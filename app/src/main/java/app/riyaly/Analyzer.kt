package app.riyaly

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pattern checks. Each member's normal behaviour is learned from his or her own history, so the
 * checks become more personal as the history grows. Findings are combined into one risk level:
 * High (loud alert), Medium (quiet list) or Low (recorded only).
 */
object Analyzer {
    const val UNKNOWN_CARD = "unknown_card"
    const val AMBIGUOUS = "ambiguous"
    const val LARGE_AMOUNT = "large_amount"
    const val LARGE_WITHDRAWAL = "large_withdrawal"
    const val MONTHLY_LIMIT = "monthly_limit"
    const val UNUSUAL_AMOUNT = "unusual_amount"
    const val NEW_MERCHANT = "new_merchant"
    const val UNUSUAL_HOUR = "unusual_hour"
    const val QUIET_HOURS = "quiet_hours"
    const val RAPID = "rapid"
    const val DUPLICATE = "duplicate"
    const val POSSIBLE_DUPLICATE = "possible_duplicate"
    const val DOUBLE_BOOKING = "double_booking"
    const val FOREIGN = "foreign"
    const val OUTSIDE_DEST = "outside_destination"
    const val OTHER_CITY = "other_city"
    const val WATCH = "watch"
    const val FAR = "far"
    const val DECLINED = "declined"
    const val LARGE_TRANSFER = "large_transfer"
    const val NEW_RECIPIENT = "new_recipient"
    const val CARD_TESTING = "card_testing"
    const val FIRST_ONLINE = "first_online"
    const val CANCELLED_CHARGED = "cancelled_charged"
    const val PRICE_UP = "price_up"
    const val TRIAL_ENDED = "trial_ended"
    const val UNEXPECTED_FEE = "unexpected_fee"
    const val UNEXPECTED_REFUND = "unexpected_refund"

    val ALL = listOf(
        UNKNOWN_CARD, AMBIGUOUS, LARGE_AMOUNT, LARGE_WITHDRAWAL, MONTHLY_LIMIT, UNUSUAL_AMOUNT, NEW_MERCHANT, UNUSUAL_HOUR,
        QUIET_HOURS, RAPID, DUPLICATE, POSSIBLE_DUPLICATE, DOUBLE_BOOKING, FOREIGN, OUTSIDE_DEST, OTHER_CITY, WATCH, FAR,
        DECLINED, LARGE_TRANSFER, NEW_RECIPIENT, CARD_TESTING, FIRST_ONLINE, CANCELLED_CHARGED, PRICE_UP, TRIAL_ENDED,
        UNEXPECTED_FEE, UNEXPECTED_REFUND,
    )

    /** Checks that travel mode pauses for the card. */
    val TRAVEL_PAUSED = setOf(FOREIGN, OTHER_CITY, FAR)

    /** Findings that are High on their own: rules the user set, and strong fraud patterns. */
    val HIGH_ALONE = setOf(CARD_TESTING, OUTSIDE_DEST, CANCELLED_CHARGED, WATCH, LARGE_AMOUNT, LARGE_WITHDRAWAL, LARGE_TRANSFER, MONTHLY_LIMIT, DUPLICATE)

    /** Findings that are information only (Low): recorded, never an alert by themselves. */
    val INFO_ONLY = setOf(PRICE_UP)

    /** One finding with the numbers behind it, for a clear explanation. */
    data class Finding(val code: String, val a: Double? = null, val b: Double? = null, val text: String? = null)

    data class Config(
        val enabled: (String) -> Boolean = { true },
        val largeAmount: Double = 1000.0,
        val transferLimit: Double = 5000.0,
        val quietStart: Int = 1,
        val quietEnd: Int = 5,
        val homeCity: String = "Riyadh",
        val watch: List<String> = emptyList(),
        val trustedMerchants: Set<String> = emptySet(),
        val trustedFor: (Long?) -> Set<String> = { emptySet() },
        val cards: Map<String, Card> = emptyMap(),
        val keyOf: (String?) -> String? = { Insights.key(it) },
        val repeatsNormal: Set<String> = emptySet(),
        val recurring: Map<String, Insights.Recurring> = emptyMap(),
        val cancelled: Set<String> = emptySet(),
        /** Learn each person's own night hours (no alert when they often shop late). */
        val learnNight: Boolean = true,
    )

    private const val MIN_AMOUNT_HISTORY = 8
    private const val MIN_MERCHANT_HISTORY = 10
    private const val MIN_HOUR_HISTORY = 20
    private const val MIN_TRANSFER_HISTORY = 3

    private val ONLINE_WORDS = listOf("انترنت", "إنترنت", "online", "internet", "e-commerce")
    private val HOLD_WORDS = listOf("HOTEL", "INN", "RESORT", "MARRIOTT", "HILTON", "HYATT", "RENT", "CAR RENTAL", "YELO", "HERTZ", "BUDGET", "SIXT", "KEY", "FUEL", "PETROL", "ALDREES", "SASCO", "فندق", "تأجير", "تاجير")

    fun hourOf(ts: Long): Int = Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.HOUR_OF_DAY)

    private fun monthStart(ts: Long): Long = Insights.month(ts).from

    private fun same(a: String, b: String): Boolean {
        val x = a.trim().uppercase(); val y = b.trim().uppercase()
        return x.isNotEmpty() && y.isNotEmpty() && (x == y || x.contains(y) || y.contains(x))
    }

    fun isOnline(t: Txn): Boolean { val l = (t.type.orEmpty() + " " + t.body).lowercase(); return ONLINE_WORDS.any { l.contains(it) } }

    fun isHoldMerchant(name: String?): Boolean { val n = name?.uppercase() ?: return false; return HOLD_WORDS.any { n.contains(it) } }

    /** Whether the transaction happened at the card's travel destination (unknown place = accepted). */
    fun atDestination(t: Txn, dest: String?): Boolean {
        if (dest.isNullOrBlank()) return true
        val places = listOfNotNull(t.country, t.city)
        if (places.isEmpty()) return true
        return dest.split(",").map { it.trim() }.filter { it.isNotEmpty() }.any { d -> places.any { same(it, d) } }
    }

    /** Codes only (kept for older callers and tests). */
    fun check(t: Txn, history: List<Txn>, cfg: Config): List<String> = findings(t, history, cfg).map { it.code }.distinct()

    /** The combined risk level of a set of findings. */
    fun riskOf(codes: Collection<String>): String {
        val real = codes.filter { it !in INFO_ONLY }
        if (real.isEmpty()) return Risk.LOW
        if (real.any { it in HIGH_ALONE }) return Risk.HIGH
        return if (real.size >= 3) Risk.HIGH else Risk.MEDIUM
    }

    /**
     * @param t the new transaction (already identified as far as possible)
     * @param history earlier transactions (any order), not including [t]
     */
    fun findings(t: Txn, history: List<Txn>, cfg: Config): List<Finding> {
        val r = mutableListOf<Finding>()
        fun add(f: Finding) { if (cfg.enabled(f.code) && r.none { it.code == f.code }) r += f }
        fun add(code: String, a: Double? = null, b: Double? = null) = add(Finding(code, a, b))
        val card = t.card?.let { cfg.cards[it] }
        val traveling = card?.travelActive(t.ts) == true
        val key = cfg.keyOf(t.merchant)

        if (t.match == Match.UNKNOWN && t.kind != Parser.Kind.FEE) add(UNKNOWN_CARD)
        if (t.match == Match.AMBIGUOUS) add(AMBIGUOUS)
        if (t.isDeclined) add(DECLINED)

        val merchantUp = t.merchant?.uppercase().orEmpty()
        val bodyUp = t.body.uppercase()
        cfg.watch.firstOrNull { it.isNotBlank() && (merchantUp.contains(it.uppercase()) || bodyUp.contains(it.uppercase())) }
            ?.let { add(Finding(WATCH, text = it)) }

        // Place checks; travel mode pauses them, but a purchase outside the destination still alerts.
        val otherCity = t.city != null && !t.city.equals(cfg.homeCity, ignoreCase = true)
        if (traveling) {
            if ((t.isForeign || otherCity) && !atDestination(t, card?.travelDest)) add(Finding(OUTSIDE_DEST, text = t.country ?: t.city))
        } else {
            if (t.isForeign) add(Finding(FOREIGN, text = t.country ?: t.origCurrency))
            if (otherCity) add(Finding(OTHER_CITY, text = t.city))
        }

        if (t.kind in Parser.Kind.INFLOW) return r

        val sar = if (t.currency == "SAR") t.amount else null

        // Fees: every bank fee other than an international purchase fee is worth a look.
        if (t.kind == Parser.Kind.FEE && !t.isDeclined) add(Finding(UNEXPECTED_FEE, a = sar, text = t.feeType))

        // Transfers out: their own limit and "new recipient".
        if (t.kind == Parser.Kind.TRANSFER_OUT) {
            if (!t.internal) {
                val limit = card?.limitSingle ?: cfg.transferLimit
                if (sar != null && limit > 0 && sar >= limit) add(LARGE_TRANSFER, limit)
                val past = history.filter { it.kind == Parser.Kind.TRANSFER_OUT }
                if (t.counterpartyAcct != null && past.size >= MIN_TRANSFER_HISTORY && past.none { it.counterpartyAcct == t.counterpartyAcct }) add(NEW_RECIPIENT)
            }
            return r
        }

        // Limits: per card when set, otherwise the general limit.
        if (sar != null && !t.isDeclined) {
            if (t.kind == Parser.Kind.WITHDRAWAL) {
                val limit = card?.limitWithdrawal ?: card?.limitSingle ?: cfg.largeAmount
                if (limit > 0 && sar >= limit) add(LARGE_WITHDRAWAL, limit)
            } else if (t.kind != Parser.Kind.FEE) {
                val limit = card?.limitSingle ?: cfg.largeAmount
                if (limit > 0 && sar >= limit) add(LARGE_AMOUNT, limit)
            }
            val monthly = card?.limitMonthly
            if (monthly != null && monthly > 0) {
                val start = monthStart(t.ts)
                val before = history.filter { it.card == t.card && it.isSpend && it.ts >= start && it.ts < t.ts }.sumOf { it.netSpend }
                if (before < monthly && before + sar >= monthly) add(MONTHLY_LIMIT, monthly, before + sar)
            }
        }

        // Card testing: a tiny charge or several declined attempts on the same card, minutes before a real one.
        if (t.card != null && sar != null && sar >= 50) {
            val recent = history.filter { it.card == t.card && it.ts in (t.ts - 30 * 60_000) until t.ts }
            val tiny = recent.any { !it.isDeclined && it.isSpend && it.amount in 0.01..5.0 }
            val declined = recent.count { it.isDeclined }
            if (tiny || declined >= 2) add(CARD_TESTING)
        }

        val mine = if (t.memberId != null) history.filter { it.memberId == t.memberId && it.kind == Parser.Kind.PURCHASE } else emptyList()
        val h = hourOf(t.ts)

        // Late night: the default window, unless this person often shops at those hours (learned).
        if (Analyzer.inRange(h, cfg.quietStart, cfg.quietEnd) && t.kind != Parser.Kind.FEE) {
            val usualAtNight = mine.size >= MIN_HOUR_HISTORY && mine.count { inRange(hourOf(it.ts), cfg.quietStart, cfg.quietEnd) } >= mine.size * 0.05
            if (!(cfg.learnNight && usualAtNight)) add(QUIET_HOURS, h.toDouble())
        }

        // Personal patterns apply to purchases.
        if (t.kind == Parser.Kind.PURCHASE) {
            val trusted = t.merchant != null && (t.merchant in cfg.trustedMerchants || (key != null && key in cfg.trustedFor(t.memberId)))

            val amounts = mine.filter { it.currency == "SAR" && !it.isDeclined }.map { it.amount }
            if (sar != null && amounts.size >= MIN_AMOUNT_HISTORY) {
                val mean = amounts.average()
                val sd = sqrt(amounts.map { (it - mean) * (it - mean) }.average())
                val median = amounts.sorted()[amounts.size / 2]
                if (sar > maxOf(mean + 3 * sd, median * 4) && sar > 100) add(UNUSUAL_AMOUNT, sar / median, median)
            }
            if (!trusted && !traveling && t.merchant != null && mine.size >= MIN_MERCHANT_HISTORY &&
                mine.none { cfg.keyOf(it.merchant) == key }
            ) add(NEW_MERCHANT)
            if (!traveling && mine.size >= MIN_HOUR_HISTORY) {
                val hours = mine.map { hourOf(it.ts) }.toSet()
                if (listOf((h + 23) % 24, h, (h + 1) % 24).none { it in hours }) add(UNUSUAL_HOUR, h.toDouble())
            }
            if (isOnline(t) && mine.size >= MIN_MERCHANT_HISTORY && mine.none { isOnline(it) }) add(FIRST_ONLINE)

            // Recurring payments: cancelled but charged, price up, free trial ended.
            if (key != null) {
                if (key in cfg.cancelled) add(CANCELLED_CHARGED)
                cfg.recurring[key]?.let { rec -> if (sar != null && sar > rec.lastAmount * 1.03 && sar - rec.lastAmount >= 1) add(PRICE_UP, rec.lastAmount, sar) }
                val before = history.filter { cfg.keyOf(it.merchant) == key && !it.isDeclined && it.kind == Parser.Kind.PURCHASE }
                if (sar != null && sar >= 10 && before.isNotEmpty() && before.all { it.amount <= 5.0 }) add(TRIAL_ENDED, before.last().amount, sar)
            }
        }

        // Rapid repetition on the same card.
        if (t.card != null) {
            val near = history.filter { it.card == t.card && it.ts in (t.ts - 10 * 60_000)..t.ts && it.kind in Parser.Kind.SPEND }
            if (near.size >= 2) add(RAPID)
        }
        duplicateFinding(t, history, cfg)?.let { add(it) }
        return r
    }

    /**
     * Duplicate charges: the same amount at the same merchant much sooner than usual.
     * Within 10 minutes is High; sooner than half this merchant's usual gap (or within 24 hours
     * while the rhythm is still unknown) is Medium. Merchants marked "Repeats are normal" are skipped.
     */
    fun duplicateFinding(t: Txn, history: List<Txn>, cfg: Config): Finding? {
        if (t.isDeclined || t.kind !in Parser.Kind.SPEND || t.kind == Parser.Kind.FEE) return null
        val key = cfg.keyOf(t.merchant) ?: return null
        val tolerance = if (t.isForeign || isOnline(t)) 0.01 else 0.0
        fun close(o: Txn) = abs(o.amount - t.amount) <= t.amount * tolerance + 0.001
        val sameMerchant = history.filter { cfg.keyOf(it.merchant) == key && !it.isDeclined && it.ts <= t.ts && it.id != t.id }
        val prev = sameMerchant.filter { it.card == t.card && close(it) && it.kind == t.kind }.maxByOrNull { it.ts }
        if (prev != null) {
            val gapMin = (t.ts - prev.ts) / 60_000.0
            if (gapMin <= 10) return Finding(DUPLICATE, gapMin, prev.id.toDouble())
            if (key !in cfg.repeatsNormal) {
                val usual = Insights.usualGapMinutes(sameMerchant.filter { it.card == t.card }.map { it.ts })
                val tooSoon = if (usual != null) gapMin < usual / 2 else gapMin <= 24 * 60
                if (tooSoon) return Finding(POSSIBLE_DUPLICATE, gapMin, prev.id.toDouble(), if (isHoldMerchant(t.merchant)) "hold" else null)
            }
        }
        // The same booking paid on two family cards.
        val other = sameMerchant.firstOrNull { it.card != null && it.card != t.card && close(it) && t.ts - it.ts <= 60 * 60_000 }
        if (other != null && key !in cfg.repeatsNormal) return Finding(DOUBLE_BOOKING, (t.ts - other.ts) / 60_000.0, other.id.toDouble())
        return null
    }

    fun inRange(h: Int, start: Int, end: Int): Boolean =
        if (start == end) false else if (start < end) h in start until end else h >= start || h < end

    fun label(ctx: android.content.Context, code: String): String = ctx.getString(
        when (code) {
            UNKNOWN_CARD -> R.string.r_unknown_card
            AMBIGUOUS -> R.string.r_ambiguous
            LARGE_AMOUNT -> R.string.r_large_amount
            LARGE_WITHDRAWAL -> R.string.r_large_withdrawal
            MONTHLY_LIMIT -> R.string.r_monthly_limit
            UNUSUAL_AMOUNT -> R.string.r_unusual_amount
            NEW_MERCHANT -> R.string.r_new_merchant
            UNUSUAL_HOUR -> R.string.r_unusual_hour
            QUIET_HOURS -> R.string.r_quiet_hours
            RAPID -> R.string.r_rapid
            DUPLICATE -> R.string.r_duplicate
            POSSIBLE_DUPLICATE -> R.string.r_possible_duplicate
            DOUBLE_BOOKING -> R.string.r_double_booking
            FOREIGN -> R.string.r_foreign
            OUTSIDE_DEST -> R.string.r_outside_dest
            OTHER_CITY -> R.string.r_other_city
            WATCH -> R.string.r_watch
            FAR -> R.string.r_far
            DECLINED -> R.string.r_declined
            LARGE_TRANSFER -> R.string.r_large_transfer
            NEW_RECIPIENT -> R.string.r_new_recipient
            CARD_TESTING -> R.string.r_card_testing
            FIRST_ONLINE -> R.string.r_first_online
            CANCELLED_CHARGED -> R.string.r_cancelled_charged
            PRICE_UP -> R.string.r_price_up
            TRIAL_ENDED -> R.string.r_trial_ended
            UNEXPECTED_FEE -> R.string.r_unexpected_fee
            UNEXPECTED_REFUND -> R.string.r_unexpected_refund
            else -> R.string.r_unknown_card
        }
    )

    /** A plain-language line with the numbers, e.g. "6× the usual amount (usually SR 45)". */
    fun explain(ctx: android.content.Context, f: Finding, who: String?): String {
        fun m(v: Double?) = v?.let { Fmt.whole(it) } ?: "?"
        return when (f.code) {
            UNUSUAL_AMOUNT -> ctx.getString(R.string.x_unusual_amount, "%.1f".format(f.a ?: 0.0), m(f.b))
            LARGE_AMOUNT, LARGE_WITHDRAWAL, LARGE_TRANSFER -> ctx.getString(R.string.x_over_limit, label(ctx, f.code), m(f.a))
            MONTHLY_LIMIT -> ctx.getString(R.string.x_monthly_limit, m(f.b), m(f.a))
            QUIET_HOURS, UNUSUAL_HOUR -> ctx.getString(R.string.x_hour, label(ctx, f.code), "%02d:00".format((f.a ?: 0.0).toInt()))
            NEW_MERCHANT -> ctx.getString(R.string.x_new_merchant, who ?: "")
            DUPLICATE, POSSIBLE_DUPLICATE, DOUBLE_BOOKING -> ctx.getString(R.string.x_duplicate, label(ctx, f.code), minutesText(ctx, f.a ?: 0.0)) +
                (if (f.text == "hold") "\n" + ctx.getString(R.string.x_hold) else "")
            PRICE_UP, TRIAL_ENDED -> ctx.getString(R.string.x_price, label(ctx, f.code), m(f.a), m(f.b))
            WATCH, FOREIGN, OUTSIDE_DEST, OTHER_CITY -> label(ctx, f.code) + (f.text?.let { ": $it" } ?: "")
            UNEXPECTED_FEE -> label(ctx, f.code) + (f.text?.let { " (" + feeTypeLabel(ctx, it) + ")" } ?: "")
            else -> label(ctx, f.code)
        }
    }

    fun minutesText(ctx: android.content.Context, minutes: Double): String = when {
        minutes < 60 -> ctx.getString(R.string.x_minutes, minutes.toInt().coerceAtLeast(1))
        minutes < 48 * 60 -> ctx.getString(R.string.x_hours, (minutes / 60).toInt().coerceAtLeast(1))
        else -> ctx.getString(R.string.x_days, (minutes / 1440).toInt())
    }

    fun feeTypeLabel(ctx: android.content.Context, type: String): String = ctx.getString(
        when (type) {
            "annual" -> R.string.fee_annual
            "atm" -> R.string.fee_atm
            "transfer" -> R.string.fee_transfer
            "penalty" -> R.string.fee_penalty
            "interest" -> R.string.fee_interest
            "international" -> R.string.fee_international
            else -> R.string.fee_other
        }
    )

    fun kindLabel(ctx: android.content.Context, kind: String): String = ctx.getString(
        when (kind) {
            Parser.Kind.WITHDRAWAL -> R.string.k_withdrawal
            Parser.Kind.TRANSFER_IN -> R.string.k_transfer_in
            Parser.Kind.TRANSFER_OUT -> R.string.k_transfer_out
            Parser.Kind.REFUND -> R.string.k_refund
            Parser.Kind.CREDIT_IN -> R.string.k_credit_in
            Parser.Kind.BILL -> R.string.k_bill
            Parser.Kind.FEE -> R.string.k_fee
            else -> R.string.k_purchase
        }
    )
}
