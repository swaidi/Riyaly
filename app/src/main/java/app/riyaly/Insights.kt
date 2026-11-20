package app.riyaly

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Calculations behind the dashboard and the Plan tab. Pure Kotlin on lists of transactions,
 * so each one can be unit tested.
 */
object Insights {
    const val DAY = 86_400_000L

    /** Merchant key: one spelling per merchant, so "HungerStation" and "HUNGERSTATION" count together. */
    fun key(merchant: String?): String? = merchant?.trim()?.uppercase()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }

    // ---------- periods ----------
    data class Period(val from: Long, val to: Long) {
        val days: Int get() = ((to - from + DAY - 1) / DAY).toInt().coerceAtLeast(1)
        operator fun contains(ts: Long) = ts in from until to
    }

    fun month(ts: Long, back: Int = 0): Period {
        val c = Calendar.getInstance().apply {
            timeInMillis = ts; set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.MONTH, -back)
        }
        val from = c.timeInMillis
        c.add(Calendar.MONTH, 1)
        return Period(from, c.timeInMillis)
    }

    fun monthKey(ts: Long): String = Calendar.getInstance().apply { timeInMillis = ts }.let { "%04d-%02d".format(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1) }

    /** Salary to salary: from the last salary on or before [now] to the expected next one. */
    fun paydayPeriod(salaryTimes: List<Long>, now: Long): Period? {
        val past = salaryTimes.filter { it <= now }.sorted()
        val last = past.lastOrNull() ?: return null
        val gaps = past.zipWithNext { a, b -> b - a }.filter { it in 20 * DAY..40 * DAY }
        val gap = if (gaps.isEmpty()) 30 * DAY else gaps.sorted()[gaps.size / 2]
        return Period(last, last + gap)
    }

    /** Next payday: the expected next salary, or the day of month the user set. */
    fun nextPayday(salaryTimes: List<Long>, manualDay: Int?, now: Long): Long? {
        paydayPeriod(salaryTimes, now)?.let { if (it.to > now) return it.to }
        if (manualDay == null || manualDay !in 1..31) return null
        val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val today = c.get(Calendar.DAY_OF_MONTH)
        if (manualDay <= today) c.add(Calendar.MONTH, 1)
        c.set(Calendar.DAY_OF_MONTH, manualDay.coerceAtMost(c.getActualMaximum(Calendar.DAY_OF_MONTH)))
        return c.timeInMillis
    }

    // ---------- salary ----------
    private val SALARY_WORDS = listOf("راتب", "رواتب", "salary", "payroll", "wages")

    /** A deposit is salary when its message says so, or when a similar deposit came about a month earlier. */
    fun isSalaryLike(t: Txn, history: List<Txn>): Boolean {
        if (t.kind != Parser.Kind.CREDIT_IN && t.kind != Parser.Kind.TRANSFER_IN) return false
        if (t.internal || t.isDeclined) return false
        val l = t.body.lowercase()
        if (SALARY_WORDS.any { l.contains(it) }) return true
        if (t.amount < 1000) return false
        return history.any {
            it.id != t.id && (it.kind == Parser.Kind.CREDIT_IN || it.kind == Parser.Kind.TRANSFER_IN) && !it.internal &&
                (t.ts - it.ts) in 25 * DAY..35 * DAY && abs(it.amount - t.amount) <= 0.15 * t.amount
        }
    }

    // ---------- money in and out ----------
    data class Flow(val income: Double, val spend: Double, val transfersOut: Double, val fees: Double) {
        val out: Double get() = spend + transfersOut
        val net: Double get() = income - out
        /** Share of income kept, or null when there is no income. */
        val savingRate: Double? get() = if (income > 0) net / income else null
    }

    fun flow(txns: List<Txn>, p: Period): Flow {
        val inP = txns.filter { it.ts in p }
        return Flow(
            income = inP.filter { it.isMoneyIn }.sumOf { it.amount },
            spend = inP.filter { it.isSpend }.sumOf { it.netSpend },
            transfersOut = inP.filter { it.isTransferOut }.sumOf { it.amount },
            fees = inP.sumOf { it.feesPaid },
        )
    }

    data class IncomeSources(val salary: Double, val people: Double, val refunds: Double, val other: Double)

    fun incomeSources(txns: List<Txn>, p: Period): IncomeSources {
        val inP = txns.filter { it.ts in p && !it.isDeclined && it.currency == "SAR" }
        return IncomeSources(
            salary = inP.filter { it.isMoneyIn && it.incomeType == "salary" }.sumOf { it.amount },
            people = inP.filter { it.isMoneyIn && it.incomeType != "salary" && it.kind == Parser.Kind.TRANSFER_IN }.sumOf { it.amount },
            refunds = inP.filter { it.isRefund }.sumOf { it.amount },
            other = inP.filter { it.isMoneyIn && it.incomeType != "salary" && it.kind != Parser.Kind.TRANSFER_IN }.sumOf { it.amount },
        )
    }

    /** Pace: the period's projected total from what has been spent so far. */
    fun projected(soFar: Double, p: Period, now: Long): Double {
        val elapsed = ((now - p.from).toDouble() / DAY).coerceAtLeast(1.0)
        val total = ((p.to - p.from).toDouble() / DAY)
        return if (elapsed >= total) soFar else soFar / elapsed * total
    }

    // ---------- this month vs normal ----------
    data class Compare(
        val soFar: Double, val lastSameDay: Double, val normalSameDay: Double, val projected: Double, val normalMonth: Double,
    ) {
        val vsLast: Double? get() = if (lastSameDay > 0) (soFar - lastSameDay) / lastSameDay else null
        val vsNormal: Double? get() = if (normalSameDay > 0) (soFar - normalSameDay) / normalSameDay else null
        val projectedVsNormal: Double? get() = if (normalMonth > 0) (projected - normalMonth) / normalMonth else null
    }

    /** This month so far against the same number of days in earlier months (fair at any point in the month). */
    fun compare(spends: List<Txn>, now: Long, excludedMonths: Set<String> = emptySet(), months: Int = 3): Compare {
        val cur = month(now)
        val elapsed = now - cur.from
        val soFar = spends.filter { it.ts in cur.from..now }.sumOf { it.netSpend }
        val last = month(now, 1)
        val lastSameDay = spends.filter { it.ts >= last.from && it.ts < last.from + elapsed }.sumOf { it.netSpend }
        val prev = (1..months + 3).map { month(now, it) }.filter { monthKey(it.from) !in excludedMonths }.take(months)
        val normalSameDay = if (prev.isEmpty()) 0.0 else prev.map { m -> spends.filter { it.ts >= m.from && it.ts < m.from + elapsed }.sumOf { it.netSpend } }.average()
        val normalMonth = if (prev.isEmpty()) 0.0 else prev.map { m -> spends.filter { it.ts in m }.sumOf { it.netSpend } }.average()
        return Compare(soFar, lastSameDay, normalSameDay, projected(soFar, cur, now), normalMonth)
    }

    /** Change per group (category, person…) this month so far against the normal for the same days. */
    fun changes(spends: List<Txn>, now: Long, groupOf: (Txn) -> String, excludedMonths: Set<String> = emptySet()): List<Triple<String, Double, Double>> {
        val cur = month(now)
        val elapsed = now - cur.from
        val prev = (1..6).map { month(now, it) }.filter { monthKey(it.from) !in excludedMonths }.take(3)
        val nowBy = spends.filter { it.ts in cur.from..now }.groupBy(groupOf).mapValues { e -> e.value.sumOf { it.netSpend } }
        val normBy = HashMap<String, Double>()
        for (m in prev) spends.filter { it.ts >= m.from && it.ts < m.from + elapsed }.groupBy(groupOf)
            .forEach { (k, l) -> normBy[k] = (normBy[k] ?: 0.0) + l.sumOf { it.netSpend } / prev.size.coerceAtLeast(1) }
        return (nowBy.keys + normBy.keys).map { k -> Triple(k, nowBy[k] ?: 0.0, normBy[k] ?: 0.0) }
            .sortedByDescending { abs(it.second - it.third) }
    }

    // ---------- recurring payments ----------
    data class Recurring(
        val key: String, val name: String, val periodDays: Int, val lastAmount: Double, val avgAmount: Double,
        val lastTs: Long, val nextTs: Long, val cards: Set<String>, val members: Set<Long>, val count: Int,
        val foreign: Boolean, val feesPerYear: Double,
    ) {
        val yearly: Double get() = avgAmount * 365.0 / periodDays
        val monthly: Double get() = avgAmount * 30.4 / periodDays
    }

    private val BUCKETS = listOf(7 to 6..8, 14 to 13..16, 30 to 26..35, 91 to 82..100, 365 to 345..385)

    private fun bucket(days: Double): Int? = BUCKETS.firstOrNull { days.roundToInt() in it.second }?.first

    /**
     * Payments that repeat on a rhythm (weekly, monthly, quarterly, yearly) with a similar amount.
     * [manual]: a period set by the user (days), or 0 for "not recurring".
     */
    fun recurring(txns: List<Txn>, keyOf: (Txn) -> String?, manual: Map<String, Int> = emptyMap(), now: Long = System.currentTimeMillis()): List<Recurring> {
        val groups = txns.filter { !it.isDeclined && it.kind in setOf(Parser.Kind.PURCHASE, Parser.Kind.BILL) }
            .groupBy { keyOf(it) }.filterKeys { it != null }
        val out = mutableListOf<Recurring>()
        for ((k, list0) in groups) {
            val key = k!!
            val list = list0.sortedBy { it.ts }
            val m = manual[key]
            if (m == 0) continue
            val period: Int = if (m != null) m else {
                if (list.size < 2) continue
                val gaps = list.zipWithNext { a, b -> (b.ts - a.ts).toDouble() / DAY }
                val bs = gaps.mapNotNull { bucket(it) }
                val top = bs.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: continue
                val billLike = list.last().kind == Parser.Kind.BILL
                val enough = (top.value >= 2 && top.value >= gaps.size * 0.6) || (billLike && top.value >= 1)
                if (!enough) continue
                val amounts = list.map { it.amount }.sorted()
                val median = amounts[amounts.size / 2]
                if (amounts.count { abs(it - median) <= 0.1 * median } < amounts.size * 0.6) continue
                top.key
            }
            val recent = list.takeLast(4)
            val last = list.last()
            out += Recurring(
                key = key, name = last.merchant ?: key, periodDays = period, lastAmount = last.amount,
                avgAmount = recent.map { it.amount }.average(), lastTs = last.ts, nextTs = last.ts + period * DAY,
                cards = list.mapNotNull { it.card }.toSet(), members = list.mapNotNull { it.memberId }.toSet(), count = list.size,
                foreign = list.any { it.isForeign }, feesPerYear = recent.map { it.feesPaid }.average() * 365.0 / period,
            )
        }
        return out.sortedBy { it.nextTs }
    }

    /** Median minutes between purchases at one merchant, or null with too little history. */
    fun usualGapMinutes(times: List<Long>): Double? {
        val s = times.sorted()
        if (s.size < 3) return null
        val gaps = s.zipWithNext { a, b -> (b - a).toDouble() / 60_000 }.sorted()
        return gaps[gaps.size / 2]
    }

    // ---------- small purchases ----------
    data class Habit(val group: String, val count: Int, val total: Double, val yearPace: Double)

    fun habits(spends: List<Txn>, p: Period, now: Long, small: Double, groupOf: (Txn) -> String): List<Habit> {
        val inP = spends.filter { it.ts in p && it.kind == Parser.Kind.PURCHASE && it.netSpend in 0.01..small }
        val elapsedDays = ((minOf(now, p.to) - p.from).toDouble() / DAY).coerceAtLeast(1.0)
        return inP.groupBy(groupOf).map { (g, l) ->
            val total = l.sumOf { it.netSpend }
            Habit(g, l.size, total, total / elapsedDays * 365)
        }.sortedByDescending { it.total }
    }

    // ---------- safe to spend ----------
    data class Safe(val balance: Double, val payday: Long, val daysLeft: Int, val billsDue: Double, val setAside: Double) {
        val available: Double get() = balance - billsDue - setAside
        val daily: Double get() = available / daysLeft
    }

    fun safeToSpend(balance: Double, payday: Long, now: Long, upcoming: List<Recurring>, setAside: Double): Safe {
        val daysLeft = ((payday - now + DAY - 1) / DAY).toInt().coerceAtLeast(1)
        val bills = upcoming.filter { it.nextTs in now until payday }.sumOf { it.lastAmount }
        return Safe(balance, payday, daysLeft, bills, setAside)
    }

    // ---------- needs, wants ----------
    val NEEDS = setOf(Categories.GROCERIES, Categories.BILLS, Categories.FUEL, Categories.PHARMACY, Categories.TRANSPORT, Categories.TELECOM, Categories.FEES)

    fun bucketOf(category: String, overrides: Map<String, String>): String = overrides[category] ?: if (category in NEEDS) "need" else "want"

    // ---------- fees ----------
    /** Fee cost per SAR spent abroad, per card: (fees + exchange cost) / foreign spending. */
    fun foreignFeeRateByCard(txns: List<Txn>): Map<String, Double> =
        txns.filter { it.isForeign && it.isSpend && it.card != null && it.amount > 0 }.groupBy { it.card!! }
            .filterValues { it.size >= 2 }
            .mapValues { (_, l) -> l.sumOf { (it.fees ?: 0.0) + (it.fxExtra ?: 0.0) } / l.sumOf { it.amount } }
}
