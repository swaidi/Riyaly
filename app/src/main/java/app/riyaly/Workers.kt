package app.riyaly

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

object Workers {
    fun schedule(ctx: Context) = AppLog.safe("schedule workers", Unit) {
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniquePeriodicWork(
            "daily", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DailyWorker>(1, TimeUnit.DAYS).setInitialDelay(delayTo(Calendar.getInstance(), 9, null), TimeUnit.MILLISECONDS).build(),
        )
        wm.enqueueUniquePeriodicWork(
            "weekly", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WeeklyWorker>(7, TimeUnit.DAYS).setInitialDelay(delayTo(Calendar.getInstance(), 20, Calendar.SUNDAY), TimeUnit.MILLISECONDS).build(),
        )
    }

    /** Milliseconds until the next [hour]:00 (on [weekday] when given). */
    fun delayTo(now: Calendar, hour: Int, weekday: Int?): Long {
        val t = (now.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (weekday != null) while (t.get(Calendar.DAY_OF_WEEK) != weekday || !t.after(now)) t.add(Calendar.DAY_OF_MONTH, 1)
        else if (!t.after(now)) t.add(Calendar.DAY_OF_MONTH, 1)
        return t.timeInMillis - now.timeInMillis
    }
}

class DailyWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        AppLog.init(applicationContext)
        AppLog.i("daily check")
        AppLog.safe("daily", Unit) { Plan.daily(applicationContext) }
        return Result.success()
    }
}

class WeeklyWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        AppLog.init(applicationContext)
        AppLog.safe("weekly", Unit) { Plan.weekly(applicationContext) }
        return Result.success()
    }
}

/** Repeats a High alert that is still unanswered after 15 minutes. */
class ReminderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        AppLog.init(applicationContext)
        val id = inputData.getLong("txn", -1)
        AppLog.safe("reminder", Unit) {
            val t = Db.get(applicationContext).txn(id) ?: return@safe
            if (t.alert == AlertState.OPEN && t.risk == Risk.HIGH) Notifier.notify(applicationContext, t, reminder = true)
        }
        return Result.success()
    }
}
