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
    const val ACTION_REPAID = "app.riyaly.REPAID"
    const val EXTRA_LOAN = "loan_id"
    const val EXTRA_AMOUNT = "amount"
    private const val CH_INFO = "info"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_TXN, ctx.getString(R.string.ch_txn), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, ctx.getString(R.string.ch_alert), NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_INFO, ctx.getString(R.string.ch_info), NotificationManager.IMPORTANCE_LOW))
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

    /** Dial intent for the bank of [t], when its phone number is saved. */
    fun callBankIntent(ctx: Context, t: Txn): Intent? {
        val phone = Db.get(ctx).bank(t.bankId)?.phone?.takeIf { it.isNotBlank() } ?: return null
        return Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' }))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun allowed(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * High: loud alert with Confirmed OK / Check / Call the bank, repeated once after 15 minutes if unanswered.
     * Medium: a quiet notification; it waits in the Alerts list and the weekly review.
     * Low or no finding: the normal purchase notification (when every purchase is notified).
     */
    fun notify(ctx: Context, t: Txn, reminder: Boolean = false) {
        if (!allowed(ctx)) return
        channels(ctx)
        val open = t.alert == AlertState.OPEN || t.match == Match.AMBIGUOUS
        val high = open && (t.risk == Risk.HIGH || t.match == Match.AMBIGUOUS || t.risk == null)
        val medium = open && !high
        if (!open && !Prefs(ctx).notifyEvery) return
        val id = t.id.toInt()
        val title = (if (reminder) ctx.getString(R.string.reminder_prefix) + " " else "") + summary(ctx, t)
        val b = NotificationCompat.Builder(ctx, if (high) CH_ALERT else CH_TXN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentIntent(openIntent(ctx, t, ACTION_OPEN, id * 4))
            .setAutoCancel(true)
            .setGroup(if (open) "alerts" else "txns")
        val safe = if (t.isSpend && !t.isDeclined) AppLog.safe("safe line", null) { Plan.safeLine(ctx) } else null
        if (open) {
            val level = ctx.getString(if (high) R.string.risk_high else R.string.risk_medium)
            val reasons = (t.reasons.map { Analyzer.label(ctx, it) } +
                listOfNotNull(t.declineReason?.let { ctx.getString(R.string.decline_reason, it) }, t.country?.let { ctx.getString(R.string.country_is, it) }))
                .distinct().joinToString(" • ")
            val big = listOfNotNull("$level: $reasons", t.explain, safe, "", t.body).joinToString("\n")
            b.setContentText("$level: $reasons")
                .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            if (high) {
                b.setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ALARM)
                val ok = PendingIntent.getBroadcast(
                    ctx, id * 4 + 1,
                    Intent(ctx, ActionReceiver::class.java).setAction(ACTION_OK).putExtra(EXTRA_TXN, t.id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                b.addAction(0, ctx.getString(R.string.btn_ok_this_time), ok)
                b.addAction(0, ctx.getString(R.string.btn_check_member), openIntent(ctx, t, ACTION_CHECK, id * 4 + 2))
                callBankIntent(ctx, t)?.let {
                    b.addAction(0, ctx.getString(R.string.btn_call_bank), PendingIntent.getActivity(ctx, id * 4 + 3, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
            } else {
                b.setPriority(NotificationCompat.PRIORITY_LOW).setSilent(true)
            }
        } else {
            val line = listOfNotNull(Analyzer.kindLabel(ctx, t.kind), t.method, t.balance?.let { ctx.getString(R.string.balance_is, Fmt.money(it, "SAR")) }).joinToString(" • ")
            b.setContentText(line)
            if (safe != null) b.setStyle(NotificationCompat.BigTextStyle().bigText(line + "\n" + safe))
        }
        AppLog.safe("post notification", Unit) { NotificationManagerCompat.from(ctx).notify(id, b.build()) }
        if (medium) AppLog.i("medium alert kept quiet: ${t.reasons.joinToString(",")}")
    }

    /** A later finding (e.g. merchant is far from home) for an already stored transaction. */
    fun renotify(ctx: Context, t: Txn) = notify(ctx, t)

    fun cancel(ctx: Context, id: Long) = NotificationManagerCompat.from(ctx).cancel(id.toInt())

    fun simple(ctx: Context, id: Int, title: String, text: String) = info(ctx, id, title, text)

    /** An information notification (summaries, budgets, reminders). */
    fun info(ctx: Context, id: Int, title: String, text: String, callBankFor: Txn? = null) {
        if (!allowed(ctx)) return
        channels(ctx)
        val open = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(ctx, CH_INFO).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true)
        callBankFor?.let { t -> callBankIntent(ctx, t)?.let {
            b.addAction(0, ctx.getString(R.string.btn_call_bank), PendingIntent.getActivity(ctx, id + 1, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } }
        AppLog.safe("post info", Unit) { NotificationManagerCompat.from(ctx).notify(id, b.build()) }
    }

    /** Money came back: which purchase it belongs to. */
    fun refund(ctx: Context, r: Txn, original: Txn) {
        val left = original.amount - original.refunded - r.amount
        val text = if (left > 0.01) ctx.getString(R.string.n_refund_part, Fmt.whole(left)) else ctx.getString(R.string.n_refund_full)
        info(ctx, r.id.toInt() + 500_000, ctx.getString(R.string.n_refund_title, Fmt.money(r.amount, r.currency), original.merchant ?: ""), text)
    }

    /** "Is this the repayment of the loan?" with a Mark repaid button. */
    fun loanPrompt(ctx: Context, t: Txn, l: Loan) {
        if (!allowed(ctx)) return
        channels(ctx)
        val amount = minOf(t.amount, l.amount - l.repaid)
        val repaid = PendingIntent.getBroadcast(ctx, 700_000 + l.id.toInt(),
            Intent(ctx, ActionReceiver::class.java).setAction(ACTION_REPAID).putExtra(EXTRA_LOAN, l.id).putExtra(EXTRA_AMOUNT, amount).putExtra(EXTRA_TXN, t.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(ctx, CH_INFO).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(ctx.getString(R.string.n_loan_prompt_title, l.counterparty))
            .setContentText(ctx.getString(R.string.n_loan_prompt_text, Fmt.whole(t.amount), Fmt.whole(l.amount - l.repaid)))
            .setAutoCancel(true)
            .addAction(0, ctx.getString(R.string.btn_mark_repaid), repaid)
        AppLog.safe("post loan prompt", Unit) { NotificationManagerCompat.from(ctx).notify(700_000 + l.id.toInt(), b.build()) }
    }
}

/**
 * Confirmed OK. "OK this time" closes this alert only; "Always OK" also trusts the merchant for this
 * person (other family members still get alerts there).
 */
fun confirmOk(ctx: Context, t: Txn, always: Boolean = false) {
    val db = Db.get(ctx)
    db.updateTxn(t.copy(alert = AlertState.OK))
    if (always) t.merchant?.let { db.trust(t.memberId, it) }
    Notifier.cancel(ctx, t.id)
    WorkManagerCancel.reminder(ctx, t.id)
    AppLog.i("alert confirmed ok (always=$always)")
}

object WorkManagerCancel {
    fun reminder(ctx: Context, id: Long) = AppLog.safe("cancel reminder", Unit) { androidx.work.WorkManager.getInstance(ctx).cancelUniqueWork("reminder_$id") }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        AppLog.init(ctx)
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    Notifier.ACTION_OK -> Db.get(ctx).txn(intent.getLongExtra(Notifier.EXTRA_TXN, -1))?.let { confirmOk(ctx, it) }
                    Notifier.ACTION_REPAID -> {
                        val loan = intent.getLongExtra(Notifier.EXTRA_LOAN, -1)
                        Plan.repay(ctx, loan, intent.getDoubleExtra(Notifier.EXTRA_AMOUNT, 0.0))
                        Notifier.cancel(ctx, 700_000L + loan)
                    }
                }
            } catch (e: Exception) { AppLog.e("notification action", e) } finally { pending.finish() }
        }.start()
    }
}
