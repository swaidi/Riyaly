package app.riyaly

import java.util.Calendar
import kotlin.math.sqrt

/**
 * Pattern checks. Each member's normal behaviour is learned from his or her own history,
 * so the checks become more personal as the history grows. Cards can have their own limits
 * and a travel mode.
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
    const val FOREIGN = "foreign"
    const val OUTSIDE_DEST = "outside_destination"
    const val OTHER_CITY = "other_city"
    const val WATCH = "watch"
    const val FAR = "far"
    const val DECLINED = "declined"
    const val LARGE_TRANSFER = "large_transfer"
    const val NEW_RECIPIENT = "new_recipient"

    val ALL = listOf(
        UNKNOWN_CARD, AMBIGUOUS, LARGE_AMOUNT, LARGE_WITHDRAWAL, MONTHLY_LIMIT, UNUSUAL_AMOUNT, NEW_MERCHANT, UNUSUAL_HOUR,
        QUIET_HOURS, RAPID, DUPLICATE, FOREIGN, OUTSIDE_DEST, OTHER_CITY, WATCH, FAR, DECLINED, LARGE_TRANSFER, NEW_RECIPIENT,
    )

    /** Checks that travel mode pauses for the card. */
    val TRAVEL_PAUSED = setOf(FOREIGN, OTHER_CITY, FAR)

    data class Config(
        val enabled: (String) -> Boolean = { true },
        val largeAmount: Double = 1000.0,
        val transferLimit: Double = 5000.0,
        val quietStart: Int = 1,
        val quietEnd: Int = 5,
        val homeCity: String = "Riyadh",
        val watch: List<String> = emptyList(),
        val trustedMerchants: Set<String> = emptySet(),
        val cards: Map<String, Card> = emptyMap(),
    )

    private const val MIN_AMOUNT_HISTORY = 8
    private const val MIN_MERCHANT_HISTORY = 10
    private const val MIN_HOUR_HISTORY = 20
    private const val MIN_TRANSFER_HISTORY = 3

    fun hourOf(ts: Long): Int = Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.HOUR_OF_DAY)

    private fun monthStart(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts; set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun same(a: String, b: String): Boolean {
        val x = a.trim().uppercase(); val y = b.trim().uppercase()
        return x.isNotEmpty() && y.isNotEmpty() && (x == y || x.contains(y) || y.contains(x))
    }

    /** Whether the transaction happened at the card's travel destination (unknown place = accepted). */
    fun atDestination(t: Txn, dest: String?): Boolean {
        if (dest.isNullOrBlank()) return true
        val places = listOfNotNull(t.country, t.city)
        if (places.isEmpty()) return true
        return dest.split(",").map { it.trim() }.filter { it.isNotEmpty() }.any { d -> places.any { same(it, d) } }
    }

    /**
     * @param t the new transaction (already identified as far as possible)
     * @param history earlier transactions (any order), not including [t]
     */
    fun check(t: Txn, history: List<Txn>, cfg: Config): List<String> {
        val r = mutableListOf<String>()
        fun add(code: String) { if (cfg.enabled(code)) r += code }
        val card = t.card?.let { cfg.cards[it] }
        val traveling = card?.travelActive(t.ts) == true

        if (t.match == Match.UNKNOWN) add(UNKNOWN_CARD)
        if (t.match == Match.AMBIGUOUS) add(AMBIGUOUS)
        if (t.isDeclined) add(DECLINED)

        val merchantUp = t.merchant?.uppercase().orEmpty()
        val bodyUp = t.body.uppercase()
        if (cfg.watch.any { it.isNotBlank() && (merchantUp.contains(it.uppercase()) || bodyUp.contains(it.uppercase())) }) add(WATCH)

        // Place checks; travel mode pauses them, but a purchase outside the destination still alerts.
        val otherCity = t.city != null && !t.city.equals(cfg.homeCity, ignoreCase = true)
        if (traveling) {
            if ((t.isForeign || otherCity) && !atDestination(t, card?.travelDest)) add(OUTSIDE_DEST)
        } else {
            if (t.isForeign) add(FOREIGN)
            if (otherCity) add(OTHER_CITY)
        }

        if (t.kind in Parser.Kind.INFLOW) return r.distinct()

        val sar = if (t.currency == "SAR") t.amount else null

        // Transfers out: their own limit and "new recipient".
        if (t.kind == Parser.Kind.TRANSFER_OUT) {
            val limit = card?.limitSingle ?: cfg.transferLimit
            if (sar != null && limit > 0 && sar >= limit) add(LARGE_TRANSFER)
            val past = history.filter { it.kind == Parser.Kind.TRANSFER_OUT }
            if (t.counterpartyAcct != null && past.size >= MIN_TRANSFER_HISTORY && past.none { it.counterpartyAcct == t.counterpartyAcct }) add(NEW_RECIPIENT)
            return r.distinct()
        }

        // Limits: per card when set, otherwise the general limit.
        if (sar != null && !t.isDeclined) {
            if (t.kind == Parser.Kind.WITHDRAWAL) {
                val limit = card?.limitWithdrawal ?: card?.limitSingle ?: cfg.largeAmount
                if (limit > 0 && sar >= limit) add(LARGE_WITHDRAWAL)
            } else {
                val limit = card?.limitSingle ?: cfg.largeAmount
                if (limit > 0 && sar >= limit) add(LARGE_AMOUNT)
            }
            val monthly = card?.limitMonthly
            if (monthly != null && monthly > 0) {
                val start = monthStart(t.ts)
                val before = history.filter { it.card == t.card && it.isSpend && it.ts >= start && it.ts < t.ts }.sumOf { it.amount }
                if (before < monthly && before + sar >= monthly) add(MONTHLY_LIMIT)
            }
        }

        val h = hourOf(t.ts)
        if (inRange(h, cfg.quietStart, cfg.quietEnd)) add(QUIET_HOURS)

        // Personal patterns apply to purchases.
        if (t.kind == Parser.Kind.PURCHASE) {
            val trusted = t.merchant != null && t.merchant in cfg.trustedMerchants
            val mine = if (t.memberId != null) history.filter { it.memberId == t.memberId && it.kind == Parser.Kind.PURCHASE } else emptyList()

            val amounts = mine.filter { it.currency == "SAR" && !it.isDeclined }.map { it.amount }
            if (sar != null && amounts.size >= MIN_AMOUNT_HISTORY) {
                val mean = amounts.average()
                val sd = sqrt(amounts.map { (it - mean) * (it - mean) }.average())
                val median = amounts.sorted()[amounts.size / 2]
                if (sar > maxOf(mean + 3 * sd, median * 4) && sar > 100) add(UNUSUAL_AMOUNT)
            }
            if (!trusted && !traveling && t.merchant != null && mine.size >= MIN_MERCHANT_HISTORY &&
                mine.none { it.merchant.equals(t.merchant, ignoreCase = true) }
            ) add(NEW_MERCHANT)
            if (!traveling && mine.size >= MIN_HOUR_HISTORY) {
                val hours = mine.map { hourOf(it.ts) }.toSet()
                if (listOf((h + 23) % 24, h, (h + 1) % 24).none { it in hours }) add(UNUSUAL_HOUR)
            }
        }

        // Rapid repetition on the same card, and duplicates.
        if (t.card != null) {
            val near = history.filter { it.card == t.card && it.ts in (t.ts - 10 * 60_000)..t.ts && it.kind in Parser.Kind.SPEND }
            if (near.size >= 2) add(RAPID)
            val dup = history.any {
                it.card == t.card && it.ts in (t.ts - 5 * 60_000)..t.ts && it.amount == t.amount &&
                    it.merchant.equals(t.merchant, ignoreCase = true) && it.kind == t.kind
            }
            if (dup) add(DUPLICATE)
        }
        return r.distinct()
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
            FOREIGN -> R.string.r_foreign
            OUTSIDE_DEST -> R.string.r_outside_dest
            OTHER_CITY -> R.string.r_other_city
            WATCH -> R.string.r_watch
            FAR -> R.string.r_far
            DECLINED -> R.string.r_declined
            LARGE_TRANSFER -> R.string.r_large_transfer
            NEW_RECIPIENT -> R.string.r_new_recipient
            else -> R.string.r_unknown_card
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
            else -> R.string.k_purchase
        }
    )
}
