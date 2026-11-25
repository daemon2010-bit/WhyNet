package dev.whynet.core.probes

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dev.whynet.core.engine.WifiApSample
import dev.whynet.core.engine.WifiChannelAnalyzer
import dev.whynet.core.model.CapabilityState
import dev.whynet.core.model.NetworkSnapshot
import dev.whynet.core.model.TransportKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.concurrent.TimeUnit

class EnvironmentCollector(
    private val context: Context,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val externalIpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .callTimeout(2000, TimeUnit.MILLISECONDS)
        .build()

    fun getActiveNetwork(): Network? = connectivityManager.activeNetwork

    fun readCapabilities(): CapabilityState {
        val hasFineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val hasNearbyWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            hasFineLocation
        }
        val hasPhoneState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED

        return CapabilityState(
            hasWifiScanPermission = hasNearbyWifi || hasFineLocation,
            hasPhoneStatePermission = hasPhoneState,
            hasVpnPermission = false,
            supportsDnsResolver = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun collectSnapshot(fetchExternalIp: Boolean = true): NetworkSnapshot =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val activeNetwork = connectivityManager.activeNetwork
            val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
            val linkProps = activeNetwork?.let { connectivityManager.getLinkProperties(it) }

            val anyVpnActive = isAnyVpnActive(caps)
            val transport = resolveTransport(caps)

            val dnsServers = linkProps?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()
            val gatewayIp = linkProps?.routes
                ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway?.hostAddress
                ?: linkProps?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress

            val localIpv4 = linkProps?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
            val localIpv6 = linkProps?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet6Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress

            val proxyStr = linkProps?.httpProxy?.let { proxy ->
                when {
                    proxy.pacFileUrl != null && proxy.pacFileUrl.toString().isNotBlank() -> "PAC: ${proxy.pacFileUrl}"
                    !proxy.host.isNullOrBlank() -> "${proxy.host}:${proxy.port}"
                    else -> null
                }
            }

            // Wi-Fi info (API 31+ NetworkCapabilities.transportInfo)
            val wifiInfo = (caps?.transportInfo as? WifiInfo) ?: readLegacyWifiInfo()
            val wifiFreq = wifiInfo?.frequency?.takeIf { it > 0 }
            val wifiBandGhz = wifiFreq?.let { freq ->
                when {
                    freq in 2400..2500 -> 2.4
                    freq in 4900..5900 -> 5.0
                    freq in 5925..7125 -> 6.0
                    else -> null
                }
            }
            val wifiChannel = wifiFreq?.let { freq ->
                when {
                    freq == 2484 -> 14
                    freq in 2412..2472 -> (freq - 2407) / 5
                    freq in 5170..5885 -> (freq - 5000) / 5
                    freq in 5955..7115 -> (freq - 5950) / 5
                    else -> null
                }
            }
            val wifiSsid = wifiInfo?.ssid?.trim('"')
                ?.takeIf { it.isNotBlank() && !it.equals("<unknown ssid>", ignoreCase = true) }
            val wifiRssi = wifiInfo?.rssi?.takeIf { it in -120..-1 }
            val wifiLinkSpeed = wifiInfo?.linkSpeed?.takeIf { it > 0 }
            val wifiTxSpeed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifiInfo?.txLinkSpeedMbps?.takeIf { it > 0 }
            } else {
                wifiLinkSpeed
            }
            val wifiRxSpeed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifiInfo?.rxLinkSpeedMbps?.takeIf { it > 0 }
            } else {
                wifiLinkSpeed
            }
            val wifiStd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wifiInfo != null) {
                when (wifiInfo.wifiStandard) {
                    ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4"
                    ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5"
                    ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6"
                    ScanResult.WIFI_STANDARD_11BE -> "Wi-Fi 7"
                    else -> null
                }
            } else {
                null
            }

            val cellTower = readCellularTowerIfPermitted()
            val wifiInterference = if (transport == TransportKind.WIFI && wifiBandGhz != null && wifiChannel != null) {
                readWifiInterferenceIfPermitted(wifiBandGhz, wifiChannel, wifiInfo?.bssid)
            } else {
                null
            }

            val externalIp = if (fetchExternalIp && transport != TransportKind.NONE && caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) != true) {
                fetchExternalIpAddress()
            } else {
                null
            }

            val isCgnatLikely = if (localIpv4 != null && externalIp != null) {
                isPrivateOrCgnatIpv4(localIpv4) && localIpv4 != externalIp
            } else {
                null
            }

            NetworkSnapshot(
                capturedAtEpochMs = now,
                transport = transport,
                isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                isCaptivePortal = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
                hasInternetCapability = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                activeExternalVpn = anyVpnActive,
                systemProxy = proxyStr,
                privateDnsActive = linkProps?.isPrivateDnsActive == true,
                privateDnsServerName = linkProps?.privateDnsServerName,
                dnsServers = dnsServers,
                gatewayIp = gatewayIp,
                localIpv4 = localIpv4,
                localIpv6 = localIpv6,
                externalIp = externalIp,
                isCgnatLikely = isCgnatLikely,
                interfaceMtu = linkProps?.mtu?.takeIf { it > 0 },
                wifiBandGhz = wifiBandGhz,
                wifiRssiDbm = wifiRssi,
                wifiLinkSpeedMbps = wifiLinkSpeed,
                wifiSsid = wifiSsid,
                wifiChannel = wifiChannel,
                wifiTxLinkSpeedMbps = wifiTxSpeed,
                wifiRxLinkSpeedMbps = wifiRxSpeed,
                wifiStandard = wifiStd,
                cellularRsrpDbm = cellTower?.rsrpDbm,
                cellularNetworkType = if (transport == TransportKind.CELLULAR) readCellularGen() else null,
                cellularEnbId = cellTower?.enbId,
                cellularTac = cellTower?.tac,
                cellularBandLabel = cellTower?.bandLabel,
                wifiChannelCongestionPct = wifiInterference?.congestionPercent,
                wifiSameChannelInterferers = wifiInterference?.interferers?.size,
                wifiRecommendedChannel = wifiInterference?.recommendedChannel,
            )
        }

    private fun isAnyVpnActive(activeCaps: NetworkCapabilities?): Boolean {
        if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return true
        return runCatching {
            @Suppress("DEPRECATION")
            connectivityManager.allNetworks.any { net ->
                connectivityManager.getNetworkCapabilities(net)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }.getOrDefault(false)
    }

    private fun resolveTransport(caps: NetworkCapabilities?): TransportKind {
        if (caps == null) return TransportKind.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TransportKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TransportKind.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TransportKind.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> {
                // Если активен VPN, определяем физический транспорт по всем не-VPN сетям (приоритет: Wi-Fi -> 4G/5G -> Ethernet)
                @Suppress("DEPRECATION")
                val physicalCaps = connectivityManager.allNetworks
                    .mapNotNull { connectivityManager.getNetworkCapabilities(it) }
                    .filter { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
                when {
                    physicalCaps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } -> TransportKind.WIFI
                    physicalCaps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } -> TransportKind.CELLULAR
                    physicalCaps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } -> TransportKind.ETHERNET
                    else -> TransportKind.NONE
                }
            }
            else -> TransportKind.OTHER
        }
    }

    @Suppress("DEPRECATION")
    private fun readLegacyWifiInfo(): WifiInfo? = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.connectionInfo
    }.getOrNull()

    private data class CellTowerSnapshot(
        val rsrpDbm: Int?,
        val enbId: Int?,
        val tac: Int?,
        val bandLabel: String?,
    )

    @SuppressLint("MissingPermission")
    private fun readCellularTowerIfPermitted(): CellTowerSnapshot? {
        val hasLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) return null

        return runCatching {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
            val cells = tm.allCellInfo ?: return null
            for (cell in cells) {
                if (!cell.isRegistered) continue
                when (cell) {
                    is CellInfoLte -> {
                        val id = cell.cellIdentity
                        val rsrp = cell.cellSignalStrength.rsrp.takeIf { it in -140..-44 }
                        val ci = id.ci.takeIf { it > 0 && it != Int.MAX_VALUE }
                        val enb = ci?.let { it shr 8 }
                        val tac = id.tac.takeIf { it > 0 && it != Int.MAX_VALUE }
                        val band = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            id.bands.firstOrNull()?.let { "LTE B$it" }
                        } else {
                            null
                        }
                        return CellTowerSnapshot(rsrp, enb, tac, band)
                    }
                    is CellInfoNr -> {
                        val nr = cell.cellSignalStrength as? CellSignalStrengthNr
                        val rsrp = nr?.ssRsrp?.takeIf { it in -140..-44 }
                        return CellTowerSnapshot(rsrp, null, null, "5G NR")
                    }
                }
            }
            null
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun readWifiInterferenceIfPermitted(
        bandGhz: Double,
        currentChannel: Int,
        connectedBssid: String?,
    ) = runCatching {
        val caps = readCapabilities()
        if (!caps.hasWifiScanPermission) return@runCatching null
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return@runCatching null
        val raw = wm.scanResults.orEmpty()
        if (raw.isEmpty()) return@runCatching null
        val samples = raw.mapNotNull { sr ->
            val freq = sr.frequency
            val band = when {
                freq in 2400..2500 -> 2.4
                freq in 4900..5900 -> 5.0
                freq in 5925..7125 -> 6.0
                else -> return@mapNotNull null
            }
            val ch = when {
                freq == 2484 -> 14
                freq in 2412..2472 -> (freq - 2407) / 5
                freq in 5170..5885 -> (freq - 5000) / 5
                freq in 5955..7115 -> (freq - 5950) / 5
                else -> return@mapNotNull null
            }
            val width = when (sr.channelWidth) {
                ScanResult.CHANNEL_WIDTH_40MHZ -> 40
                ScanResult.CHANNEL_WIDTH_80MHZ -> 80
                ScanResult.CHANNEL_WIDTH_160MHZ -> 160
                else -> 20
            }
            WifiApSample(
                ssid = sr.SSID.orEmpty(),
                bssid = sr.BSSID.orEmpty(),
                frequencyMhz = freq,
                centerFreqMhz = sr.centerFreq0.takeIf { it > 0 } ?: freq,
                bandGhz = band,
                channel = ch,
                channelWidthMhz = width,
                rssiDbm = sr.level,
                isConnected = connectedBssid != null && sr.BSSID.equals(connectedBssid, ignoreCase = true),
            )
        }
        WifiChannelAnalyzer.analyzeChannelInterferers(samples, bandGhz, explicitChannel = currentChannel)
    }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun readCellularGen(): String? {
        val hasPhone = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPhone) return null
        return runCatching {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
            when (tm.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
                TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                else -> "Mobile"
            }
        }.getOrNull()
    }

    private fun fetchExternalIpAddress(): String? = runCatching {
        val req = Request.Builder()
            .url("https://api.ipify.org?format=text")
            .header("User-Agent", "WhyNet-Android/1.0")
            .build()
        externalIpClient.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body.string().trim().takeIf { it.length in 7..45 } else null
        }
    }.getOrNull()

    private fun isPrivateOrCgnatIpv4(ip: String): Boolean {
        val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 10 ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 100 && parts[1] in 64..127)
    }
}
