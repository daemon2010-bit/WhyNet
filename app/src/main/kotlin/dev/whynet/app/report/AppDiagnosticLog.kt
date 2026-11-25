package dev.whynet.app.report

import android.os.Process
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Потокобезопасный кольцевой журнал работы приложения WhyNet (только в памяти, без записи PII).
 */
object AppDiagnosticLog {
    private const val MAX_ENTRIES = 250
    private val lock = Any()
    private val entries = ArrayDeque<String>(MAX_ENTRIES)
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val secretRegex = Regex(
        pattern = """(?i)(password|passwd|token|secret|cookie|authorization|bearer|apikey|api_key)\s*[:=]\s*\S+""",
    )

    fun i(tag: String, message: String) = append("I", tag, message)

    fun w(tag: String, message: String) = append("W", tag, message)

    fun e(tag: String, message: String, error: Throwable? = null) {
        val tail = if (error != null) " (${error.javaClass.simpleName}: ${error.message})" else ""
        append("E", tag, message + tail)
    }

    private fun append(level: String, tag: String, message: String) {
        val clean = sanitizeLine(message)
        synchronized(lock) {
            val ts = timeFmt.format(Date())
            if (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
            entries.addLast("$ts $level/$tag: $clean")
        }
    }

    fun snapshotLines(): List<String> = synchronized(lock) { entries.toList() }

    private val noisyLogcatTags = listOf(
        "DynamicFramerate",
        "IJankManager",
        "OplusScrollToTopManager",
        "OplusViewDragTouchViewHelper",
        "OplusBracketLog",
        "ViewRootImplExtImpl",
        "BLASTBufferQueue",
        "BufferQueue",
        "SurfaceControl",
        "HWUI",
        "ImeTracker",
        "QualityInfo",
        "VRI[",
        "AssetManager2",
    )

    /**
     * Безопасно забирает последние [maxLines] полезных строк logcat только собственного процесса WhyNet,
     * отсеивая шум отрисовки (ColorOS/HWUI/BLASTBufferQueue).
     */
    fun readOwnProcessLogcat(maxLines: Int = 60): List<String> {
        val pid = Process.myPid()
        return runCatching {
            val proc = ProcessBuilder(
                "logcat",
                "-d",
                "-v",
                "time",
                "-t",
                "250",
                "--pid=$pid",
            ).redirectErrorStream(true).start()

            val lines = mutableListOf<String>()
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    if (noisyLogcatTags.any { line.contains(it) }) continue
                    lines += sanitizeLine(line)
                }
            }
            proc.waitFor()
            if (lines.isEmpty()) listOf("(ошибок и системных предупреждений процесса не зафиксировано)")
            else lines.takeLast(maxLines)
        }.getOrElse { err ->
            listOf("(logcat собственного процесса недоступен: ${err.javaClass.simpleName})")
        }
    }

    private fun sanitizeLine(raw: String): String =
        secretRegex.replace(raw) { match ->
            val key = match.groupValues.getOrNull(1).orEmpty()
            "$key=***"
        }.take(320)
}
