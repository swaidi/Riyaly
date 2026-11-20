package app.riyaly

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The issue log: a small text file on the phone that records what the app did and any errors,
 * so a problem can be traced. It never stores message text, amounts or card digits: messages are
 * logged only by their shape (numbers replaced by #).
 */
object AppLog {
    private const val FILE = "riyaly-log.txt"
    private const val MAX_BYTES = 400_000L
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    @Volatile private var dir: File? = null

    fun init(ctx: Context) {
        dir = ctx.applicationContext.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous !is CrashHandler) Thread.setDefaultUncaughtExceptionHandler(CrashHandler(previous))
    }

    fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, FILE)

    @Synchronized
    private fun write(level: String, msg: String) {
        val d = dir ?: return
        try {
            val f = File(d, FILE)
            if (f.length() > MAX_BYTES) {
                val keep = f.readText().takeLast((MAX_BYTES / 2).toInt())
                f.writeText(keep)
            }
            f.appendText("${fmt.format(Date())} $level $msg\n")
        } catch (_: Exception) {}
    }

    fun i(msg: String) = write("I", msg)
    fun w(msg: String) = write("W", msg)
    fun e(msg: String, t: Throwable? = null) = write("E", msg + (t?.let { "\n" + stack(it) } ?: ""))

    /** A message's shape for the log: numbers become #, so no amounts or card digits are kept. */
    fun shape(body: String): String = Parser.normalize(body).replace(Regex("\\d+([.,]\\d+)*"), "#").replace(Regex("\\s+"), " ").take(160)

    fun read(ctx: Context): String = try { file(ctx).takeIf { it.exists() }?.readText() ?: "" } catch (_: Exception) { "" }

    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Exception) {} }

    private fun stack(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString().take(4000)

    private class CrashHandler(private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            write("CRASH", "in ${t.name}: ${e.javaClass.simpleName}: ${e.message}\n" + stack(e))
            previous?.uncaughtException(t, e)
        }
    }

    /** Runs [block]; a failure is logged and swallowed, so one broken feature never stops the app. */
    inline fun <T> safe(what: String, default: T, block: () -> T): T = try { block() } catch (ex: Exception) { e("$what failed", ex); default }
}
