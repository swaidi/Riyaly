package app.riyaly

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony

/** Receives every incoming SMS; only senders listed under Banks are read. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        // A long message arrives in several parts; join them per sender.
        val bySender = parts.groupBy { it.displayOriginatingAddress ?: it.originatingAddress ?: "" }
        val pending = goAsync()
        Thread {
            try {
                for ((sender, list) in bySender) {
                    val body = list.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
                    val ts = list.minOf { it.timestampMillis }.takeIf { it > 0 } ?: System.currentTimeMillis()
                    Processor.handle(ctx, sender, body, ts, live = true)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** Reads earlier bank messages already in the phone's inbox, to build history from day one. */
object Inbox {
    data class Outcome(val read: Int, val added: Int)

    fun import(ctx: Context, sinceDays: Int = 365): Outcome {
        val db = Db.get(ctx)
        val banks = db.banks()
        if (banks.isEmpty()) return Outcome(0, 0)
        val since = System.currentTimeMillis() - sinceDays * 24L * 3600 * 1000
        var read = 0
        var added = 0
        val cr = ctx.contentResolver
        cr.query(
            Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"),
            "date>=?", arrayOf("$since"), "date ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(0) ?: continue
                if (db.bankForSender(address) == null) continue
                read++
                val t = Processor.handle(ctx, address, c.getString(1) ?: "", c.getLong(2), live = false)
                if (t != null) added++
            }
        }
        return Outcome(read, added)
    }

    /** After a card or account is registered, re-identify older unidentified transactions with it. */
    fun reidentify(ctx: Context) {
        val db = Db.get(ctx)
        val cards = db.cards()
        val banks = db.banks().associateBy { it.id }
        for (t in db.txns()) {
            if (t.match == Match.IDENTIFIED && t.counterparty != null) continue
            val bank = t.bankId?.let { banks[it] }
            val r = Parser.parse(t.body, cards.map { it.last4 }.toSet(),
                bank?.let { Parser.Profile(it.balanceWords, it.merchantPrefixes) } ?: Parser.Profile())
            if (!r.isTransaction) continue
            val b = Processor.build(db, r, cards, t)
            when {
                t.match == Match.IDENTIFIED -> db.updateTxn(t.copy(counterparty = b.counterparty))
                b.match == Match.IDENTIFIED -> Processor.assign(ctx, b.copy(alert = t.alert, reasons = t.reasons), b.memberId!!)
                b.match == Match.AMBIGUOUS && t.match != Match.AMBIGUOUS -> db.updateTxn(t.copy(match = Match.AMBIGUOUS, candidates = b.candidates))
            }
        }
    }
}
