package app.riyaly

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Travel mode ends by itself the day after the end date, with a notification. */
object Travel {
    private fun workName(last4: String) = "travel_end_$last4"

    fun schedule(ctx: Context, card: Card) {
        val wm = WorkManager.getInstance(ctx)
        val end = card.travelTo
        if (end == null) { wm.cancelUniqueWork(workName(card.last4)); return }
        val endsAt = end + 24L * 3600 * 1000
        val delay = (endsAt - System.currentTimeMillis()).coerceAtLeast(0)
        val req = OneTimeWorkRequestBuilder<TravelEndWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("card" to card.last4, "to" to end))
            .build()
        wm.enqueueUniqueWork(workName(card.last4), ExistingWorkPolicy.REPLACE, req)
    }

    /** Date pickers give midnight UTC; convert to the start of that day in the phone's time zone. */
    fun utcDayToLocal(utcMillis: Long): Long {
        val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
        return Calendar.getInstance().apply {
            clear(); set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    fun localDayToUtc(localMillis: Long): Long {
        val l = Calendar.getInstance().apply { timeInMillis = localMillis }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(l.get(Calendar.YEAR), l.get(Calendar.MONTH), l.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }
}

class TravelEndWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val db = Db.get(ctx)
        val card = db.card(inputData.getString("card")) ?: return Result.success()
        if (card.travelTo != inputData.getLong("to", -1)) return Result.success() // dates were changed since
        db.saveCard(card.copy(travelFrom = null, travelTo = null, travelDest = null))
        val who = db.member(card.memberId)?.name ?: ""
        Notifier.simple(ctx, 900_000 + (card.last4.toIntOrNull() ?: 0), ctx.getString(R.string.travel_ended_title),
            ctx.getString(R.string.travel_ended_body, card.display(), who))
        return Result.success()
    }
}
