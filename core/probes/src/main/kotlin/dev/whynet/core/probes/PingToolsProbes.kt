package dev.whynet.core.probes

import dev.whynet.core.engine.NetToolsMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class ServiceLatencyEntry(
    val code: String,
    val name: String,
    val host: String,
    val protocol: String,
    val rttMs: Long?,
    val isOk: Boolean,
)

data class PortScanEntry(
    val port: Int,
    val serviceName: String,
    val rttMs: Long,
)

data class PortScanReport(
    val targetHost: String,
    val resolvedIp: String,
    val scannedCount: Int,
    val openPorts: List<PortScanEntry>,
    val durationMs: Long,
)

data class DnsRecordEntry(
    val type: String,
    val ttl: Int?,
    val value: String,
)

data class DnsLookupProReport(
    val hostname: String,
    val provider: String,
    val records: List<DnsRecordEntry>,
    val durationMs: Long,
    val error: String? = null,
)

data class WhoisIpReport(
    val query: String,
    val resolvedIp: String,
    val country: String? = null,
    val city: String? = null,
    val isp: String? = null,
    val org: String? = null,
    val asn: String? = null,
    val cidr: String? = null,
    val netName: String? = null,
    val rawSummary: String,
)

data class BufferbloatReport(
    val downloadMbps: Double,
    val idleGatewayMs: Double?,
    val loadedGatewayMs: Double?,
    val idleExternalMs: Double?,
    val loadedExternalMs: Double?,
    val grade: String,
    val verdict: String,
    val testServer: String,
    val speedSamplesMbps: List<Double> = emptyList(),
    val loadedPingSamplesMs: List<Double> = emptyList(),
    val cloudflareBlocked: Boolean = false,
)

data class MassDnsEntry(
    val name: String,
    val serverIp: String,
    val rttMs: Long?,
    val resolvedIp: String?,
    val isOk: Boolean,
    val isSuspicious: Boolean = false,
    val note: String,
)

data class MassDnsBenchmarkReport(
    val testDomain: String,
    val entries: List<MassDnsEntry>,
    val fastestClean: MassDnsEntry?,
    val summary: String,
    val durationMs: Long,
)

data class TspuContourEntry(
    val label: String,
    val target: String,
    val status: String, // "OK", "WARN", "FAIL"
    val detail: String,
    val rttMs: Long? = null,
    val bytesRead: Long = 0L,
)

data class TspuTestReport(
    val entries: List<TspuContourEntry>,
    val looksLikeWhitelist: Boolean,
    val has16KbDrop: Boolean,
    val verdict: String,
    val recommendation: String,
    val durationMs: Long,
)

/**
 * Расширенные сетевые инструменты в стиле PingTools / WiFiman (Этап 3):
 * Port Scanner, DNS Lookup Pro, WHOIS/IP Info, Wake-on-LAN, Bufferbloat/Speedtest и SSDP discovery.
 */
class PingToolsProbes {

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3500, TimeUnit.MILLISECONDS)
        .readTimeout(4500, TimeUnit.MILLISECONDS)
        .callTimeout(6000, TimeUnit.MILLISECONDS)
        .build()

    private val speedClient = OkHttpClient.Builder()
        .connectTimeout(2200, TimeUnit.MILLISECONDS)
        .readTimeout(4500, TimeUnit.MILLISECONDS)
        .callTimeout(6000, TimeUnit.MILLISECONDS)
        .build()

    /**
     * 1. Быстрый параллельный TCP Port Scanner (до 24 одновременных сокетов).
     */
    suspend fun scanTcpPorts(
        host: String,
        ports: List<Int>,
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> },
    ): Result<PortScanReport> = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val cleanHost = host.trim()
        val resolved = runCatching {
            InetAddress.getAllByName(cleanHost).firstOrNull { it is Inet4Address }?.hostAddress
                ?: InetAddress.getByName(cleanHost).hostAddress
        }.getOrNull() ?: return@withContext Result.failure(IllegalArgumentException("Не удалось разрешить адрес: $cleanHost"))

        if (ports.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Список портов пуст"))
        }

        val sem = Semaphore(24)
        val doneCount = AtomicInteger(0)
        val total = ports.size

        val open = coroutineScope {
            ports.map { port ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        val t0 = System.currentTimeMillis()
                        val isOpen = runCatching {
                            Socket().use { s ->
                                s.connect(InetSocketAddress(resolved, port), 550)
                            }
                            true
                        }.getOrDefault(false)
                        val rtt = System.currentTimeMillis() - t0
                        val done = doneCount.incrementAndGet()
                        if (done % 4 == 0 || done == total) {
                            onProgress(done, total)
                        }
                        if (isOpen) {
                            PortScanEntry(
                                port = port,
                                serviceName = NetToolsMath.knownServiceName(port),
                                rttMs = rtt,
                            )
                        } else {
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull().sortedBy { it.port }
        }

        Result.success(
            PortScanReport(
                targetHost = cleanHost,
                resolvedIp = resolved,
                scannedCount = total,
                openPorts = open,
                durationMs = System.currentTimeMillis() - started,
            ),
        )
    }

    /**
     * 2. Расширенный DNS Lookup Pro (A, AAAA, MX, TXT, NS, CNAME, SOA) через DoH с резервом.
     */
    suspend fun runDnsLookupPro(
        hostname: String,
        types: List<String> = listOf("A", "AAAA", "CNAME", "MX", "NS", "TXT", "SOA"),
    ): DnsLookupProReport = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val clean = hostname.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/')
        val endpoints = listOf("1.1.1.1" to "https://1.1.1.1/dns-query", "8.8.8.8" to "https://dns.google/resolve")
        var usedProvider = "1.1.1.1"
        var lastErr: String? = null
        val collected = mutableListOf<DnsRecordEntry>()

        for ((providerName, endpoint) in endpoints) {
            usedProvider = providerName
            var anyRequestOk = false
            collected.clear()
            for (type in types) {
                try {
                    val req = Request.Builder()
                        .url("$endpoint?name=$clean&type=$type")
                        .header("Accept", "application/dns-json")
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            anyRequestOk = true
                            val body = resp.body.string()
                            val root = json.parseToJsonElement(body).jsonObject
                            val answers = root["Answer"]?.jsonArray.orEmpty()
                            for (el in answers) {
                                val obj = el.jsonObject
                                val data = obj["data"]?.jsonPrimitive?.content ?: continue
                                val ttl = obj["TTL"]?.jsonPrimitive?.intOrNull
                                val recTypeCode = obj["type"]?.jsonPrimitive?.intOrNull
                                val recTypeName = dnsTypeCodeToName(recTypeCode, type)
                                collected += DnsRecordEntry(type = recTypeName, ttl = ttl, value = data)
                            }
                        }
                    }
                } catch (e: Exception) {
                    lastErr = "${e.javaClass.simpleName}: ${e.message}"
                }
            }
            if (anyRequestOk) {
                lastErr = null
                break
            }
        }

        // Если оба DoH недоступны (например, блокировка на сети), пробуем хотя бы системный DNS A/AAAA
        if (collected.isEmpty() && lastErr != null) {
            runCatching {
                InetAddress.getAllByName(clean).forEach { addr ->
                    val t = if (addr is Inet4Address) "A" else "AAAA"
                    addr.hostAddress?.let { collected += DnsRecordEntry(type = t, ttl = null, value = it) }
                }
                if (collected.isNotEmpty()) {
                    usedProvider = "Системный DNS (резерв)"
                    lastErr = null
                }
            }
        }

        DnsLookupProReport(
            hostname = clean,
            provider = usedProvider,
            records = collected.distinctBy { "${it.type}|${it.value}" },
            durationMs = System.currentTimeMillis() - started,
            error = lastErr,
        )
    }

    /**
     * 3. WHOIS / IP Info (Geo, ASN, провайдер, RDAP CIDR-блок).
     */
    suspend fun lookupWhoisIp(hostOrIp: String): Result<WhoisIpReport> = withContext(Dispatchers.IO) {
        val clean = hostOrIp.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')
        val ip = runCatching {
            InetAddress.getAllByName(clean).firstOrNull { it is Inet4Address }?.hostAddress
                ?: InetAddress.getByName(clean).hostAddress
        }.getOrNull() ?: return@withContext Result.failure(IllegalArgumentException("Не удалось разрешить адрес: $clean"))

        var country: String? = null
        var city: String? = null
        var isp: String? = null
        var org: String? = null
        var asn: String? = null
        var cidr: String? = null
        var netName: String? = null

        // 1) ip-api.com (быстрый Geo + ISP + ASN)
        runCatching {
            val req = Request.Builder()
                .url("http://ip-api.com/json/$ip?fields=status,country,city,isp,org,as")
                .header("User-Agent", "WhyNet-Android/1.0")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val obj = json.parseToJsonElement(resp.body.string()).jsonObject
                    if (obj["status"]?.jsonPrimitive?.content == "success") {
                        country = obj["country"]?.jsonPrimitive?.content
                        city = obj["city"]?.jsonPrimitive?.content
                        isp = obj["isp"]?.jsonPrimitive?.content
                        org = obj["org"]?.jsonPrimitive?.content
                        asn = obj["as"]?.jsonPrimitive?.content
                    }
                }
            }
        }

        // 2) RDAP (официальный WHOIS по HTTPS)
        runCatching {
            val req = Request.Builder()
                .url("https://rdap.org/ip/$ip")
                .header("Accept", "application/rdap+json")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val obj = json.parseToJsonElement(resp.body.string()).jsonObject
                    netName = obj["name"]?.jsonPrimitive?.content
                    val startAddr = obj["startAddress"]?.jsonPrimitive?.content
                    val endAddr = obj["endAddress"]?.jsonPrimitive?.content
                    val cidrArr = obj["cidr0_cidrs"]?.jsonArray
                    cidr = if (cidrArr != null && cidrArr.isNotEmpty()) {
                        val first = cidrArr[0].jsonObject
                        val v4 = first["v4prefix"]?.jsonPrimitive?.content
                        val len = first["length"]?.jsonPrimitive?.intOrNull
                        if (v4 != null && len != null) "$v4/$len" else null
                    } else if (startAddr != null && endAddr != null) {
                        "$startAddr - $endAddr"
                    } else {
                        null
                    }
                    if (country.isNullOrBlank()) {
                        country = obj["country"]?.jsonPrimitive?.content
                    }
                }
            }
        }

        val summary = buildString {
            appendLine("IP: $ip")
            if (!asn.isNullOrBlank()) appendLine("ASN: $asn")
            if (!isp.isNullOrBlank()) appendLine("Провайдер: $isp")
            if (!org.isNullOrBlank() && org != isp) appendLine("Организация: $org")
            if (!netName.isNullOrBlank()) appendLine("Блок (RDAP): $netName${cidr?.let { " ($it)" } ?: ""}")
            else if (!cidr.isNullOrBlank()) appendLine("Диапазон: $cidr")
            if (!country.isNullOrBlank()) appendLine("Локация: $country${city?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""}")
        }.trim()

        Result.success(
            WhoisIpReport(
                query = clean,
                resolvedIp = ip,
                country = country,
                city = city,
                isp = isp,
                org = org,
                asn = asn,
                cidr = cidr,
                netName = netName,
                rawSummary = summary,
            ),
        )
    }

    /**
     * 4. Wake-on-LAN: отправка 3 UDP Magic Packets на broadcast-адрес.
     */
    suspend fun sendWakeOnLan(
        macAddress: String,
        broadcastIp: String = "255.255.255.255",
        port: Int = 9,
    ): Result<String> = withContext(Dispatchers.IO) {
        val magic = NetToolsMath.buildWolMagicPacket(macAddress)
            ?: return@withContext Result.failure(IllegalArgumentException("Неверный MAC-адрес (ожидается AA:BB:CC:DD:EE:FF)"))
        runCatching {
            val dest = InetAddress.getByName(broadcastIp.ifBlank { "255.255.255.255" })
            DatagramSocket().use { socket ->
                socket.broadcast = true
                repeat(3) {
                    socket.send(DatagramPacket(magic, magic.size, dest, port))
                    Thread.sleep(60)
                }
            }
            "Отправлено 3 Magic Packet (102 Б) на ${dest.hostAddress}:$port для MAC ${macAddress.uppercase()}"
        }
    }

    /**
     * 5. Тест скорости загрузки + Bufferbloat (сравнивает пинг в покое и под 5-секундной нагрузкой).
     */
    suspend fun runBufferbloatAndSpeedTest(
        gatewayIp: String?,
        onProgress: suspend (String) -> Unit = {},
    ): Result<BufferbloatReport> = withContext(Dispatchers.IO) {
        val extHost = pickReachablePingHost()
        onProgress("1/3 Измеряем пинг без нагрузки (шлюз + $extHost)...")

        val idleGwSamples = mutableListOf<Double>()
        val idleExtSamples = mutableListOf<Double>()
        repeat(5) {
            if (!gatewayIp.isNullOrBlank()) {
                probeTcpQuick(gatewayIp, 80, 500, acceptReset = true)?.let(idleGwSamples::add)
            }
            probeTcpQuick(extHost, 443, 800, acceptReset = false)?.let(idleExtSamples::add)
            delay(100)
        }
        val idleGw = idleGwSamples.takeIf { it.isNotEmpty() }?.average()
        val idleExt = idleExtSamples.takeIf { it.isNotEmpty() }?.average()

        onProgress("2/3 Нагружаем канал (5 с) и измеряем задержку под нагрузкой...")
        val loadedGwSamples = mutableListOf<Double>()
        val loadedExtSamples = mutableListOf<Double>()
        val speedSeries = mutableListOf<Double>()
        val totalBytes = AtomicLong(0L)
        var usedServer = "РФ-резерв"
        var cfBlocked = false
        val loadStarted = System.currentTimeMillis()

        // Сначала пробуем быстрый РФ-сервер (чтобы не терять время на таймауте заблокированного CF), затем Cloudflare и Hetzner
        val testUrls = listOf(
            "selectel.ru (РФ)" to "https://speedtest.selectel.ru/10MB",
            "yandex.ru (РФ)" to "https://mirror.yandex.ru/ubuntu/ls-lR.gz",
            "speed.cloudflare.com" to "https://speed.cloudflare.com/__down?bytes=15000000",
            "ash-speed.hetzner.com" to "https://ash-speed.hetzner.com/10MB.bin",
        )

        coroutineScope {
            val pingJob = launch(Dispatchers.IO) {
                var prevBytes = 0L
                var prevTime = System.currentTimeMillis()
                while (isActive && System.currentTimeMillis() - loadStarted < 5500L) {
                    if (!gatewayIp.isNullOrBlank()) {
                        probeTcpQuick(gatewayIp, 80, 600, acceptReset = true)?.let {
                            synchronized(loadedGwSamples) { loadedGwSamples += it }
                        }
                    }
                    probeTcpQuick(extHost, 443, 1000, acceptReset = false)?.let {
                        synchronized(loadedExtSamples) { loadedExtSamples += it }
                    }
                    val now = System.currentTimeMillis()
                    val dt = (now - prevTime).coerceAtLeast(100L) / 1000.0
                    val curBytes = totalBytes.get()
                    val instMbps = ((curBytes - prevBytes).coerceAtLeast(0L) * 8.0) / (dt * 1_000_000.0)
                    synchronized(speedSeries) { speedSeries += instMbps }
                    prevBytes = curBytes
                    prevTime = now
                    delay(350)
                }
            }

            // Пытаемся качать поток 5 секунд с первого доступного тестового сервера
            for ((label, url) in testUrls) {
                val attemptStart = System.currentTimeMillis()
                val attemptBytes = AtomicLong(0L)
                val ok = runCatching {
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", "WhyNet-Speed/2.0")
                        .build()
                    speedClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@runCatching false
                        usedServer = label
                        val buf = ByteArray(16384)
                        val stream = resp.body.byteStream()
                        while (System.currentTimeMillis() - attemptStart < 5000L) {
                            val n = stream.read(buf)
                            if (n <= 0) break
                            attemptBytes.addAndGet(n.toLong())
                            totalBytes.addAndGet(n.toLong())
                        }
                        attemptBytes.get() > 64_000L
                    }
                }.getOrDefault(false)
                if (ok) {
                    break
                } else if (label == "speed.cloudflare.com") {
                    cfBlocked = true
                }
            }
            pingJob.cancel()
        }

        val elapsedSec = ((System.currentTimeMillis() - loadStarted).coerceAtLeast(500L)) / 1000.0
        val mbps = (totalBytes.get() * 8.0) / (elapsedSec * 1_000_000.0)
        val loadedGw = synchronized(loadedGwSamples) { loadedGwSamples.takeIf { it.isNotEmpty() }?.average() }
        val loadedExt = synchronized(loadedExtSamples) { loadedExtSamples.takeIf { it.isNotEmpty() }?.average() }

        val refIdle = idleExt ?: idleGw ?: 0.0
        val refLoaded = loadedExt ?: loadedGw ?: refIdle
        val (grade, verdict) = if (mbps < 0.05) {
            "—" to "Тест скорости не выполнен (0,0 Мбит/с): серверы загрузки недоступны или заблокированы на текущей сети."
        } else {
            NetToolsMath.gradeBufferbloat(refIdle, refLoaded)
        }

        onProgress("3/3 Готово: ${"%.1f".format(mbps)} Мбит/с · Bufferbloat $grade")
        Result.success(
            BufferbloatReport(
                downloadMbps = mbps,
                idleGatewayMs = idleGw,
                loadedGatewayMs = loadedGw,
                idleExternalMs = idleExt,
                loadedExternalMs = loadedExt,
                grade = grade,
                verdict = verdict,
                testServer = usedServer,
                speedSamplesMbps = synchronized(speedSeries) { speedSeries.toList() },
                loadedPingSamplesMs = synchronized(loadedExtSamples) { loadedExtSamples.toList() },
                cloudflareBlocked = cfBlocked,
            ),
        )
    }

    /**
     * 5b. Проверка всех массовых DNS (UDP:53 + системный) на скорость ответа и подмену.
     */
    suspend fun runMassDnsBenchmark(
        testDomain: String = "ya.ru",
    ): MassDnsBenchmarkReport = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val domain = testDomain.trim().lowercase().ifBlank { "ya.ru" }
        val targets = listOf(
            "Системный DNS" to "system",
            "Яндекс DNS" to "77.88.8.8",
            "AdGuard DNS" to "94.140.14.14",
            "Google DNS" to "8.8.8.8",
            "Quad9 DNS" to "9.9.9.9",
            "Cloudflare DNS" to "1.1.1.1",
            "OpenDNS" to "208.67.222.222",
        )

        val entries = coroutineScope {
            targets.map { (name, ip) ->
                async(Dispatchers.IO) {
                    if (ip == "system") {
                        val t0 = System.nanoTime()
                        val res = runCatching {
                            InetAddress.getAllByName(domain).firstOrNull { it is Inet4Address }?.hostAddress
                        }.getOrNull()
                        val ms = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                        val susp = isBogusDnsIp(res)
                        MassDnsEntry(
                            name = name,
                            serverIp = "auto",
                            rttMs = if (res != null) ms else null,
                            resolvedIp = res,
                            isOk = res != null && !susp,
                            isSuspicious = susp,
                            note = when {
                                res == null -> "Нет ответа"
                                susp -> "Подмена ($res)"
                                else -> "OK · $res"
                            },
                        )
                    } else {
                        queryUdpDnsA(name, ip, domain)
                    }
                }
            }.awaitAll()
        }.sortedWith(compareByDescending<MassDnsEntry> { it.isOk }.thenBy { it.rttMs ?: Long.MAX_VALUE })

        val fastest = entries.firstOrNull { it.isOk }
        val failedList = entries.filterNot { it.isOk }.map { it.name }
        val summary = buildString {
            if (fastest != null) {
                append("Самый быстрый чистый DNS: ${fastest.name} (${fastest.serverIp}) - ${fastest.rttMs} мс. ")
            } else {
                append("Ни один DNS не ответил корректно. ")
            }
            if (entries.any { it.isSuspicious }) {
                append("Внимание: обнаружена подмена IP на части серверов. ")
            }
            if (failedList.isNotEmpty()) {
                append("Не отвечают на текущей сети: ${failedList.joinToString()}.")
            }
        }.trim()

        MassDnsBenchmarkReport(
            testDomain = domain,
            entries = entries,
            fastestClean = fastest,
            summary = summary,
            durationMs = System.currentTimeMillis() - started,
        )
    }

    /**
     * 5c. Тест ТСПУ / DPI и белых списков (включая проверку порога 16 КБ на AWS CloudFront и Cloudflare).
     */
    suspend fun runTspuAndContoursTest(): TspuTestReport = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val probes = coroutineScope {
            listOf(
                async(Dispatchers.IO) { probeHttpContour("РФ эталон (ya.ru)", "https://ya.ru", minBytes = 2048) },
                async(Dispatchers.IO) { probeHttpContour("Порог 16 КБ (AWS)", "https://dwd665skciqzl.cloudfront.net/", minBytes = 18_000, check16Kb = true) },
                async(Dispatchers.IO) { probeHttpContour("Cloudflare CDN", "https://speed.cloudflare.com/__down?bytes=32768", minBytes = 24_000, check16Kb = true) },
                async(Dispatchers.IO) { probeHttpContour("Google / API", "https://www.google.com/generate_204", minBytes = 0) },
                async(Dispatchers.IO) { probeHttpContour("YouTube (SNI)", "https://www.youtube.com/generate_204", minBytes = 0) },
                async(Dispatchers.IO) { probeHttpContour("Discord (SNI)", "https://discord.com/api/v10/gateway", minBytes = 10) },
            ).awaitAll()
        }

        val ruOk = probes.firstOrNull { it.label.startsWith("РФ") }?.status == "OK"
        val blockedCount = probes.drop(1).count { it.status == "FAIL" }
        val has16Kb = probes.any { it.detail.contains("16 КБ") || it.detail.contains("DROP") }
        val whitelist = ruOk && blockedCount >= 3

        val verdict = when {
            whitelist -> "Активна фильтрация ТСПУ / режим белых списков на текущей сети"
            blockedCount >= 2 -> "Выборочная фильтрация зарубежных CDN / SNI (ТСПУ)"
            blockedCount == 1 -> "Незначительные ограничения на одном из зарубежных контуров"
            else -> "Признаков жёсткой фильтрации ТСПУ сейчас не выявлено"
        }
        val rec = when {
            whitelist || blockedCount >= 2 -> "РФ-ресурсы работают штатно, но зарубежные CDN и игры режутся оператором. Используй выборочный VPN (например, AdGuard VPN в интегрированном режиме) для нужных приложений или переключись на Wi-Fi."
            else -> "Все проверенные контуры отвечают штатно."
        }

        TspuTestReport(
            entries = probes,
            looksLikeWhitelist = whitelist,
            has16KbDrop = has16Kb,
            verdict = verdict,
            recommendation = rec,
            durationMs = System.currentTimeMillis() - started,
        )
    }

    /**
     * 5d. Быстрая авто-проба 4 контуров для главного экрана ("Контуры доступности на этой БС").
     */
    suspend fun runQuickContoursCheck(): List<TspuContourEntry> = withContext(Dispatchers.IO) {
        coroutineScope {
            listOf(
                async(Dispatchers.IO) { probeHttpContour("РФ (ya.ru)", "https://ya.ru", minBytes = 1024) },
                async(Dispatchers.IO) { probeHttpContour("CloudFront", "https://dwd665skciqzl.cloudfront.net/", minBytes = 1024, check16Kb = true) },
                async(Dispatchers.IO) { probeHttpContour("Cloudflare", "https://speed.cloudflare.com/__down?bytes=24576", minBytes = 18_000, check16Kb = true) },
                async(Dispatchers.IO) { probeUdpQuickContour() },
            ).awaitAll()
        }
    }

    /**
     * 5e. Живой монитор задержки ключевых сервисов (в стиле PingTools):
     * параллельный TCP/TLS замер RTT для 8 популярных сервисов + пользовательский хост.
     */
    suspend fun probeServicesLatency(customHost: String? = null): List<ServiceLatencyEntry> = withContext(Dispatchers.IO) {
        data class SvcSpec(val code: String, val name: String, val host: String, val port: Int, val proto: String)
        val targets = buildList {
            add(SvcSpec("CF", "Cloudflare", "1.1.1.1", 443, "TCP"))
            add(SvcSpec("GO", "Google DNS", "8.8.8.8", 443, "TCP"))
            add(SvcSpec("YA", "Яндекс", "ya.ru", 443, "HTTPS"))
            add(SvcSpec("GH", "GitHub", "github.com", 443, "HTTPS"))
            add(SvcSpec("AW", "AWS", "aws.amazon.com", 443, "HTTPS"))
            add(SvcSpec("YT", "YouTube", "youtube.com", 443, "HTTPS"))
            add(SvcSpec("DC", "Discord", "discord.com", 443, "HTTPS"))
            add(SvcSpec("TG", "Telegram", "core.telegram.org", 443, "HTTPS"))
            val extra = customHost?.trim()?.removePrefix("https://")?.removePrefix("http://")?.substringBefore('/')
            if (!extra.isNullOrBlank() && none { it.host.equals(extra, ignoreCase = true) }) {
                val code = extra.take(2).uppercase()
                add(SvcSpec(code, extra, extra, 443, "HTTPS"))
            }
        }
        coroutineScope {
            targets.map { spec ->
                async(Dispatchers.IO) {
                    val rtt = probeTcpQuick(spec.host, spec.port, timeoutMs = 1500, acceptReset = false)
                    ServiceLatencyEntry(
                        code = spec.code,
                        name = spec.name,
                        host = spec.host,
                        protocol = spec.proto,
                        rttMs = rtt?.toLong()?.coerceAtLeast(1L),
                        isOk = rtt != null,
                    )
                }
            }.awaitAll()
        }
    }

    private fun probeHttpContour(
        label: String,
        url: String,
        minBytes: Int,
        check16Kb: Boolean = false,
    ): TspuContourEntry {
        val t0 = System.currentTimeMillis()
        var readTotal = 0L
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "WhyNet/2.0")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val code = resp.code
                val buf = ByteArray(4096)
                val stream = resp.body.byteStream()
                val targetBytes = maxOf(minBytes, 4096)
                while (readTotal < targetBytes) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    readTotal += n
                }
                val rtt = System.currentTimeMillis() - t0
                val isReachableCode = code in 200..499
                if (isReachableCode && (minBytes == 0 || readTotal >= minBytes / 2 || code == 403 || code == 404)) {
                    TspuContourEntry(label, url, "OK", "$code · ${rtt}мс", rtt, readTotal)
                } else {
                    TspuContourEntry(label, url, "WARN", "HTTP $code · ${rtt}мс", rtt, readTotal)
                }
            }
        } catch (e: Exception) {
            val rtt = System.currentTimeMillis() - t0
            val msg = e.message.orEmpty()
            val detail = when {
                check16Kb && readTotal in 1..20_000 -> "DROP >16 КБ"
                e is java.net.SocketTimeoutException -> "DROP TLS"
                msg.contains("reset", ignoreCase = true) -> "RST DPI"
                else -> "БЛОК / СБОЙ"
            }
            TspuContourEntry(label, url, "FAIL", detail, rtt, readTotal)
        }
    }

    private fun probeUdpQuickContour(): TspuContourEntry {
        val t0 = System.currentTimeMillis()
        val ok = runCatching {
            // STUN Binding Request к stun.l.google.com:19302
            val pktData = byteArrayOf(
                0x00, 0x01, 0x00, 0x00,
                0x21, 0x12, 0xA4.toByte(), 0x42,
                0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte(), 0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(),
            )
            DatagramSocket().use { s ->
                s.soTimeout = 1500
                val addr = InetAddress.getByName("stun.l.google.com")
                s.send(DatagramPacket(pktData, pktData.size, addr, 19302))
                val resp = ByteArray(256)
                s.receive(DatagramPacket(resp, resp.size))
                true
            }
        }.getOrDefault(false)
        val rtt = System.currentTimeMillis() - t0
        return if (ok) {
            val st = if (rtt > 500) "WARN" else "OK"
            TspuContourEntry("Звонки UDP", "stun.l.google.com:19302", st, "${rtt}мс", rtt)
        } else {
            TspuContourEntry("Звонки UDP", "stun.l.google.com:19302", "WARN", "TIMEOUT", rtt)
        }
    }

    private fun queryUdpDnsA(name: String, serverIp: String, domain: String): MassDnsEntry {
        val t0 = System.nanoTime()
        return try {
            val query = buildDnsAQuery(domain)
            val respBuf = ByteArray(512)
            DatagramSocket().use { socket ->
                socket.soTimeout = 1500
                val addr = InetAddress.getByName(serverIp)
                socket.send(DatagramPacket(query, query.size, addr, 53))
                val pkt = DatagramPacket(respBuf, respBuf.size)
                socket.receive(pkt)
                val rtt = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                val ip = parseFirstIpv4FromDnsResponse(respBuf, pkt.length)
                val susp = isBogusDnsIp(ip)
                MassDnsEntry(
                    name = name,
                    serverIp = serverIp,
                    rttMs = rtt,
                    resolvedIp = ip,
                    isOk = ip != null && !susp,
                    isSuspicious = susp,
                    note = when {
                        ip == null -> "Пустой ответ"
                        susp -> "Подмена ($ip)"
                        else -> "${rtt}мс · $ip"
                    },
                )
            }
        } catch (_: Exception) {
            MassDnsEntry(name, serverIp, null, null, false, false, "TIMEOUT")
        }
    }

    private fun buildDnsAQuery(domain: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // Header: ID=0x1234, Flags=0x0100 (RD=1), QDCOUNT=1
        out.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        domain.split('.').filter { it.isNotBlank() }.forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0) // root
        out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01)) // QTYPE=A, QCLASS=IN
        return out.toByteArray()
    }

    private fun parseFirstIpv4FromDnsResponse(buf: ByteArray, len: Int): String? {
        if (len < 12) return null
        val anCount = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
        if (anCount <= 0) return null
        var idx = 12
        // Skip question
        while (idx < len && buf[idx].toInt() != 0) {
            val l = buf[idx].toInt() and 0xFF
            idx += l + 1
        }
        idx += 5 // null + QTYPE(2) + QCLASS(2)
        repeat(anCount) {
            if (idx + 12 > len) return null
            idx += if ((buf[idx].toInt() and 0xC0) == 0xC0) 2 else {
                var p = idx
                while (p < len && buf[p].toInt() != 0) p += (buf[p].toInt() and 0xFF) + 1
                (p - idx) + 1
            }
            if (idx + 10 > len) return null
            val type = ((buf[idx].toInt() and 0xFF) shl 8) or (buf[idx + 1].toInt() and 0xFF)
            val rdLen = ((buf[idx + 8].toInt() and 0xFF) shl 8) or (buf[idx + 9].toInt() and 0xFF)
            idx += 10
            if (type == 1 && rdLen == 4 && idx + 4 <= len) {
                return "${buf[idx].toInt() and 0xFF}.${buf[idx + 1].toInt() and 0xFF}.${buf[idx + 2].toInt() and 0xFF}.${buf[idx + 3].toInt() and 0xFF}"
            }
            idx += rdLen
        }
        return null
    }

    private fun isBogusDnsIp(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        return ip == "0.0.0.0" || ip == "127.0.0.1" || ip.startsWith("127.")
    }

    /**
     * 6. Быстрый SSDP (UPnP) M-SEARCH по локальной сети для распознавания реальных имён устройств
     * (роутеры, Smart TV, NAS, медиаплееры) в LAN-сканере.
     */
    fun discoverSsdpNames(timeoutMs: Int = 900): Map<String, String> {
        val found = mutableMapOf<String, String>()
        runCatching {
            val payload = (
                "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 1\r\n" +
                    "ST: ssdp:all\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
            DatagramSocket().use { socket ->
                socket.soTimeout = 250
                val group = InetAddress.getByName("239.255.255.250")
                socket.send(DatagramPacket(payload, payload.size, group, 1900))
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(1536)
                while (System.currentTimeMillis() < deadline) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(pkt)
                        val ip = pkt.address?.hostAddress ?: continue
                        val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                        val server = Regex("(?im)^SERVER:\\s*(.+)$").find(text)?.groupValues?.getOrNull(1)?.trim()
                        if (!server.isNullOrBlank() && ip !in found) {
                            found[ip] = server.take(42)
                        }
                    } catch (_: Exception) {
                        // сокет таймаут одного чтения
                    }
                }
            }
        }
        return found
    }

    private fun pickReachablePingHost(): String {
        for (h in listOf("ya.ru", "1.1.1.1", "8.8.8.8")) {
            if (probeTcpQuick(h, 443, 600, acceptReset = false) != null) return h
        }
        return "ya.ru"
    }

    private fun probeTcpQuick(host: String, port: Int, timeoutMs: Int, acceptReset: Boolean): Double? {
        val t0 = System.nanoTime()
        return try {
            Socket().use { s -> s.connect(InetSocketAddress(host, port), timeoutMs) }
            (System.nanoTime() - t0) / 1_000_000.0
        } catch (e: java.net.ConnectException) {
            if (acceptReset && e.message?.contains("refused", ignoreCase = true) == true) {
                (System.nanoTime() - t0) / 1_000_000.0
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun dnsTypeCodeToName(code: Int?, fallback: String): String = when (code) {
        1 -> "A"
        2 -> "NS"
        5 -> "CNAME"
        6 -> "SOA"
        12 -> "PTR"
        15 -> "MX"
        16 -> "TXT"
        28 -> "AAAA"
        else -> fallback
    }
}
