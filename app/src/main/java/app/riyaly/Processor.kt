package app.riyaly

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.security.MessageDigest

/** Turns one bank SMS into a stored transaction, runs the checks, and notifies. */
object Processor {

    fun configFor(ctx: Context, db: Db): Analyzer.Config {
        val p = Prefs(ctx)
        return Analyzer.Config(
            enabled = { p.rule(it) },
            largeAmount = p.largeAmount.toDouble(),
            transferLimit = p.transferLimit.toDouble(),
            cards = db.cards().associateBy { it.last4 },
            quietStart = p.quietStart,
            quietEnd = p.quietEnd,
            homeCity = p.homeCity,
            watch = db.watch(),
            trustedMerchants = db.merchants().filter { it.trusted }.map { it.name }.toSet(),
        )
    }

    fun hash(sender: String, body: String, ts: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest("$sender|${body.trim()}|${ts / 60_000}".toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * @param live true for an SMS that just arrived (notify, look up location);
     *             false for messages imported from the inbox (stored quietly, alerts marked reviewed).
     * @return the stored transaction, or null when the SMS is not a bank transaction.
     */
    /** Builds an (unsaved) transaction from a parsed message: who, which card/account, transfer party. */
    fun build(db: Db, r: Parser.Result, cards: List<Card>, base: Txn): Txn {
        val cardOwner = cards.associate { it.last4 to it.memberId }
        val owners = r.matchedCards.mapNotNull { cardOwner[it] }.distinct()
        val (match, memberId, candidates) = when {
            owners.size == 1 -> Triple(Match.IDENTIFIED, owners[0], emptyList())
            owners.size > 1 -> Triple(Match.AMBIGUOUS, null, owners)
            else -> Triple(Match.UNKNOWN, null, emptyList())
        }
        val card = when {
            owners.size == 1 -> r.matchedCards.first { cardOwner[it] == owners[0] }
            r.matchedCards.isNotEmpty() -> r.matchedCards.first()
            else -> r.unknownCards.firstOrNull()
        }
        // A transfer to or from another registered family account shows that member's name.
        val cpMember = r.counterpartyAcct?.takeLast(4)?.let { cardOwner[it] }?.let { db.member(it)?.name }
        val cardInfo = cards.firstOrNull { it.last4 == card }
        return base.copy(
            amount = r.amount ?: 0.0, currency = r.currency ?: "SAR", balance = r.balance,
            merchant = r.merchant, method = r.method, type = r.type,
            isCredit = r.isCredit, isDeclined = r.isDeclined, isForeign = r.isForeign,
            card = card, memberId = memberId, match = match, candidates = candidates,
            city = r.cities.firstOrNull(),
            kind = r.kind, fees = r.fees, origAmount = r.origAmount, origCurrency = r.origCurrency, country = r.country,
            counterparty = cpMember ?: r.counterpartyName, counterpartyAcct = r.counterpartyAcct,
            declineReason = r.declineReason, duringTravel = cardInfo?.travelActive(base.ts) == true,
        )
    }

    /**
     * @param live true for an SMS that just arrived (notify, look up location);
     *             false for messages imported from the inbox (stored quietly, alerts marked reviewed).
     * @return the stored transaction, or null when the SMS is not a bank transaction.
     */
    /**
     * A message's "shape" for "Not a transaction": the whole text with numbers replaced by #, so the same
     * advertisement or notice with other numbers matches, but a purchase at another merchant does not.
     */
    fun signature(body: String): String {
        val lines = Parser.normalize(body).lines().map { it.trim() }.filter { it.isNotEmpty() }
        fun shape(l: String) = l.lowercase().replace(Regex("\\d+([.,]\\d+)*"), "#").replace(Regex("\\s+"), " ").trim()
        return lines.joinToString(" | ") { shape(it) }.take(300)
    }

    fun isIgnored(ctx: Context, sender: String, body: String): Boolean =
        isAdSender(sender) || Parser.isIgnorable(body) || signature(body) in Prefs(ctx).ignoredPatterns

    /** "Not a transaction": remove it, and ignore messages shaped like it from now on. */
    fun markNotTransaction(ctx: Context, t: Txn) {
        val p = Prefs(ctx)
        p.ignoredPatterns = p.ignoredPatterns + signature(t.body)
        val db = Db.get(ctx)
        val sig = signature(t.body)
        db.txns().filter { signature(it.body) == sig }.forEach { db.deleteTxn(it.id); Notifier.cancel(ctx, it.id) }
    }

    fun handle(ctx: Context, sender: String, body: String, ts: Long, live: Boolean): Txn? {
        val db = Db.get(ctx)
        val bank = db.bankForSender(sender) ?: return null
        if (isIgnored(ctx, sender, body)) return null
        val h = hash(sender, body, ts)
        if (db.hashExists(h) || db.sameMessageExists(sender, body, ts)) return null

        val cards = db.cards()
        val r = Parser.parse(body, cards.map { it.last4 }.toSet(), Parser.Profile(bank.balanceWords, bank.merchantPrefixes))
        if (!r.isTransaction) return null

        val base = Txn(
            id = 0, smsHash = h, sender = sender, bankId = bank.id, body = body, ts = ts,
            amount = 0.0, currency = "SAR", balance = null, merchant = null, method = null, type = null,
            isCredit = false, isDeclined = false, isForeign = false, card = null, memberId = null,
            match = Match.UNKNOWN, candidates = emptyList(), alert = AlertState.NONE, reasons = emptyList(),
            city = null, distanceKm = null,
        )
        var t = build(db, r, cards, base)
        val history = db.txns(ts - 180L * 24 * 3600 * 1000, ts)
        val reasons = Analyzer.check(t, history, configFor(ctx, db))
        t = t.copy(
            reasons = reasons,
            alert = when {
                reasons.isEmpty() -> AlertState.NONE
                live -> AlertState.OPEN
                else -> AlertState.OK
            },
        )
        val id = db.insertTxn(t)
        if (id <= 0) return null
        t = t.copy(id = id)

        if (live) {
            Notifier.notify(ctx, t)
            maybeLookup(ctx, t)
            Backup.scheduleSoon(ctx)
        }
        return t
    }

    /**
     * After an app update that reads messages better: re-read every stored message and refresh the
     * extracted details (type, amount, merchant, transfer party…). Your choices — the person you picked,
     * Confirmed OK / checking status — are kept.
     */
    fun reparseAll(ctx: Context) {
        val db = Db.get(ctx)
        val cards = db.cards()
        val banks = db.banks().associateBy { it.id }
        val cfg = configFor(ctx, db)
        // Earlier versions recorded some one-time passwords and advertisements: remove them.
        db.txns().filter { isIgnored(ctx, it.sender, it.body) }.forEach { db.deleteTxn(it.id) }
        val all = db.txns().sortedBy { it.ts }
        for (old in all) {
            val bank = old.bankId?.let { banks[it] }
            val r = Parser.parse(old.body, cards.map { it.last4 }.toSet(),
                bank?.let { Parser.Profile(it.balanceWords, it.merchantPrefixes) } ?: Parser.Profile())
            if (!r.isTransaction) continue
            var t = build(db, r, cards, old)
            if (old.match == Match.IDENTIFIED && t.match != Match.IDENTIFIED) {
                t = t.copy(memberId = old.memberId, match = old.match, candidates = emptyList(), card = old.card ?: t.card)
            }
            val history = all.filter { it.ts < old.ts && it.ts > old.ts - 180L * 24 * 3600 * 1000 }
            val reasons = Analyzer.check(t, history, cfg) + (if (Analyzer.FAR in old.reasons) listOf(Analyzer.FAR) else emptyList())
            val alert = when {
                reasons.isEmpty() -> AlertState.NONE
                old.alert == AlertState.NONE -> AlertState.OK // older messages are not re-alerted
                else -> old.alert
            }
            db.updateTxn(t.copy(reasons = reasons.distinct(), alert = alert, distanceKm = old.distanceKm))
        }
    }

    fun maybeLookup(ctx: Context, t: Txn) {
        val p = Prefs(ctx)
        if (!p.lookupEnabled || !p.rule(Analyzer.FAR) || t.merchant.isNullOrBlank() || p.homeLat == null) return
        if (t.kind !in Parser.Kind.SPEND || t.duringTravel) return
        val req = OneTimeWorkRequestBuilder<LookupWorker>()
            .setInputData(workDataOf("txn" to t.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueue(req)
    }

    /** Re-identify a transaction after the user registers a new card or chooses a person. */
    fun assign(ctx: Context, t: Txn, memberId: Long): Txn {
        val db = Db.get(ctx)
        val updated = t.copy(
            memberId = memberId, match = Match.IDENTIFIED, candidates = emptyList(),
            reasons = t.reasons - Analyzer.AMBIGUOUS - Analyzer.UNKNOWN_CARD,
        )
        val final = updated.copy(alert = if (updated.reasons.isEmpty()) AlertState.NONE else updated.alert)
        db.updateTxn(final)
        return final
    }
}
