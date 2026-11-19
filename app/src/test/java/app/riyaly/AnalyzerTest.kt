package app.riyaly

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AnalyzerTest {
    private val day = 24L * 3600 * 1000
    private val noon: Long = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 18, 12, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun txn(
        amount: Double, card: String = "4567", kind: String = Parser.Kind.PURCHASE, ts: Long = noon,
        country: String? = null, foreign: Boolean = false, counterpartyAcct: String? = null,
    ) = Txn(
        id = 0, smsHash = "", sender = "AlRajhiBank", bankId = 1, body = "", ts = ts, amount = amount, currency = "SAR",
        balance = null, merchant = "SHOP", method = null, type = null, isCredit = kind in Parser.Kind.INFLOW, isDeclined = false,
        isForeign = foreign, card = card, memberId = 1, match = Match.IDENTIFIED, candidates = emptyList(), alert = AlertState.NONE,
        reasons = emptyList(), city = null, distanceKm = null, kind = kind, country = country, counterpartyAcct = counterpartyAcct,
    )

    private fun cfg(card: Card) = Analyzer.Config(cards = mapOf(card.last4 to card), largeAmount = 1000.0, quietStart = 1, quietEnd = 5)

    @Test fun travelPausesForeignAlert() {
        val card = Card("4567", 1, travelFrom = noon - 2 * day, travelTo = noon + 2 * day)
        val r = Analyzer.check(txn(50.0, country = "USA", foreign = true), emptyList(), cfg(card))
        assertFalse(Analyzer.FOREIGN in r)
        assertFalse(Analyzer.OUTSIDE_DEST in r)
    }

    @Test fun travelDestinationMismatchAlerts() {
        val card = Card("4567", 1, travelFrom = noon - day, travelTo = noon + day, travelDest = "UK")
        val r = Analyzer.check(txn(50.0, country = "USA", foreign = true), emptyList(), cfg(card))
        assertTrue(Analyzer.OUTSIDE_DEST in r)
        assertTrue(Analyzer.check(txn(50.0, country = "UK", foreign = true), emptyList(), cfg(card)).none { it == Analyzer.OUTSIDE_DEST })
    }

    @Test fun foreignAlertWithoutTravel() {
        val r = Analyzer.check(txn(50.0, country = "USA", foreign = true), emptyList(), cfg(Card("4567", 1)))
        assertTrue(Analyzer.FOREIGN in r)
    }

    @Test fun travelEndsAfterEndDate() {
        val card = Card("4567", 1, travelFrom = noon - 5 * day, travelTo = noon - 2 * day)
        assertTrue(Analyzer.FOREIGN in Analyzer.check(txn(50.0, country = "USA", foreign = true), emptyList(), cfg(card)))
    }

    @Test fun perCardSingleLimit() {
        val card = Card("4567", 1, limitSingle = 300.0)
        assertTrue(Analyzer.LARGE_AMOUNT in Analyzer.check(txn(350.0), emptyList(), cfg(card)))
        assertFalse(Analyzer.LARGE_AMOUNT in Analyzer.check(txn(250.0), emptyList(), cfg(card)))
        // Without its own limit the general SR 1,000 applies.
        assertFalse(Analyzer.LARGE_AMOUNT in Analyzer.check(txn(350.0), emptyList(), cfg(Card("4567", 1))))
    }

    @Test fun withdrawalLimit() {
        val card = Card("7890", 1, limitWithdrawal = 3000.0)
        assertFalse(Analyzer.LARGE_WITHDRAWAL in Analyzer.check(txn(2800.0, card = "7890", kind = Parser.Kind.WITHDRAWAL), emptyList(), cfg(card)))
        assertTrue(Analyzer.LARGE_WITHDRAWAL in Analyzer.check(txn(3200.0, card = "7890", kind = Parser.Kind.WITHDRAWAL), emptyList(), cfg(card)))
    }

    @Test fun monthlyLimitAlertsOnceWhenCrossed() {
        val card = Card("4567", 1, limitMonthly = 1000.0)
        val history = listOf(txn(600.0, ts = noon - 3 * 3600_000), txn(300.0, ts = noon - 2 * 3600_000))
        assertTrue(Analyzer.MONTHLY_LIMIT in Analyzer.check(txn(150.0), history, cfg(card)))
        val after = history + txn(150.0, ts = noon - 3600_000)
        assertFalse(Analyzer.MONTHLY_LIMIT in Analyzer.check(txn(50.0), after, cfg(card)))
    }

    @Test fun transferInHasNoSpendAlerts() {
        val r = Analyzer.check(txn(5000.0, card = "5678", kind = Parser.Kind.TRANSFER_IN), emptyList(), cfg(Card("5678", 1)))
        assertTrue(r.isEmpty())
    }

    @Test fun largeTransferOutAndNewRecipient() {
        val card = Card("6789", 1)
        val past = (1..3).map { txn(100.0, card = "6789", kind = Parser.Kind.TRANSFER_OUT, ts = noon - it * day, counterpartyAcct = "1111") }
        val r = Analyzer.check(txn(6000.0, card = "6789", kind = Parser.Kind.TRANSFER_OUT, counterpartyAcct = "9012"), past, cfg(card))
        assertTrue(Analyzer.LARGE_TRANSFER in r)
        assertTrue(Analyzer.NEW_RECIPIENT in r)
        val known = Analyzer.check(txn(100.0, card = "6789", kind = Parser.Kind.TRANSFER_OUT, counterpartyAcct = "1111"), past, cfg(card))
        assertTrue(known.isEmpty())
    }
}
