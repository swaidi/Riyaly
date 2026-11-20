package app.riyaly

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Turns one bank SMS into a stored transaction, runs the checks, links refunds, and notifies. */
object Processor {

    private val DUP_CODES = setOf(Analyzer.DUPLICATE, Analyzer.POSSIBLE_DUPLICATE, Analyzer.DOUBLE_BOOKING)

    /** Merchant key honouring merged spellings ("HUNGERSTA" counted with "HungerStation"). */
    fun keyResolver(db: Db): (String?) -> String? {
        val alias = db.merchants().filter { !it.aliasOf.isNullOrBlank() }
            .mapNotNull { m -> Insights.key(m.name)?.let { it to Insights.key(m.aliasOf) } }.toMap()
        return { m -> Insights.key(m)?.let { k -> alias[k] ?: k } }
    }

    fun configFor(ctx: Context, db: Db, history: List<Txn>? = null): Analyzer.Config {
        val p = Prefs(ctx)
        val keyOf = keyResolver(db)
        val merchants = db.merchants()
        val states = db.recStates()
        val manual = states.values.filter { it.state == "not_recurring" || it.periodDays != null }
            .associate { it.key to if (it.state == "not_recurring") 0 else it.periodDays!! }
        val hist = history ?: db.txns(System.currentTimeMillis() - 400 * Insights.DAY)
        val recurring = AppLog.safe("recurring", emptyList()) { Insights.recurring(hist, { keyOf(it.merchant) }, manual) }
        val trustCache = HashMap<Long?, Set<String>>()
        return Analyzer.Config(
            enabled = { p.rule(it) },
            largeAmount = p.largeAmount.toDouble(),
            transferLimit = p.transferLimit.toDouble(),
            cards = db.cards().associateBy { it.last4 },
            quietStart = p.quietStart,
            quietEnd = p.quietEnd,
            homeCity = p.homeCity,
            watch = db.watch(),
            trustedMerchants = merchants.filter { it.trusted }.map { it.name }.toSet(),
            trustedFor = { id -> trustCache.getOrPut(id) { db.trusted(id) } },
            keyOf = keyOf,
            repeatsNormal = merchants.filter { it.repeatsNormal }.mapNotNull { keyOf(it.name) }.toSet(),
            recurring = recurring.associateBy { it.key },
            cancelled = states.values.filter { it.state == "cancelled" }.map { it.key }.toSet(),
            learnNight = p.learnedHours,
        )
    }

    fun hash(sender: String, body: String, ts: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest("$sender|${body.trim()}|${ts / 60_000}".toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

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
        // A transfer to or from another registered family account shows that member's name, and is internal.
        val cpCard = r.counterpartyAcct?.let { a -> listOf(a.takeLast(4), a.takeLast(3)).firstOrNull { it in cardOwner } }
        val cpMember = cpCard?.let { cardOwner[it] }?.let { db.member(it)?.name }
        val cardInfo = cards.firstOrNull { it.last4 == card }
        val transfer = r.kind == Parser.Kind.TRANSFER_IN || r.kind == Parser.Kind.TRANSFER_OUT
        return base.copy(
            amount = r.amount ?: 0.0, currency = r.currency ?: "SAR", balance = r.balance,
            merchant = r.merchant, method = r.method, type = r.type,
            isCredit = r.isCredit, isDeclined = r.isDeclined, isForeign = r.isForeign,
            card = card, memberId = memberId, match = match, candidates = candidates,
            city = r.cities.firstOrNull(),
            kind = r.kind, fees = r.fees, origAmount = r.origAmount, origCurrency = r.origCurrency, country = r.country,
            counterparty = cpMember ?: r.counterpartyName, counterpartyAcct = r.counterpartyAcct,
            declineReason = r.declineReason, duringTravel = cardInfo?.travelActive(base.ts) == true,
            internal = (cpCard != null && transfer) || (base.internal && transfer),
            feeType = when {
                r.kind == Parser.Kind.FEE -> Parser.feeTypeOf(base.body)
                (r.fees ?: 0.0) > 0 -> "international"
                else -> null
            },
        )
    }

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
        val sig = signature(t.body)
        p.ignoredPatterns = p.ignoredPatterns + sig
        val db = Db.get(ctx)
        db.txns().filter { signature(it.body) == sig }.forEach { db.deleteTxn(it.id); Notifier.cancel(ctx, it.id) }
        AppLog.i("marked not a transaction: ${AppLog.shape(t.body)}")
    }

    /** Income type of money in: salary, person, refund, internal or other. A user's own choice is kept. */
    fun incomeTypeOf(t: Txn, history: List<Txn>): String? = when {
        t.kind == Parser.Kind.REFUND -> "refund"
        t.kind != Parser.Kind.TRANSFER_IN && t.kind != Parser.Kind.CREDIT_IN -> null
        t.internal -> "internal"
        Insights.isSalaryLike(t, history) -> "salary"
        t.kind == Parser.Kind.TRANSFER_IN -> "person"
        else -> "other"
    }

    /** The purchase a refund belongs to: same merchant (or same card), within 60 days, not yet fully refunded. */
    fun matchRefund(refund: Txn, history: List<Txn>, keyOf: (String?) -> String?): Txn? {
        val key = keyOf(refund.merchant)
        val candidates = history.filter {
            it.id != refund.id && !it.isDeclined &&
                it.kind in setOf(Parser.Kind.PURCHASE, Parser.Kind.BILL, Parser.Kind.WITHDRAWAL) &&
                it.ts <= refund.ts && refund.ts - it.ts <= 60 * Insights.DAY &&
                (if (key != null) keyOf(it.merchant) == key else (refund.card != null && it.card == refund.card)) &&
                it.amount - it.refunded >= refund.amount - 0.01
        }
        // Prefer an exact amount, then the most recent.
        return candidates.minWithOrNull(compareBy<Txn>({ abs(it.amount - it.refunded - refund.amount) > 0.01 }, { -it.ts }))
    }

    /** Transfer out and in of the same amount between two registered cards within 10 minutes. */
    fun internalPair(t: Txn, history: List<Txn>): Txn? {
        if (t.internal || t.card == null || t.match != Match.IDENTIFIED) return null
        val opposite = when (t.kind) {
            Parser.Kind.TRANSFER_IN -> Parser.Kind.TRANSFER_OUT
            Parser.Kind.TRANSFER_OUT -> Parser.Kind.TRANSFER_IN
            else -> return null
        }
        return history.firstOrNull {
            it.kind == opposite && it.match == Match.IDENTIFIED && it.card != t.card &&
                abs(it.amount - t.amount) < 0.01 && abs(it.ts - t.ts) <= 10 * 60_000
        }
    }

    /** Runs every check and returns the transaction with reasons, risk, explanation and duplicate link set. */
    fun evaluate(ctx: Context, t: Txn, history: List<Txn>, cfg: Analyzer.Config, refundMatched: Boolean, who: String?): Pair<Txn, List<String>> {
        val findings = AppLog.safe("checks", emptyList()) { Analyzer.findings(t, history, cfg) }.toMutableList()
        if (t.kind == Parser.Kind.REFUND && !t.isDeclined && !refundMatched && cfg.enabled(Analyzer.UNEXPECTED_REFUND)) {
            findings += Analyzer.Finding(Analyzer.UNEXPECTED_REFUND, a = t.amount)
        }
        val codes = findings.map { it.code }.distinct()
        val dup = findings.firstOrNull { it.code in DUP_CODES }
        val explain = findings.joinToString("\n") { AppLog.safe("explain", "") { Analyzer.explain(ctx, it, who) } }.trim().ifEmpty { null }
        return t.copy(
            reasons = codes,
            risk = if (codes.isEmpty()) null else Analyzer.riskOf(codes),
            explain = explain,
            dupOf = dup?.b?.toLong(),
        ) to codes
    }

    /**
     * @param live true for an SMS that just arrived (notify, look up location);
     *             false for messages imported from the inbox (stored quietly, alerts marked reviewed).
     * @return the stored transaction, or null when the SMS is not a bank transaction.
     */
    fun handle(ctx: Context, sender: String, body: String, ts: Long, live: Boolean): Txn? {
        val db = Db.get(ctx)
        val bank = db.bankForSender(sender) ?: return null
        if (isIgnored(ctx, sender, body)) { AppLog.i("ignored (one-time code, ad or marked): ${AppLog.shape(body)}"); return null }
        val h = hash(sender, body, ts)
        if (db.hashExists(h) || db.sameMessageExists(sender, body, ts)) return null

        val cards = db.cards()
        val r = Parser.parse(body, cards.map { it.last4 }.toSet(), Parser.Profile(bank.balanceWords, bank.merchantPrefixes))
        if (!r.isTransaction) { AppLog.i("bank message not read as a transaction: ${AppLog.shape(body)}"); return null }

        val base = Txn(
            id = 0, smsHash = h, sender = sender, bankId = bank.id, body = body, ts = ts,
            amount = 0.0, currency = "SAR", balance = null, merchant = null, method = null, type = null,
            isCredit = false, isDeclined = false, isForeign = false, card = null, memberId = null,
            match = Match.UNKNOWN, candidates = emptyList(), alert = AlertState.NONE, reasons = emptyList(),
            city = null, distanceKm = null,
        )
        var t = build(db, r, cards, base)
        val history = db.txns(ts - 400 * Insights.DAY, ts)
        val cfg = configFor(ctx, db, history)

        val pair = internalPair(t, history)
        if (pair != null) t = t.copy(internal = true)
        t = t.copy(incomeType = incomeTypeOf(t, history))

        // Refunds: link to the original purchase.
        val original = if (t.kind == Parser.Kind.REFUND && !t.isDeclined) matchRefund(t, history, cfg.keyOf) else null
        if (original != null) t = t.copy(refundOf = original.id, merchant = t.merchant ?: original.merchant)

        val who = db.member(t.memberId)?.name
        val (checked, codes) = evaluate(ctx, t, history, cfg, original != null, who)
        t = checked.copy(
            dupState = if (checked.dupOf != null) DupState.OPEN else null,
            alert = when {
                codes.isEmpty() || t.risk == Risk.LOW -> AlertState.NONE
                live -> AlertState.OPEN
                else -> AlertState.OK
            },
        )
        val id = db.insertTxn(t)
        if (id <= 0) return null
        t = t.copy(id = id)
        AppLog.i("recorded ${t.kind} risk=${t.risk ?: "-"} reasons=${codes.joinToString(",")} msg=${AppLog.shape(body)}")

        // Update the linked records.
        pair?.let { db.updateTxn(it.copy(internal = true, incomeType = if (it.incomeType != null) "internal" else null)) }
        if (original != null) {
            db.updateTxn(original.copy(refunded = original.refunded + t.amount, dupState = if (original.dupState != null) DupState.REFUNDED else null))
            // A duplicate pair is closed when either charge is refunded.
            history.filter { it.dupOf == original.id && it.dupState != null }.forEach { db.updateTxn(it.copy(dupState = DupState.REFUNDED)) }
            original.dupOf?.let { db.txn(it) }?.let { o -> if (o.dupState != null) db.updateTxn(o.copy(dupState = DupState.REFUNDED)) }
        }

        if (live) {
            AppLog.safe("notify", Unit) {
                Notifier.notify(ctx, t)
                if (original != null) Notifier.refund(ctx, t, original)
            }
            AppLog.safe("loan prompt", Unit) { if (t.kind == Parser.Kind.TRANSFER_IN && !t.internal) Plan.loanRepaymentPrompt(ctx, t) }
            if (t.risk == Risk.HIGH && t.alert == AlertState.OPEN) scheduleReminder(ctx, t.id)
            maybeLookup(ctx, t)
            AppLog.safe("plan alerts", Unit) { Plan.checkPlanAlerts(ctx, t) }
            Backup.scheduleSoon(ctx)
        }
        return t
    }

    /** A High alert still unanswered after 15 minutes is repeated once. */
    private fun scheduleReminder(ctx: Context, id: Long) = AppLog.safe("reminder", Unit) {
        val req = OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(15, TimeUnit.MINUTES).setInputData(workDataOf("txn" to id)).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("reminder_$id", ExistingWorkPolicy.REPLACE, req)
    }

    /**
     * After an app update that reads messages better: re-read every stored message and refresh the
     * extracted details. Your choices — the person you picked, Confirmed OK / checking status, notes,
     * salary choice, duplicate decisions and refund links — are kept.
     */
    fun reparseAll(ctx: Context) {
        val db = Db.get(ctx)
        val cards = db.cards()
        val banks = db.banks().associateBy { it.id }
        // Earlier versions recorded some one-time passwords and advertisements: remove them.
        db.txns().filter { isIgnored(ctx, it.sender, it.body) }.forEach { db.deleteTxn(it.id) }
        val all = db.txns().sortedBy { it.ts }
        val cfg = configFor(ctx, db, all)
        val done = ArrayList<Txn>()
        for (old in all) {
            val bank = old.bankId?.let { banks[it] }
            val r = Parser.parse(old.body, cards.map { it.last4 }.toSet(),
                bank?.let { Parser.Profile(it.balanceWords, it.merchantPrefixes) } ?: Parser.Profile())
            if (!r.isTransaction) { done += old; continue }
            var t = build(db, r, cards, old)
            if (old.match == Match.IDENTIFIED && t.match != Match.IDENTIFIED) {
                t = t.copy(memberId = old.memberId, match = old.match, candidates = emptyList(), card = old.card ?: t.card)
            }
            val history = done.filter { it.ts > old.ts - 400 * Insights.DAY }
            internalPair(t, history)?.let { p ->
                t = t.copy(internal = true)
                val upd = p.copy(internal = true, incomeType = if (p.incomeType != null) "internal" else null)
                db.updateTxn(upd); done.replaceAll { if (it.id == upd.id) upd else it }
            }
            val userIncome = old.incomeType?.takeIf { it == "salary" || it == "not_salary" }
            t = t.copy(incomeType = userIncome ?: incomeTypeOf(t, history))
            var matched = old.refundOf != null
            if (t.kind == Parser.Kind.REFUND && old.refundOf == null) {
                matchRefund(t, history, cfg.keyOf)?.let { orig ->
                    matched = true
                    t = t.copy(refundOf = orig.id)
                    val upd = orig.copy(refunded = orig.refunded + t.amount)
                    db.updateTxn(upd); done.replaceAll { if (it.id == upd.id) upd else it }
                }
            }
            val (checked, codes0) = evaluate(ctx, t, history, cfg, matched, db.member(t.memberId)?.name)
            val codes = if (Analyzer.FAR in old.reasons) (codes0 + Analyzer.FAR).distinct() else codes0
            val risk = if (codes.isEmpty()) null else Analyzer.riskOf(codes)
            val alert = when {
                codes.isEmpty() || risk == Risk.LOW -> AlertState.NONE
                old.alert == AlertState.NONE -> AlertState.OK // older messages are not re-alerted
                else -> old.alert
            }
            val final = checked.copy(
                reasons = codes, risk = risk, alert = alert, distanceKm = old.distanceKm,
                dupOf = old.dupOf ?: checked.dupOf,
                dupState = old.dupState ?: (if (checked.dupOf != null) DupState.NORMAL else null),
                refunded = done.firstOrNull { it.id == old.id }?.refunded ?: old.refunded,
            )
            db.updateTxn(final)
            done.removeAll { it.id == final.id }
            done += final
        }
        AppLog.i("re-read ${all.size} stored messages")
    }

    fun maybeLookup(ctx: Context, t: Txn) {
        val p = Prefs(ctx)
        if (!p.lookupEnabled || !p.rule(Analyzer.FAR) || t.merchant.isNullOrBlank() || p.homeLat == null) return
        if (t.kind !in Parser.Kind.SPEND || t.duringTravel) return
        AppLog.safe("lookup", Unit) {
            val req = OneTimeWorkRequestBuilder<LookupWorker>()
                .setInputData(workDataOf("txn" to t.id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        }
    }

    /** Re-identify a transaction after the user registers a new card or chooses a person. */
    fun assign(ctx: Context, t: Txn, memberId: Long): Txn {
        val db = Db.get(ctx)
        val reasons = t.reasons - Analyzer.AMBIGUOUS - Analyzer.UNKNOWN_CARD
        val risk = if (reasons.isEmpty()) null else Analyzer.riskOf(reasons)
        val final = t.copy(
            memberId = memberId, match = Match.IDENTIFIED, candidates = emptyList(), reasons = reasons, risk = risk,
            alert = if (reasons.isEmpty() || risk == Risk.LOW) AlertState.NONE else t.alert,
        )
        db.updateTxn(final)
        return final
    }
}
