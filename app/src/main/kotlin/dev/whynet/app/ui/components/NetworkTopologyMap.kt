package dev.whynet.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whynet.app.ui.theme.WhyNetColors
import dev.whynet.app.ui.theme.tr
import dev.whynet.core.model.NetworkSnapshot
import dev.whynet.core.model.ProbeResult
import dev.whynet.core.model.ProbeStatus
import dev.whynet.core.model.StageId
import dev.whynet.core.model.TransportKind

/**
 * Компактная графическая схема "Путь пакета" v2.2:
 * - Плотные карточки без пустых вертикальных дыр;
 * - Явные видимые стрелки между узлами (в т.ч. вертикальная ветка от Вышки/Роутера в Звонки/UDP);
 * - На переходах: дельта этапа (+мс), внутри узлов: суммарная задержка до этой точки.
 */
@Composable
fun NetworkTopologyMap(
    plannedStages: List<StageId>,
    completedProbes: List<ProbeResult>,
    activeStage: StageId?,
    selectedStage: StageId?,
    isRunning: Boolean,
    transportKind: TransportKind?,
    onSelectStage: (StageId) -> Unit,
    localIpv4: String? = null,
    gatewayIp: String? = null,
    snapshot: NetworkSnapshot? = null,
    modifier: Modifier = Modifier,
) {
    val probeMap = remember(completedProbes) { completedProbes.associateBy { it.stage } }

    val okColor = WhyNetColors.OkGreen
    val warnColor = WhyNetColors.WarnAmber
    val failColor = WhyNetColors.FailRed
    val infoColor = WhyNetColors.InfoBlue
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val borderIdle = WhyNetColors.Border

    @Composable
    fun stageColor(stage: StageId): Color {
        if (activeStage == stage && isRunning) return infoColor
        val p = probeMap[stage] ?: return borderIdle
        return when (p.status) {
            ProbeStatus.SUCCESS -> {
                if (stage == StageId.LOCAL_LINK && (p.gatewayExternal?.gatewayLossPercent ?: 0.0) >= 5.0) warnColor
                else if (stage == StageId.UDP_STUN && p.durationMs > 800) warnColor
                else okColor
            }
            ProbeStatus.TIMEOUT, ProbeStatus.NETWORK_FAILURE -> failColor
            ProbeStatus.UNSUPPORTED, ProbeStatus.PERMISSION_DENIED, ProbeStatus.TOOL_ERROR -> warnColor
            ProbeStatus.CANCELLED -> mutedColor
        }
    }

    val cPhone = stageColor(StageId.ENVIRONMENT)
    val cNet = stageColor(StageId.LOCAL_LINK)
    val cDns = stageColor(StageId.DNS)
    val cTcp = stageColor(StageId.TCP)
    val cTls = stageColor(StageId.TLS_SNI)
    val cHttp = stageColor(StageId.HTTP_PAYLOAD)
    val cUdp = stageColor(StageId.UDP_STUN)

    val isWifi = (snapshot?.transport ?: transportKind) == TransportKind.WIFI
    val isCell = (snapshot?.transport ?: transportKind) == TransportKind.CELLULAR
    val netEmoji = if (isWifi) "📶" else "📡"
    val netTitle = when {
        isWifi -> tr("Роутер", "Router")
        isCell -> tr("Вышка 4G+", "Tower 4G+")
        else -> tr("Сеть", "Network")
    }

    // Реальные задержки этапов и кумулятивные (суммарные) значения до каждого узла
    val gwProbe = probeMap[StageId.LOCAL_LINK]
    val gwMetrics = gwProbe?.gatewayExternal
    val netMsVal = gwMetrics?.gatewayAvgMs?.toLong()
        ?: gwMetrics?.externalAvgMs?.toLong()
        ?: gwProbe?.durationMs

    val dnsProbe = probeMap[StageId.DNS]
    val tcpProbe = probeMap[StageId.TCP]
    val tlsProbe = probeMap[StageId.TLS_SNI]
    val httpProbe = probeMap[StageId.HTTP_PAYLOAD]
    val udpProbe = probeMap[StageId.UDP_STUN]

    val netStep = netMsVal ?: 0L
    val tcpDelta = tcpProbe?.takeIf { it.status == ProbeStatus.SUCCESS }?.durationMs
    val tlsDelta = tlsProbe?.takeIf { it.status == ProbeStatus.SUCCESS }?.durationMs
    val httpDelta = httpProbe?.takeIf { it.status == ProbeStatus.SUCCESS }?.durationMs

    val tcpCum = tcpDelta?.let { netStep + it }
    val tlsCum = if (tcpCum != null && tlsDelta != null) tcpCum + tlsDelta else tlsDelta
    val httpCum = if (tlsCum != null && httpDelta != null) tlsCum + httpDelta else httpDelta

    val netLine1 = when {
        isWifi -> snapshot?.wifiChannel?.let { "Wi-Fi · к.$it" } ?: "Wi-Fi"
        isCell -> snapshot?.cellularEnbId?.let { "eNB $it" } ?: (snapshot?.cellularBandLabel ?: "LTE")
        else -> "Канал"
    }
    val netLine2 = when {
        isWifi -> buildString {
            snapshot?.wifiRssiDbm?.let { append("${it}дБ") }
            val interf = snapshot?.wifiSameChannelInterferers
            if (interf != null && interf > 0) append(" · ${interf}сос")
        }.ifBlank { gatewayIp ?: "-" }
        isCell -> buildString {
            snapshot?.cellularBandLabel?.let { append(it.removePrefix("LTE ")) }
            snapshot?.cellularRsrpDbm?.let { if (isNotEmpty()) append(" · "); append("${it}дБ") }
        }.ifBlank { "Мобильная" }
        else -> gatewayIp ?: "-"
    }
    val netTotalText = when {
        gwMetrics != null && gwMetrics.gatewayLossPercent >= 5.0 -> "${gwMetrics.gatewayLossPercent.toInt()}% пот."
        netMsVal != null -> "${netMsVal}мс"
        else -> if (isRunning && activeStage == StageId.LOCAL_LINK) "..." else "—"
    }

    val dnsIp = dnsProbe?.dnsCompare?.systemIpv4?.firstOrNull()
        ?: dnsProbe?.dnsCompare?.dohIpv4?.firstOrNull()
        ?: snapshot?.dnsServers?.firstOrNull()
        ?: "—"
    val dnsMs = dnsProbe?.let { "+${it.durationMs}мс" } ?: if (isRunning && activeStage == StageId.DNS) "..." else "—"
    val dnsStatusLabel = when {
        dnsProbe == null -> ""
        dnsProbe.dnsCompare?.isSuspiciousSystemIp == true -> "подмена"
        dnsProbe.status == ProbeStatus.SUCCESS -> "чистый"
        else -> "сбой"
    }

    val tcpVal = when {
        tcpProbe == null -> if (isRunning && activeStage == StageId.TCP) "..." else "—"
        tcpProbe.status == ProbeStatus.SUCCESS -> "${tcpCum ?: tcpProbe.durationMs}мс"
        else -> "СБОЙ"
    }
    val tlsVal = when {
        tlsProbe == null -> if (isRunning && activeStage == StageId.TLS_SNI) "..." else "—"
        tlsProbe.status == ProbeStatus.SUCCESS -> "${tlsCum ?: tlsProbe.durationMs}мс"
        else -> "БЛОК"
    }
    val httpVal = when {
        httpProbe == null -> if (isRunning && activeStage == StageId.HTTP_PAYLOAD) "..." else "—"
        httpProbe.status == ProbeStatus.SUCCESS -> "${httpCum ?: httpProbe.durationMs}мс"
        httpProbe.status == ProbeStatus.TIMEOUT -> "DROP ✕"
        else -> "СБОЙ ✕"
    }
    val httpSub = when {
        httpProbe == null -> "Данные"
        httpProbe.status == ProbeStatus.SUCCESS -> httpProbe.httpPayload?.statusCode?.let { "HTTP $it" } ?: "OK"
        else -> "0 байт"
    }

    val udpVal = when {
        udpProbe == null -> if (isRunning && activeStage == StageId.UDP_STUN) "проверка..." else "не проверялся"
        udpProbe.status == ProbeStatus.SUCCESS -> "${udpProbe.durationMs}мс · OK"
        else -> "TIMEOUT"
    }
    val udpSub = when {
        snapshot?.isCgnatLikely == true -> "NAT: CGNAT · возможны задержки P2P"
        udpProbe?.status == ProbeStatus.SUCCESS && udpProbe.durationMs <= 400 -> "NAT: прямой · голос и игры без задержек"
        udpProbe?.status == ProbeStatus.SUCCESS -> "NAT: резервный STUN · повышенная задержка"
        else -> "STUN / WebRTC проверка"
    }

    val showUdp = plannedStages.isEmpty() || plannedStages.contains(StageId.UDP_STUN) || udpProbe != null
    val phoneIpShort = localIpv4 ?: snapshot?.localIpv4 ?: "—"
    val phoneSub = if (snapshot?.activeExternalVpn == true) "VPN/фильтр" else "MTU ${snapshot?.interfaceMtu ?: 1500}"

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // Верхняя плотная зона: [Телефон] --> [Вышка/Роутер] --> [DNS / (Связь -> Защита -> Сервер)]
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            // Видимые соединительные линии со стрелками + дельта пинга прямо НА соединениях
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                val xPhoneEnd = w * 0.195f
                val xNetStart = w * 0.235f
                val xNetEnd = w * 0.44f
                val xRightStart = w * 0.48f
                val yMidLeft = h * 0.50f
                val yDns = h * 0.19f
                val yChain = h * 0.72f

                val colPhoneNet = if (cNet == borderIdle) mutedColor.copy(alpha = 0.7f) else cNet
                val colNetDns = if (cDns == borderIdle) mutedColor.copy(alpha = 0.7f) else cDns
                val colNetTcp = if (cTcp == borderIdle) mutedColor.copy(alpha = 0.7f) else cTcp
                val colTcpTls = if (cTls == borderIdle) mutedColor.copy(alpha = 0.7f) else cTls
                val colTlsHttp = if (cHttp == borderIdle) mutedColor.copy(alpha = 0.7f) else cHttp

                fun drawLinkDelta(xMid: Float, yLine: Float, text: String?, color: Color) {
                    if (text.isNullOrBlank()) return
                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        textSize = 7.0.sp.toPx()
                        this.color = color.toArgb()
                        textAlign = android.graphics.Paint.Align.CENTER
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    }
                    drawContext.canvas.nativeCanvas.drawText(text, xMid, yLine - 7f, paint)
                }

                // 1. Телефон ---> Вышка/Роутер (+пинг до роутера/вышки)
                drawArrowLine(Offset(xPhoneEnd, yMidLeft), Offset(xNetStart, yMidLeft), colPhoneNet)
                drawLinkDelta((xPhoneEnd + xNetStart) / 2f, yMidLeft, netMsVal?.let { "+${it}" }, colPhoneNet)

                // 2. Вышка/Роутер ---> DNS (верхняя ветка)
                val dnsPath = Path().apply {
                    moveTo(xNetEnd, h * 0.34f)
                    cubicTo(
                        xNetEnd + (xRightStart - xNetEnd) * 0.55f, h * 0.34f,
                        xNetEnd + (xRightStart - xNetEnd) * 0.45f, yDns,
                        xRightStart - 3f, yDns,
                    )
                }
                drawPath(dnsPath, color = colNetDns, style = Stroke(width = 3.2f, cap = StrokeCap.Round))
                drawArrowHead(Offset(xRightStart, yDns), colNetDns)

                // 3. Вышка/Роутер ---> Связь (TCP)
                drawArrowLine(Offset(xNetEnd, yChain), Offset(xRightStart, yChain), colNetTcp)
                drawLinkDelta((xNetEnd + xRightStart) / 2f, yChain, tcpDelta?.let { "+${it}" }, colNetTcp)

                // 4. Связь ---> Защита (TLS)
                val gapPx = 17.dp.toPx()
                val rightW = w - xRightStart
                val miniW = (rightW - gapPx * 2f) / 3f
                val xTcpEnd = xRightStart + miniW
                val xTlsStart = xTcpEnd + gapPx
                val xTlsEnd = xTlsStart + miniW
                val xHttpStart = xTlsEnd + gapPx

                drawArrowLine(Offset(xTcpEnd, yChain), Offset(xTlsStart, yChain), colTcpTls)
                drawLinkDelta((xTcpEnd + xTlsStart) / 2f, yChain, tlsDelta?.let { "+${it}" }, colTcpTls)

                // 5. Защита ---> Сервер (HTTP)
                drawArrowLine(
                    Offset(xTlsEnd, yChain),
                    Offset(xHttpStart, yChain),
                    colTlsHttp,
                    dashed = cHttp == failColor,
                )
                drawLinkDelta((xTlsEnd + xHttpStart) / 2f, yChain, httpDelta?.let { "+${it}" }, colTlsHttp)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 1. Телефон (19.5%)
                DeviceNodeCard(
                    emoji = "📱",
                    title = tr("Телефон", "Phone"),
                    line1 = phoneIpShort,
                    line2 = phoneSub,
                    line3 = if (probeMap[StageId.ENVIRONMENT] != null) "0мс · OK" else "—",
                    accent = cPhone,
                    selected = selectedStage == StageId.ENVIRONMENT,
                    onClick = { onSelectStage(StageId.ENVIRONMENT) },
                    modifier = Modifier
                        .weight(0.195f)
                        .fillMaxHeight(),
                )

                Spacer(Modifier.weight(0.04f))

                // 2. Вышка / Роутер (20.5%)
                DeviceNodeCard(
                    emoji = netEmoji,
                    title = netTitle,
                    line1 = netLine1,
                    line2 = netLine2,
                    line3 = netTotalText,
                    accent = cNet,
                    selected = selectedStage == StageId.LOCAL_LINK,
                    onClick = { onSelectStage(StageId.LOCAL_LINK) },
                    modifier = Modifier
                        .weight(0.205f)
                        .fillMaxHeight(),
                )

                Spacer(Modifier.weight(0.04f))

                // 3. Правая колонка: DNS + Связь -> Защита -> Сервер (52%)
                Column(
                    modifier = Modifier.weight(0.52f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    // Ветка 1: DNS
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(
                            width = if (selectedStage == StageId.DNS) 1.6.dp else 1.dp,
                            color = cDns,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .clickable { onSelectStage(StageId.DNS) },
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "📖 DNS $dnsMs",
                                    fontSize = 9.5.sp,
                                    lineHeight = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (cDns == borderIdle) MaterialTheme.colorScheme.onSurface else cDns,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (dnsStatusLabel.isNotBlank()) {
                                    Text(
                                        text = dnsStatusLabel,
                                        fontSize = 8.5.sp,
                                        lineHeight = 10.sp,
                                        color = cDns,
                                        maxLines = 1,
                                    )
                                }
                            }
                            Text(
                                text = dnsIp,
                                fontSize = 9.sp,
                                lineHeight = 11.sp,
                                letterSpacing = (-0.2).sp,
                                color = mutedColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // Ветка 2: Связь (TCP) -> Защита (TLS) -> Сервер (HTTP) (зазор 17dp для подписи дельты на стрелке)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(17.dp),
                    ) {
                        MiniStageCard(
                            emoji = "🔌",
                            title = tr("Связь", "Link"),
                            delta = null,
                            total = tcpVal,
                            sub = "TCP",
                            accent = cTcp,
                            selected = selectedStage == StageId.TCP,
                            onClick = { onSelectStage(StageId.TCP) },
                            modifier = Modifier.weight(1f),
                        )
                        MiniStageCard(
                            emoji = "🔒",
                            title = tr("Защита", "TLS"),
                            delta = null,
                            total = tlsVal,
                            sub = "TLS1.3",
                            accent = cTls,
                            selected = selectedStage == StageId.TLS_SNI,
                            onClick = { onSelectStage(StageId.TLS_SNI) },
                            modifier = Modifier.weight(1f),
                        )
                        MiniStageCard(
                            emoji = "🌐",
                            title = tr("Сервер", "Server"),
                            delta = null,
                            total = httpVal,
                            sub = httpSub,
                            accent = cHttp,
                            selected = selectedStage == StageId.HTTP_PAYLOAD,
                            highlightFail = cHttp == failColor,
                            onClick = { onSelectStage(StageId.HTTP_PAYLOAD) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // Явная видимая вертикальная ветка от Вышки/Роутера (x = 36.7%) вниз в Звонки / Игры (UDP)
        if (showUdp) {
            val udpLinkCol = if (cUdp == borderIdle) mutedColor.copy(alpha = 0.7f) else cUdp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val x = size.width * 0.367f
                    drawLine(
                        color = udpLinkCol,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height - 3f),
                        strokeWidth = 3.5f,
                        pathEffect = if (cUdp != okColor) PathEffect.dashPathEffect(floatArrayOf(5f, 3f)) else null,
                    )
                    // Стрелка вниз
                    val tipY = size.height
                    val head = Path().apply {
                        moveTo(x - 5.5f, tipY - 6.5f)
                        lineTo(x + 5.5f, tipY - 6.5f)
                        lineTo(x, tipY)
                        close()
                    }
                    drawPath(head, color = udpLinkCol)
                }
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(0.39f))
                    val udpDeltaSuffix = udpProbe?.takeIf { it.status == ProbeStatus.SUCCESS }?.let { " · +${it.durationMs}мс" }.orEmpty()
                    Text(
                        text = "ветка UDP от ${if (isWifi) "роутера" else "вышки"}$udpDeltaSuffix",
                        fontSize = 8.5.sp,
                        lineHeight = 10.sp,
                        color = udpLinkCol,
                        modifier = Modifier.weight(0.61f),
                        maxLines = 1,
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(7.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(
                    width = if (selectedStage == StageId.UDP_STUN) 1.6.dp else 1.dp,
                    color = cUdp,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(7.dp))
                    .clickable { onSelectStage(StageId.UDP_STUN) },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "📞 Звонки / Игры (UDP / STUN)",
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (cUdp == borderIdle) MaterialTheme.colorScheme.onSurface else cUdp,
                            maxLines = 1,
                        )
                        Text(
                            text = udpVal,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (cUdp == borderIdle) mutedColor else cUdp,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = udpSub,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        color = mutedColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawArrowLine(
    start: Offset,
    end: Offset,
    color: Color,
    dashed: Boolean = false,
) {
    drawLine(
        color = color,
        start = start,
        end = Offset(end.x - 4f, end.y),
        strokeWidth = 3.2f,
        cap = StrokeCap.Round,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5f, 3f)) else null,
    )
    drawArrowHead(end, color)
}

private fun DrawScope.drawArrowHead(tip: Offset, color: Color) {
    val head = Path().apply {
        moveTo(tip.x - 6.5f, tip.y - 4.5f)
        lineTo(tip.x - 6.5f, tip.y + 4.5f)
        lineTo(tip.x, tip.y)
        close()
    }
    drawPath(head, color = color)
}

@Composable
private fun DeviceNodeCard(
    emoji: String,
    title: String,
    line1: String,
    line2: String,
    line3: String,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (accent == WhyNetColors.Border) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        accent.copy(alpha = 0.09f)
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(if (selected) 1.6.dp else 1.dp, accent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Text(text = emoji, fontSize = 13.sp, lineHeight = 14.sp)
        Text(
            text = title,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = line1,
            fontSize = 8.0.sp,
            lineHeight = 10.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = line2,
            fontSize = 8.4.sp,
            lineHeight = 10.5.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = line3,
            fontSize = 9.5.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (accent == WhyNetColors.Border) MaterialTheme.colorScheme.onSurfaceVariant else accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MiniStageCard(
    emoji: String,
    title: String,
    delta: String?,
    total: String,
    sub: String,
    accent: Color,
    selected: Boolean,
    highlightFail: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (highlightFail) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(if (selected || highlightFail) 1.4.dp else 1.dp, accent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Text(text = emoji, fontSize = 11.sp, lineHeight = 12.sp)
        Text(
            text = title,
            fontSize = 8.8.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (highlightFail) accent else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = total,
            fontSize = 9.5.sp,
            lineHeight = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = if (accent == WhyNetColors.Border) MaterialTheme.colorScheme.onSurfaceVariant else accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (delta != null) "$delta · $sub" else sub,
            fontSize = 7.8.sp,
            lineHeight = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
