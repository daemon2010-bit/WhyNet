package dev.whynet.app.report

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.core.content.FileProvider
import dev.whynet.core.engine.HeatmapFloorPlan
import dev.whynet.core.engine.HeatmapLayer
import dev.whynet.core.engine.WifiHeatmapEngine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Хранение планов помещений и экспорт готовой Wi-Fi тепловой карты в PNG.
 */
object HeatmapExporter {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun heatmapsDir(context: Context): File =
        File(context.filesDir, "heatmaps").apply { mkdirs() }

    private fun planFile(context: Context): File =
        File(heatmapsDir(context), "current_plan.json")

    fun savePlan(context: Context, plan: HeatmapFloorPlan) {
        runCatching {
            planFile(context).writeText(json.encodeToString(plan), Charsets.UTF_8)
        }
    }

    fun loadPlan(context: Context): HeatmapFloorPlan {
        val f = planFile(context)
        if (!f.exists()) {
            return HeatmapFloorPlan(
                name = "2-комн. (шаблон)",
                walls = WifiHeatmapEngine.presetWalls("two_room"),
            )
        }
        return runCatching {
            json.decodeFromString<HeatmapFloorPlan>(f.readText(Charsets.UTF_8))
        }.getOrElse {
            HeatmapFloorPlan(
                name = "2-комн. (шаблон)",
                walls = WifiHeatmapEngine.presetWalls("two_room"),
            )
        }
    }

    /**
     * Копирует и сжимает выбранную пользователем картинку плана в локальное хранилище (до 1280px).
     */
    fun importFloorImage(context: Context, uri: Uri): String? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val maxSide = maxOf(opts.outWidth, opts.outHeight).coerceAtLeast(1)
        var sample = 1
        while (maxSide / sample > 1280) sample *= 2

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null

        val outFile = File(heatmapsDir(context), "floor_bg_${System.currentTimeMillis()}.jpg")
        // Удаляем старые фоны
        heatmapsDir(context).listFiles { f -> f.name.startsWith("floor_bg_") }?.forEach { runCatching { it.delete() } }
        FileOutputStream(outFile).use { fos ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 86, fos)
        }
        bmp.recycle()
        outFile.absolutePath
    }.getOrNull()

    fun exportAndSharePng(
        context: Context,
        plan: HeatmapFloorPlan,
        layer: HeatmapLayer,
    ): String {
        val w = 1200
        val h = 960
        val mapTop = 88
        val mapBottom = 880
        val mapLeft = 40
        val mapRight = 1160
        val mapW = mapRight - mapLeft
        val mapH = mapBottom - mapTop

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        // Заголовок
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#111827")
            textSize = 34f
            isFakeBoldText = true
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4B5563")
            textSize = 22f
        }
        canvas.drawText("WhyNet Wi-Fi Heatmap · ${plan.name} (${layer.title})", 40f, 44f, titlePaint)
        canvas.drawText("Точек замера: ${plan.points.size} · ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())}", 40f, 74f, subPaint)

        // Фон карты (фото или сетка)
        val bgRect = Rect(mapLeft, mapTop, mapRight, mapBottom)
        val bgPath = plan.backgroundImagePath
        val bgBmp = if (!bgPath.isNullOrBlank() && File(bgPath).exists()) {
            runCatching { BitmapFactory.decodeFile(bgPath) }.getOrNull()
        } else {
            null
        }
        if (bgBmp != null) {
            canvas.drawBitmap(bgBmp, null, bgRect, null)
            bgBmp.recycle()
        } else {
            val fillPaint = Paint().apply { color = Color.parseColor("#F8FAFC") }
            canvas.drawRect(bgRect, fillPaint)
            val gridPaint = Paint().apply {
                color = Color.parseColor("#E2E8F0")
                strokeWidth = 2f
            }
            for (i in 1..11) {
                val x = mapLeft + mapW * (i / 12f)
                canvas.drawLine(x, mapTop.toFloat(), x, mapBottom.toFloat(), gridPaint)
            }
            for (j in 1..8) {
                val y = mapTop + mapH * (j / 9f)
                canvas.drawLine(mapLeft.toFloat(), y, mapRight.toFloat(), y, gridPaint)
            }
        }

        // Тепловая сетка IDW (44x33 ячеек)
        if (plan.points.isNotEmpty()) {
            val samples = when (layer) {
                HeatmapLayer.SIGNAL_RSSI -> plan.points.mapNotNull { p ->
                    p.connectedRssiDbm?.let { Triple(p.x, p.y, it.toDouble()) }
                }
                HeatmapLayer.BEST_NETWORK -> plan.points.mapNotNull { p ->
                    (p.bestOverallRssiDbm ?: p.connectedRssiDbm)?.let { Triple(p.x, p.y, it.toDouble()) }
                }
                HeatmapLayer.GATEWAY_PING -> plan.points.mapNotNull { p ->
                    p.gatewayRttMs?.let { Triple(p.x, p.y, it) }
                }
            }
            val cols = 44
            val rows = 33
            val cellW = mapW.toFloat() / cols
            val cellH = mapH.toFloat() / rows
            val cellPaint = Paint()
            for (r in 0 until rows) {
                val ny = (r + 0.5f) / rows
                for (c in 0 until cols) {
                    val nx = (c + 0.5f) / cols
                    val valInterp = WifiHeatmapEngine.interpolateIdw(nx, ny, samples) ?: continue
                    val q = when (layer) {
                        HeatmapLayer.GATEWAY_PING -> WifiHeatmapEngine.pingToQuality(valInterp)
                        else -> WifiHeatmapEngine.rssiToQuality(valInterp)
                    }
                    cellPaint.color = qualityToHeatColorArgb(q, alpha = 135)
                    val cx = mapLeft + c * cellW
                    val cy = mapTop + r * cellH
                    canvas.drawRect(cx, cy, cx + cellW + 1f, cy + cellH + 1f, cellPaint)
                }
            }
        }

        // Стены
        val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
        }
        plan.walls.forEach { wall ->
            canvas.drawLine(
                mapLeft + wall.x1 * mapW,
                mapTop + wall.y1 * mapH,
                mapLeft + wall.x2 * mapW,
                mapTop + wall.y2 * mapH,
                wallPaint,
            )
        }

        // Точки замеров
        val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0F172A") }
        val dotBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 20f
            isFakeBoldText = true
        }
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(225, 255, 255, 255)
        }
        plan.points.forEach { pt ->
            val px = mapLeft + pt.x * mapW
            val py = mapTop + pt.y * mapH
            canvas.drawCircle(px, py, 12f, dotFill)
            canvas.drawCircle(px, py, 12f, dotBorder)
            val txt = when (layer) {
                HeatmapLayer.SIGNAL_RSSI -> "${pt.label}: ${pt.connectedRssiDbm ?: "-"} дБм"
                HeatmapLayer.BEST_NETWORK -> "${pt.label}: ${(pt.bestOverallSsid ?: pt.connectedSsid).take(12)} (${pt.bestOverallRssiDbm ?: pt.connectedRssiDbm ?: "-"})"
                HeatmapLayer.GATEWAY_PING -> "${pt.label}: ${pt.gatewayRttMs?.let { "%.0f мс".format(it) } ?: "—"}"
            }
            val tw = labelPaint.measureText(txt)
            val rx = (px - tw / 2f).coerceIn(mapLeft + 6f, mapRight - tw - 12f)
            val ry = (py - 20f).coerceAtLeast(mapTop + 26f)
            canvas.drawRoundRect(rx - 6f, ry - 20f, rx + tw + 6f, ry + 6f, 8f, 8f, pillPaint)
            canvas.drawText(txt, rx, ry, labelPaint)
        }

        // Предполагаемые точки роутеров (AP Locator) на PNG
        val routers = WifiHeatmapEngine.estimateRouterLocations(plan, minPoints = 2, maxRouters = 6)
        val apFillSelf = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#16A34A") }
        val apFillOther = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2563EB") }
        val apBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        routers.forEach { ap ->
            val ax = mapLeft + ap.x * mapW
            val ay = mapTop + ap.y * mapH
            val r = if (ap.isConnected) 18f else 14f
            val path = android.graphics.Path().apply {
                moveTo(ax, ay - r)
                lineTo(ax + r, ay)
                lineTo(ax, ay + r)
                lineTo(ax - r, ay)
                close()
            }
            canvas.drawPath(path, if (ap.isConnected) apFillSelf else apFillOther)
            canvas.drawPath(path, apBorder)
            val apTxt = "${if (ap.isConnected) "★ " else "AP: "}${ap.ssid.take(14)} (к.${ap.channel})"
            val tw = labelPaint.measureText(apTxt)
            val rx = (ax - tw / 2f).coerceIn(mapLeft + 6f, mapRight - tw - 12f)
            val ry = (ay + 34f).coerceAtMost(mapBottom - 10f)
            canvas.drawRoundRect(rx - 6f, ry - 20f, rx + tw + 6f, ry + 6f, 8f, 8f, pillPaint)
            canvas.drawText(apTxt, rx, ry, labelPaint)
        }

        // Легенда снизу
        val legendY = 925f
        canvas.drawText("Шкала: Зелёный = Отлично (-50 дБм / <8 мс) · Жёлтый = Норма (-67 дБм) · Красный = Слабо (<-78 дБм)", 40f, legendY, subPaint)

        val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(reportsDir, "whynet_heatmap_$stamp.png")
        FileOutputStream(outFile).use { fos ->
            bmp.compress(Bitmap.CompressFormat.PNG, 95, fos)
        }
        bmp.recycle()

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, outFile.name)
            putExtra(Intent.EXTRA_TEXT, "Wi-Fi карта помещения WhyNet (${plan.name}, ${plan.points.size} точек)")
            clipData = ClipData.newRawUri(outFile.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Поделиться картой Wi-Fi (.png)").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        return outFile.name
    }

    fun qualityToHeatColorArgb(q: Float, alpha: Int = 145): Int {
        val clamped = q.coerceIn(0f, 1f)
        val r: Int
        val g: Int
        val b: Int
        if (clamped < 0.5f) {
            // Красный (0.0) -> Жёлтый (0.5)
            val t = clamped * 2f
            r = 225
            g = (55 + 165 * t).toInt()
            b = 45
        } else {
            // Жёлтый (0.5) -> Зелёный (1.0)
            val t = (clamped - 0.5f) * 2f
            r = (225 - 185 * t).toInt()
            g = (220 - 35 * t).toInt()
            b = (45 + 45 * t).toInt()
        }
        return Color.argb(alpha, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }
}
