package app.riyaly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** The new alert rules: duplicates with the usual gap, card testing, risk levels, recurring changes. */
class RulesTest {
    private val day = Insights.DAY
    private val min = 60_000L
    private val noon: Long = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 18, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private var nextId = 1L

    private fun t(
        amount: Double, ts: Long, merchant: String? = "SHOP", card: String = "1111", declined: Boolean = false,
        kind: String = Parser.Kind.PURCHASE, member: Long = 1, body: String = "",
    ) = Txn(
        id = nextId++, smsHash = "", sender = "BANK", bankId = 1, body = body, ts = ts, amount = amount, currency = "SAR",
        balance = null, merchant = merchant, method = null, type = null, isCredit = false, isDeclined = declined,
        isForeign = false, card = card, memberId = member, match = Match.IDENTIFIED, candidates = emptyList(), alert = AlertState.NONE,
        reasons = emptyList(), city = null, distanceKm = null, kind = kind,
    )

    private val cfg = Analyzer.Config(cards = mapOf("1111" to Card("1111", 1), "2222" to Card("2222", 2)), quietStart = 1, quietEnd = 5)

    @Test fun sameAmountWithinTenMinutesIsHighDuplicate() {
        val first = t(89.0, noon)
        val codes = Analyzer.check(t(89.0, noon + 4 * min), listOf(first), cfg)
        assertTrue(Analyzer.DUPLICATE in codes)
        assertEquals(Risk.HIGH, Analyzer.riskOf(codes))
    }

    @Test fun dailyCoffeeIsNotADuplicate() {
        // Coffee every morning: the usual gap is a day, so a day later is normal.
        val history = (1..6).map { t(16.0, noon - it * day, merchant = "COFFEE") }
        val codes = Analyzer.check(t(16.0, noon, merchant = "COFFEE"), history, cfg)
        assertFalse(codes.any { it == Analyzer.DUPLICATE || it == Analyzer.POSSIBLE_DUPLICATE })
    }

    @Test fun weeklySubscriptionChargedAgainAfterTwoDaysIsPossibleDuplicate() {
        val history = (1..4).map { t(29.0, noon - (it * 7 + 2) * day, merchant = "WEEKLYBOX") } + t(29.0, noon - 2 * day, merchant = "WEEKLYBOX")
        val codes = Analyzer.check(t(29.0, noon, merchant = "WEEKLYBOX"), history, cfg)
        assertTrue(Analyzer.POSSIBLE_DUPLICATE in codes)
        assertEquals(Risk.MEDIUM, Analyzer.riskOf(codes - Analyzer.NEW_MERCHANT))
    }

    @Test fun repeatsNormalMerchantIsSkipped() {
        val history = listOf(t(16.0, noon - 3 * 3600_000L, merchant = "COFFEE"))
        val c2 = cfg.copy(repeatsNormal = setOf("COFFEE"))
        assertFalse(Analyzer.POSSIBLE_DUPLICATE in Analyzer.check(t(16.0, noon, merchant = "COFFEE"), history, c2))
    }

    @Test fun sameBookingOnTwoFamilyCards() {
        val other = t(640.0, noon - 20 * min, merchant = "HOTEL BOOK", card = "2222", member = 2)
        assertTrue(Analyzer.DOUBLE_BOOKING in Analyzer.check(t(640.0, noon, merchant = "HOTEL BOOK"), listOf(other), cfg))
    }

    @Test fun cardTesting() {
        val history = listOf(t(1.0, noon - 5 * min, merchant = "X"))
        assertTrue(Analyzer.CARD_TESTING in Analyzer.check(t(900.0, noon, merchant = "Y"), history, cfg))
        val declined = listOf(t(300.0, noon - 6 * min, declined = true), t(300.0, noon - 3 * min, declined = true))
        assertTrue(Analyzer.CARD_TESTING in Analyzer.check(t(300.0, noon, merchant = "Z"), declined, cfg))
    }

    @Test fun cancelledButCharged() {
        val c2 = cfg.copy(cancelled = setOf("STREAMING"))
        val codes = Analyzer.check(t(45.0, noon, merchant = "STREAMING"), emptyList(), c2)
        assertTrue(Analyzer.CANCELLED_CHARGED in codes)
        assertEquals(Risk.HIGH, Analyzer.riskOf(codes))
    }

    @Test fun priceIncreaseIsLowOnly() {
        val rec = Insights.Recurring("STREAMING", "STREAMING", 30, 45.0, 45.0, noon - 30 * day, noon, setOf("1111"), setOf(1L), 3, false, 0.0)
        val history = (1..3).map { t(45.0, noon - it * 30 * day, merchant = "STREAMING") }
        val codes = Analyzer.check(t(55.0, noon, merchant = "STREAMING"), history, cfg.copy(recurring = mapOf("STREAMING" to rec)))
        assertTrue(Analyzer.PRICE_UP in codes)
        assertEquals(Risk.LOW, Analyzer.riskOf(listOf(Analyzer.PRICE_UP)))
    }

    @Test fun freeTrialEnded() {
        val history = listOf(t(1.0, noon - 7 * day, merchant = "APPTRIAL"))
        assertTrue(Analyzer.TRIAL_ENDED in Analyzer.check(t(39.0, noon, merchant = "APPTRIAL"), history, cfg))
    }

    @Test fun trustedOnlyForThatPerson() {
        val history = (1..12).map { t(50.0, noon - it * day, merchant = "S$it") }
        val c2 = cfg.copy(trustedFor = { if (it == 1L) setOf("NEWSHOP") else emptySet() })
        assertFalse(Analyzer.NEW_MERCHANT in Analyzer.check(t(50.0, noon, merchant = "NEWSHOP"), history, c2))
        val forOther = history.map { it.copy(memberId = 2, card = "2222") }
        assertTrue(Analyzer.NEW_MERCHANT in Analyzer.check(t(50.0, noon, merchant = "NEWSHOP", card = "2222", member = 2), forOther, c2))
    }

    @Test fun feeIsFlaggedAndCountedAsSpending() {
        val fee = t(115.0, noon, merchant = null, kind = Parser.Kind.FEE)
        assertTrue(Analyzer.UNEXPECTED_FEE in Analyzer.check(fee, emptyList(), cfg))
        assertTrue(fee.isSpend)
        assertEquals(115.0, fee.feesPaid, 0.01)
    }

    @Test fun internalTransferNotLargeTransfer() {
        val tr = t(9000.0, noon, merchant = null, kind = Parser.Kind.TRANSFER_OUT).copy(internal = true, counterpartyAcct = "2222")
        assertFalse(Analyzer.LARGE_TRANSFER in Analyzer.check(tr, emptyList(), cfg))
    }
}
