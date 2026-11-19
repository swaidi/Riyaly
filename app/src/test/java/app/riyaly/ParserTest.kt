package app.riyaly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {
    private val cards = setOf("1234", "2345", "3456")

    @Test fun alRajhiPurchase() {
        val sms = "شراء إنترنت بـSR 37.62 \nعبر1234;فيزا-ابل باي\nلـHUNGERSTA\nرصيد:120.40 SR\n؜18/11/26 8:45"
        val r = Parser.parse(sms, cards)
        assertTrue(r.isTransaction)
        assertEquals(37.62, r.amount!!, 0.001)
        assertEquals("SAR", r.currency)
        assertEquals(120.40, r.balance!!, 0.001)
        assertEquals(listOf("1234"), r.matchedCards)
        assertEquals("HUNGERSTA", r.merchant)
        assertEquals("فيزا-ابل باي", r.method)
        assertEquals("شراء إنترنت", r.type)
        assertFalse(r.isForeign)
    }

    @Test fun amountEqualsCardDigits_sameCard() {
        val sms = "شراء بـSR 1234 \nعبر1234;مدى\nلـZARA\nرصيد:900 SR\n18/11/26 8:45"
        val r = Parser.parse(sms, cards)
        assertEquals(1234.0, r.amount!!, 0.001)
        assertEquals(listOf("1234"), r.matchedCards)
    }

    @Test fun amountEqualsOtherRegisteredCard() {
        // Amount 1234 must NOT be read as card 1234; the card used is 2345.
        val sms = "شراء بـSR 1234 \nعبر2345;مدى\nلـZARA NAKHEEL\nرصيد:1234 SR\n18/11/26 21:10"
        val r = Parser.parse(sms, cards)
        assertEquals(listOf("2345"), r.matchedCards)
        assertEquals(1234.0, r.balance!!, 0.001)
        assertEquals("ZARA NAKHEEL", r.merchant)
    }

    @Test fun balanceWithoutCurrency() {
        val sms = "شراء بـSR 50.00\nعبر3456;مدى\nلـPANDA\nرصيد:1234\n18/11/26 9:00"
        val r = Parser.parse(sms, cards)
        assertEquals(listOf("3456"), r.matchedCards)
        assertEquals(1234.0, r.balance!!, 0.001)
    }

    @Test fun arabicIndicDigits() {
        val sms = "شراء بـSR ٤٥٫٥٠\nعبر١٢٣٤;مدى\nلـJAHEZ"
        val r = Parser.parse(sms, cards)
        assertEquals(45.5, r.amount!!, 0.001)
        assertEquals(listOf("1234"), r.matchedCards)
    }

    @Test fun cardAnywhereInDifferentWording() {
        val sms = "Purchase of SAR 120.00 with card *2345 At: ZARA NAKHEEL MALL RIYADH on 18/11/2026 21:15"
        val r = Parser.parse(sms, cards)
        assertEquals(listOf("2345"), r.matchedCards)
        assertEquals(120.0, r.amount!!, 0.001)
        assertTrue(r.cities.contains("Riyadh"))
    }

    @Test fun unknownCard() {
        val sms = "شراء بـSR 10\nعبر7777;مدى\nلـKFC"
        val r = Parser.parse(sms, cards)
        assertTrue(r.matchedCards.isEmpty())
        assertEquals(listOf("7777"), r.unknownCards)
    }

    @Test fun foreignCurrency() {
        val sms = "شراء إنترنت بـUSD 25.00\nعبر1234;فيزا\nلـAMAZON.COM"
        val r = Parser.parse(sms, cards)
        assertTrue(r.isForeign)
        assertEquals("USD", r.currency)
    }

    @Test fun refundIsCredit() {
        val sms = "استرداد مبلغ SR 37.62\nعبر1234\nمن HUNGERSTA"
        val r = Parser.parse(sms, cards)
        assertTrue(r.isCredit)
    }

    @Test fun twoRegisteredCards() {
        val sms = "تحويل بين البطاقات SR 100 من 1234 إلى 2345"
        val r = Parser.parse(sms, cards)
        assertEquals(listOf("1234", "2345"), r.matchedCards)
    }

    @Test fun notATransaction() {
        val r = Parser.parse("رمز التحقق 2345 لا تشاركه مع أحد", cards)
        assertFalse(r.isTransaction)
    }

    // ---------- Al Rajhi formats sent by the user (names replaced) ----------

    @Test fun transferIn() {
        val sms = "حوالة داخلية واردة بـSR 250\nلـ5678\nمن8901;NAME A\n26/11/19 09:36"
        val r = Parser.parse(sms, setOf("5678"))
        assertEquals(Parser.Kind.TRANSFER_IN, r.kind)
        assertTrue(r.isCredit)
        assertEquals(250.0, r.amount!!, 0.001)
        assertEquals(listOf("5678"), r.matchedCards)
        assertTrue(r.unknownCards.isEmpty())
        assertEquals("8901", r.counterpartyAcct)
        assertEquals("NAME A", r.counterpartyName)
        assertEquals(null, r.merchant)
    }

    @Test fun transferInUnregisteredAccount() {
        val sms = "حوالة داخلية واردة بـSR 250\nلـ5678\nمن8901;NAME A\n26/11/19 09:36"
        val r = Parser.parse(sms, emptySet())
        assertEquals(listOf("5678"), r.unknownCards) // the sender's 8901 is never treated as ours
    }

    @Test fun transferOut() {
        val sms = "حوالة داخلية صادرة بـSR 1200\nمن6789\nلـ9012;NAME B\n26/11/18 19:57"
        val r = Parser.parse(sms, setOf("6789"))
        assertEquals(Parser.Kind.TRANSFER_OUT, r.kind)
        assertFalse(r.isCredit)
        assertEquals(1200.0, r.amount!!, 0.001)
        assertEquals(listOf("6789"), r.matchedCards)
        assertEquals("9012", r.counterpartyAcct)
        assertEquals("NAME B", r.counterpartyName)
        assertEquals("حوالة داخلية صادرة", r.type)
    }

    @Test fun declinedLabeled() {
        val sms = "تم رفض العملية: الرصيد غير كافٍ.\nالعملية: شراء انترنت\nالبطاقة: 1234\nالمبلغ: SAR 42\nالحساب: noon.com\nالتاريخ: 18/11/26 17:53"
        val r = Parser.parse(sms, cards)
        assertTrue(r.isDeclined)
        assertEquals("الرصيد غير كافٍ", r.declineReason)
        assertEquals("شراء انترنت", r.type)
        assertEquals(listOf("1234"), r.matchedCards)
        assertEquals(42.0, r.amount!!, 0.001)
        assertEquals("noon.com", r.merchant)
        assertEquals(Parser.Kind.PURCHASE, r.kind)
    }

    @Test fun posPurchaseLabeled() {
        val sms = "شراء عبر نقاط البيع \nبطاقة:1234 ;فيزا-ابل باي\nلدى:Half Mill\nمبلغ:24 SAR\nرصيد:88.10 SAR\n\u061C18/11/26 12:17"
        val r = Parser.parse(sms, cards)
        assertEquals(listOf("1234"), r.matchedCards)
        assertEquals("Half Mill", r.merchant)
        assertEquals("فيزا-ابل باي", r.method)
        assertEquals(24.0, r.amount!!, 0.001)
        assertEquals(88.10, r.balance!!, 0.001)
        assertEquals("شراء عبر نقاط البيع", r.type)
        assertFalse(r.isForeign)
    }

    @Test fun internationalWithFeesAndTotal() {
        val sms = "شراء دولي\nبطاقة:4567 ;فيزا\nمبلغ: 15 USD(56.31 ريال)\nلدى:WEBSERVIC\nرسوم وضريبة: 1.30\nسعر الصرف~ 3.754\nإجمالي المبلغ المستحق: 57.61 SAR\nدولة:USA\nرصيد: 245.60 SAR\n\u061C16/11/26 19:59"
        val r = Parser.parse(sms, setOf("4567"))
        assertEquals(57.61, r.amount!!, 0.001)
        assertEquals("SAR", r.currency)
        assertEquals(15.0, r.origAmount!!, 0.001)
        assertEquals("USD", r.origCurrency)
        assertEquals(1.30, r.fees!!, 0.001)
        assertEquals(3.754, r.rate!!, 0.0001)
        assertEquals("USA", r.country)
        assertTrue(r.isForeign)
        assertEquals("WEBSERVIC", r.merchant)
        assertEquals("فيزا", r.method)
        assertEquals(245.60, r.balance!!, 0.001)
        assertEquals(listOf("4567"), r.matchedCards)
    }

    @Test fun onlineForeignCountryInRiyals() {
        val sms = "شراء انترنت\nبطاقة:4567 ;فيزا\nمبلغ: 97.95 SAR\nلدى:SHOP.IND\nرسوم وضريبة: 2.25 SAR\nاجمالي المبلغ المستحق: 100.2 SAR\nدولة:UK\nرصيد:310.25 SAR\nفي:16/11/26 7:26"
        val r = Parser.parse(sms, setOf("4567"))
        assertEquals(100.2, r.amount!!, 0.001)
        assertEquals(97.95, r.origAmount!!, 0.001)
        assertEquals(2.25, r.fees!!, 0.001)
        assertEquals("UK", r.country)
        assertTrue(r.isForeign)
        assertEquals("SHOP.IND", r.merchant)
        assertEquals(310.25, r.balance!!, 0.001)
    }

    @Test fun atmWithdrawal() {
        val sms = "سحب:صراف آلي بSR 2800\nعبر7890;مدى\nمنCA-AL OLAYA MAIN BR 2\n\u061C7/11/26 15:13"
        val r = Parser.parse(sms, setOf("7890"))
        assertEquals(Parser.Kind.WITHDRAWAL, r.kind)
        assertEquals(2800.0, r.amount!!, 0.001)
        assertEquals(listOf("7890"), r.matchedCards)
        assertEquals("مدى", r.method)
        assertEquals("CA-AL OLAYA MAIN BR 2", r.merchant)
        assertEquals("سحب: صراف آلي", r.type)
    }

    // ---------- Update 3: OTP, advertisements, SAB format, years, 3-digit endings (numbers replaced) ----------

    @Test fun otpIsIgnored() {
        val sms = "OTP: 4321\nOne Time Password to confirm your online transaction for SAB MADA (4455)\nMerchant: SHOP\nDate and Time: 2026-11-18 09:48:31\nAmount: SAR 1500.0"
        assertTrue(Parser.isIgnorable(sms))
        assertFalse(Parser.parse(sms, setOf("4455")).isTransaction)
    }

    @Test fun sabOnlinePurchase() {
        val sms = "Online Purchase\nBy: ***4455;mada\nFrom: ***009\nAmount: SAR 1,500.00\nAt: SHOP NAME\nDate: 2026-11-18 09:48:47"
        val r = Parser.parse(sms, setOf("4455"))
        assertTrue(r.isTransaction)
        assertEquals(Parser.Kind.PURCHASE, r.kind)
        assertEquals(1500.0, r.amount!!, 0.001)
        assertEquals(listOf("4455"), r.matchedCards)
        assertEquals("mada", r.method)
        assertEquals("SHOP NAME", r.merchant)
    }

    @Test fun threeDigitAccountEnding() {
        val sms = "Online Purchase\nBy: ***4455;mada\nFrom: ***009\nAmount: SAR 20.00\nAt: SHOP NAME"
        val r = Parser.parse(sms, setOf("009"))
        assertEquals(listOf("009"), r.matchedCards)
        // An unregistered 3-digit number is never reported as an unknown card.
        assertFalse(Parser.parse("شراء بـSR 20\nعبر1234;مدى\nلـSHOP 777", setOf("1234")).unknownCards.contains("777"))
    }

    @Test fun advertisementIsIgnored() {
        val sms = "Spend a minimum of SR 5000 using any of your Credit Cards and get SR 100 cashback. The offer is valid from 18 November until 18 December 2026. For T&Cs, please visit the Special Offers Page on our website. To participate, please send the code (AB1) to 500000"
        assertTrue(Parser.isIgnorable(sms))
        assertFalse(Parser.parse(sms, emptySet()).isTransaction)
    }

    @Test fun realDeductionIsNotAnAd() {
        val sms = "تم خصم مبلغ SR 25 من بطاقتك 1234 لدى SHOP"
        assertFalse(Parser.isIgnorable(sms))
    }

    @Test fun yearNextToMonthIsNotACard() {
        val sms = "شراء بـSR 50\nعبر1234;مدى\nلـSHOP\n18 December 2026"
        val r = Parser.parse(sms, setOf("1234", "2026"))
        assertEquals(listOf("1234"), r.matchedCards)
        assertTrue(r.unknownCards.isEmpty())
        val ar = Parser.parse("شراء بـSR 50\nعبر1234;مدى\nلـSHOP\n18 نوفمبر 2026", setOf("1234"))
        assertTrue(ar.unknownCards.isEmpty())
        // A card that really ends in 2026 is still found where a card appears.
        assertEquals(listOf("2026"), Parser.parse("شراء بـSR 50\nعبر2026;مدى\nلـSHOP", setOf("2026")).matchedCards)
    }
}
