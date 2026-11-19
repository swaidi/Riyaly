package app.riyaly

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.text.NumberFormat
import java.util.Locale

object Fmt {
    fun money(amount: Double, currency: String): String {
        val nf = NumberFormat.getNumberInstance(Locale.US).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
        val cur = if (currency == "SAR") "SR" else currency
        return "$cur ${nf.format(amount)}"
    }

    fun whole(amount: Double): String = NumberFormat.getIntegerInstance(Locale.US).format(Math.round(amount))
}

object Notifier {
    private const val CH_TXN = "txn"
    private const val CH_ALERT = "alert"
    const val EXTRA_TXN = "txn_id"
    const val EXTRA_ACTION = "action"
    const val ACTION_OK = "app.riyaly.OK"
    const val ACTION_CHECK = "check"
    const val ACTION_OPEN = "open"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_TXN, ctx.getString(R.string.ch_txn), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, ctx.getString(R.string.ch_alert), NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
        })
    }

    /** The person only, e.g. "Family Member A". */
    fun person(ctx: Context, t: Txn): String = who(ctx, t, withCard = false)

    /** The person and, when it has a name, the card or account: "Me · Salary account (5678)". */
    fun who(ctx: Context, t: Txn, withCard: Boolean = true): String {
        val db = Db.get(ctx)
        return when (t.match) {
            Match.IDENTIFIED -> {
                val name = db.member(t.memberId)?.name ?: ctx.getString(R.string.unidentified)
                val label = if (withCard) db.card(t.card)?.label else null
                if (label.isNullOrBlank()) name else "$name · $label (${t.card})"
            }
            Match.AMBIGUOUS -> ctx.getString(R.string.check_who, t.candidates.mapNotNull { db.member(it)?.name }.joinToString(" / "))
            else -> if (t.card != null) ctx.getString(R.string.unidentified_card, t.card) else ctx.getString(R.string.unidentified)
        }
    }

    fun summary(ctx: Context, t: Txn): String {
        val sign = if (t.isCredit) "+" else ""
        val tail = when (t.kind) {
            Parser.Kind.TRANSFER_IN -> t.counterparty?.let { " · " + ctx.getString(R.string.from_party, it) } ?: ""
            Parser.Kind.TRANSFER_OUT -> t.counterparty?.let { " · " + ctx.getString(R.string.to_party, it) } ?: ""
            Parser.Kind.WITHDRAWAL -> " · " + ctx.getString(R.string.k_withdrawal) + (t.merchant?.let { " – $it" } ?: "")
            else -> t.merchant?.let { " " + ctx.getString(R.string.at_merchant, it) } ?: ""
        }
        val declined = if (t.isDeclined) ctx.getString(R.string.declined) + ": " else ""
        return "$declined${who(ctx, t)}: $sign${Fmt.money(t.amount, t.currency)}$tail"
    }

    private fun openIntent(ctx: Context, t: Txn, action: String, req: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            putExtra(EXTRA_TXN, t.id); putExtra(EXTRA_ACTION, action)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun notify(ctx: Context, t: Txn) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        channels(ctx)
        val alert = t.alert == AlertState.OPEN || t.match == Match.AMBIGUOUS
        if (!alert && !Prefs(ctx).notifyEvery) return
        val id = t.id.toInt()
        val b = NotificationCompat.Builder(ctx, if (alert) CH_ALERT else CH_TXN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(summary(ctx, t))
            .setContentIntent(openIntent(ctx, t, ACTION_OPEN, id * 3))
            .setAutoCancel(true)
            .setGroup(if (alert) "alerts" else "txns")
        if (alert) {
            val reasons = (t.reasons.map { Analyzer.label(ctx, it) } +
                listOfNotNull(t.declineReason?.let { ctx.getString(R.string.decline_reason, it) }, t.country?.let { ctx.getString(R.string.country_is, it) }))
                .distinct().joinToString(" • ")
            b.setContentText(ctx.getString(R.string.alert_prefix) + " " + reasons)
                .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(R.string.alert_prefix) + " " + reasons + "\n\n" + t.body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
            val ok = PendingIntent.getBroadcast(
                ctx, id * 3 + 1,
                Intent(ctx, ActionReceiver::class.java).setAction(ACTION_OK).putExtra(EXTRA_TXN, t.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(0, ctx.getString(R.string.btn_confirmed_ok), ok)
            b.addAction(0, ctx.getString(R.string.btn_check_member), openIntent(ctx, t, ACTION_CHECK, id * 3 + 2))
        } else {
            b.setContentText(listOfNotNull(Analyzer.kindLabel(ctx, t.kind), t.method, t.balance?.let { ctx.getString(R.string.balance_is, Fmt.money(it, "SAR")) }).joinToString(" • "))
        }
        NotificationManagerCompat.from(ctx).notify(id, b.build())
    }

    /** A later finding (e.g. merchant is far from home) for an already stored transaction. */
    fun renotify(ctx: Context, t: Txn) = notify(ctx, t)

    fun cancel(ctx: Context, id: Long) = NotificationManagerCompat.from(ctx).cancel(id.toInt())

    fun simple(ctx: Context, id: Int, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        channels(ctx)
        val b = NotificationCompat.Builder(ctx, CH_TXN).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(text).setAutoCancel(true)
        NotificationManagerCompat.from(ctx).notify(id, b.build())
    }
}

/** Marks a transaction as normal and teaches the app that its merchant is fine. */
fun confirmOk(ctx: Context, t: Txn) {
    val db = Db.get(ctx)
    db.updateTxn(t.copy(alert = AlertState.OK))
    t.merchant?.let { db.setMerchantTrusted(it, true) }
    Notifier.cancel(ctx, t.id)
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Notifier.ACTION_OK) return
        val id = intent.getLongExtra(Notifier.EXTRA_TXN, -1)
        val pending = goAsync()
        Thread {
            try { Db.get(ctx).txn(id)?.let { confirmOk(ctx, it) } } finally { pending.finish() }
        }.start()
    }
}
