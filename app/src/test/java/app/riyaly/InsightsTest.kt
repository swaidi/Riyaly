package app.riyaly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class InsightsTest {
    private val day = Insights.DAY
    private val now: Long = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 20, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private var nextId = 1L

    private fun t(
        amount: Double, ts: Long, kind: String = Parser.Kind.PURCHASE, merchant: String? = "SHOP", card: String = "1111",
        body: String = "", internal: Boolean = false, refunded: Double = 0.0, incomeType: String? = null, fees: Double? = null,
    ) = Txn(
        id = nextId++, smsHash = "", sender = "BANK", bankId = 1, body = body, ts = ts, amount = amount, currency = "SAR",
        balance = null, merchant = merchant, method = null, type = null, isCredit = kind in Parser.Kind.INFLOW, isDeclined = false,
        isForeign = false, card = card, memberId = 1, match = Match.IDENTIFIED, candidates = emptyList(), alert = AlertState.NONE,
        reasons = emptyList(), city = null, distanceKm = null, kind = kind, internal = internal, refunded = refunded, incomeType = incomeType, fees = fees,
    )

    @Test fun flowCountsRefundsAndExcludesInternal() {
        val p = Insights.month(now)
        val txns = listOf(
            t(1000.0, now - day, refunded = 400.0),
            t(5000.0, now - 2 * day, Parser.Kind.TRANSFER_IN, incomeType = "salary"),
            t(300.0, now - 2 * day, Parser.Kind.TRANSFER_OUT, internal = true),
            t(300.0, now - 2 * day, Parser.Kind.TRANSFER_IN, internal = true),
            t(25.0, now - 3 * day, Parser.Kind.FEE),
        )
        val f = Insights.flow(txns, p)
        assertEquals(5000.0, f.income, 0.01)
        assertEquals(625.0, f.spend, 0.01) // 600 net purchase + 25 fee
        assertEquals(0.0, f.transfersOut, 0.01)
        assertEquals(25.0, f.fees, 0.01)
        assertEquals((5000 - 625) / 5000.0, f.savingRate!!, 0.001)
    }

    @Test fun salaryByWordsOrMonthlySimilarDeposit() {
        assertTrue(Insights.isSalaryLike(t(9000.0, now, Parser.Kind.CREDIT_IN, body = "إيداع راتب"), emptyList()))
        val prev = t(9000.0, now - 30 * day, Parser.Kind.CREDIT_IN)
        assertTrue(Insights.isSalaryLike(t(9500.0, now, Parser.Kind.CREDIT_IN), listOf(prev)))
        assertFalse(Insights.isSalaryLike(t(14000.0, now, Parser.Kind.CREDIT_IN), listOf(prev)))
        assertFalse(Insights.isSalaryLike(t(9000.0, now, Parser.Kind.TRANSFER_IN, internal = true), listOf(prev)))
    }

    @Test fun monthlySubscriptionDetected() {
        val txns = (0..3).map { t(45.0, now - (it * 30 + 2) * day, merchant = "STREAMING") } + t(12.0, now - day, merchant = "COFFEE")
        val rec = Insights.recurring(txns, { Insights.key(it.merchant) }, now = now)
        assertEquals(1, rec.size)
        assertEquals(30, rec[0].periodDays)
        assertEquals(45.0 * 365 / 30, rec[0].yearly, 0.5)
    }

    @Test fun weeklyAndManualRecurring() {
        val weekly = (0..3).map { t(20.0, now - (it * 7 + 1) * day, merchant = "GYM") }
        assertEquals(7, Insights.recurring(weekly, { Insights.key(it.merchant) }, now = now).single().periodDays)
        // The user marks it "not recurring".
        assertTrue(Insights.recurring(weekly, { Insights.key(it.merchant) }, mapOf("GYM" to 0), now).isEmpty())
    }

    @Test fun dailyCoffeeIsNotASubscriptionWithVaryingAmounts() {
        val coffee = (0..9).map { t(listOf(14.0, 18.0, 22.0, 9.0)[it % 4], now - it * day, merchant = "COFFEE") }
        assertTrue(Insights.recurring(coffee, { Insights.key(it.merchant) }, now = now).isEmpty())
    }

    @Test fun fairComparisonSameDayLastMonth() {
        val m = Insights.month(now)
        val last = Insights.month(now, 1)
        val txns = listOf(
            t(500.0, m.from + day), // this month so far
            t(400.0, last.from + day), // same days last month
            t(2000.0, last.from + 25 * day), // later last month: not counted in "same day"
        )
        val c = Insights.compare(txns, now)
        assertEquals(500.0, c.soFar, 0.01)
        assertEquals(400.0, c.lastSameDay, 0.01)
        assertEquals(0.25, c.vsLast!!, 0.001)
    }

    @Test fun excludedMonthsLeftOutOfNormal() {
        val last = Insights.month(now, 1)
        val txns = listOf(t(9000.0, last.from + day))
        val withIt = Insights.compare(txns, now)
        val without = Insights.compare(txns, now, setOf(Insights.monthKey(last.from)))
        assertTrue(withIt.normalSameDay > without.normalSameDay)
    }

    @Test fun safeToSpendSubtractsBillsBeforePayday() {
        val rec = Insights.Recurring("BILL", "BILL", 30, 300.0, 300.0, now - 25 * day, now + 5 * day, setOf("1111"), setOf(1L), 3, false, 0.0)
        val s = Insights.safeToSpend(3300.0, now + 10 * day, now, listOf(rec), 1000.0)
        assertEquals(10, s.daysLeft)
        assertEquals(300.0, s.billsDue, 0.01)
        assertEquals(2000.0, s.available, 0.01)
        assertEquals(200.0, s.daily, 0.01)
    }

    @Test fun nextPaydayFromSalaryOrManualDay() {
        val salaries = listOf(now - 40 * day, now - 10 * day)
        val next = Insights.nextPayday(salaries, null, now)
        assertNotNull(next)
        assertTrue(next!! > now)
        assertNull(Insights.nextPayday(emptyList(), null, now))
        val manual = Insights.nextPayday(emptyList(), 27, now)!!
        assertEquals(27, Calendar.getInstance().apply { timeInMillis = manual }.get(Calendar.DAY_OF_MONTH))
    }

    @Test fun smallPurchasesAddUp() {
        val m = Insights.month(now)
        val txns = (1..10).map { t(15.0, m.from + it * 3600_000L, merchant = "COFFEE") } + t(400.0, m.from + day, merchant = "MALL")
        val h = Insights.habits(txns, m, now, 50.0) { it.merchant!! }
        assertEquals(1, h.size)
        assertEquals(10, h[0].count)
        assertEquals(150.0, h[0].total, 0.01)
        assertTrue(h[0].yearPace > 150.0)
    }

    @Test fun usualGap() {
        val times = (0..5).map { now - it * day }
        assertEquals(24 * 60.0, Insights.usualGapMinutes(times)!!, 1.0)
        assertNull(Insights.usualGapMinutes(times.take(2)))
    }

    @Test fun needsAndWants() {
        assertEquals("need", Insights.bucketOf(Categories.GROCERIES, emptyMap()))
        assertEquals("want", Insights.bucketOf(Categories.RESTAURANTS, emptyMap()))
        assertEquals("need", Insights.bucketOf(Categories.RESTAURANTS, mapOf(Categories.RESTAURANTS to "need")))
    }

    @Test fun marketRateExtraCost() {
        val rates = mapOf("USD" to 1.0, "EUR" to 0.9, "SAR" to 3.75)
        assertEquals(41.667, Fx.marketSar(10.0, "EUR", rates)!!, 0.01)
        val foreign = t(58.0, now, fees = 1.30).copy(origAmount = 15.0, origCurrency = "USD", isForeign = true)
        // charged without fees 56.70, market 56.25 → 0.45
        assertEquals(0.45, Fx.extraCost(foreign, rates)!!, 0.01)
    }
}
