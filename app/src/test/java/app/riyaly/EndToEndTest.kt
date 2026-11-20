package app.riyaly

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.graphics.Color
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Runs real bank messages through the whole app (reading, checks, refunds, transfers, loans, plan)
 * on a simulated phone, then opens every screen to make sure nothing crashes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EndToEndTest {
    private lateinit var ctx: Application
    private val sender = "AlRajhiBank"
    private val min = 60_000L
    private val now = System.currentTimeMillis()
    private var me = 0L
    private var kid = 0L

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Db.resetForTests()
        ctx.deleteDatabase("riyaly.db")
        ctx.getSharedPreferences("riyaly", 0).edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        shadowOf(ctx).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        AppLog.init(ctx)
        AppLog.clear(ctx)
        val db = Db.get(ctx)
        me = db.saveMember(null, "Member A", Color.BLUE)
        kid = db.saveMember(null, "Member B", Color.RED)
        db.saveCard("1234", me)
        db.saveCard("2345", kid)
        Prefs(ctx).onboarded = true
    }

    private fun db() = Db.get(ctx)
    private fun sms(body: String, ts: Long = now, live: Boolean = true) = Processor.handle(ctx, sender, body, ts, live)
    private fun purchase(amount: String, card: String, merchant: String, time: String, balance: String = "900") =
        "شراء بـSR $amount\nعبر$card;مدى\nلـ$merchant\nرصيد:$balance SR\n$time"

    @Test fun purchaseIsRecordedForTheRightPerson() {
        val t = sms(purchase("37.62", "1234", "HUNGERSTA", "18/11/26 8:45"))!!
        assertEquals(me, t.memberId)
        assertEquals(Parser.Kind.PURCHASE, t.kind)
        assertEquals(37.62, t.amount, 0.001)
        val k = sms(purchase("12", "2345", "CAFE ONE", "18/11/26 8:50"), now + min)!!
        assertEquals(kid, k.memberId)
        assertEquals(2, db().txns().size)
        assertTrue(AppLog.read(ctx).contains("recorded purchase"))
        assertFalse("log must not contain amounts", AppLog.read(ctx).contains("37.62"))
    }

    @Test fun duplicateWithinMinutesIsHighAndRingsAgainLater() {
        sms(purchase("89", "1234", "SHOP ONE", "18/11/26 9:00"))
        val second = sms(purchase("89", "1234", "SHOP ONE", "18/11/26 9:04"), now + 4 * min)!!
        assertTrue(Analyzer.DUPLICATE in second.reasons)
        assertEquals(Risk.HIGH, second.risk)
        assertEquals(DupState.OPEN, second.dupState)
        assertEquals(AlertState.OPEN, second.alert)
        assertNotNull(second.explain)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        assertTrue(shadowOf(nm).allNotifications.isNotEmpty())
    }

    @Test fun refundIsLinkedAndSubtracted() {
        val p = sms(purchase("37.62", "1234", "HUNGERSTA", "18/11/26 8:45"))!!
        val r = sms("استرداد مبلغ SR 37.62\nعبر1234\nمن HUNGERSTA", now + 60 * min)!!
        assertEquals(Parser.Kind.REFUND, r.kind)
        assertEquals(p.id, r.refundOf)
        val updated = db().txn(p.id)!!
        assertEquals(37.62, updated.refunded, 0.001)
        assertEquals(0.0, updated.netSpend, 0.001)
        assertFalse(Analyzer.UNEXPECTED_REFUND in r.reasons)
    }

    @Test fun refundWithoutPurchaseIsUnexpected() {
        val r = sms("استرداد مبلغ SR 80\nعبر1234\nمن OTHER SHOP")!!
        assertNull(r.refundOf)
        assertTrue(Analyzer.UNEXPECTED_REFUND in r.reasons)
    }

    @Test fun transferBetweenFamilyAccountsIsInternal() {
        val t = sms("حوالة داخلية صادرة بـSR 1200\nمن1234\nلـ2345;NAME B\n26/11/18 19:57")!!
        assertEquals(Parser.Kind.TRANSFER_OUT, t.kind)
        assertTrue(t.internal)
        assertEquals("Member B", t.counterparty)
        assertFalse(Analyzer.LARGE_TRANSFER in t.reasons)
        assertEquals(0.0, Insights.flow(db().txns(), Insights.month(now)).transfersOut, 0.01)
    }

    @Test fun feeIsRecognised() {
        val f = sms("خصم رسوم سنوية بطاقة ائتمانية\nبطاقة:1234\nمبلغ:SR 115\nرصيد:900 SR")!!
        assertEquals(Parser.Kind.FEE, f.kind)
        assertEquals("annual", f.feeType)
        assertTrue(Analyzer.UNEXPECTED_FEE in f.reasons)
        assertEquals(115.0, f.feesPaid, 0.01)
    }

    @Test fun salaryDetectedAndSafeToSpend() {
        val s = sms("حوالة داخلية واردة بـSR 9000\nلـ1234\nمن8901;EMPLOYER CO\nراتب شهر\nرصيد:9500 SR")!!
        assertEquals("salary", s.incomeType)
        sms(purchase("200", "1234", "MARKET", "18/11/26 10:00", balance = "9300"), now + 10 * min)
        Prefs(ctx).paydayDay = 27
        val safe = Plan.safe(ctx)
        assertNotNull(safe)
        assertTrue(safe!!.available > 0)
        assertNotNull(Plan.safeLine(ctx))
    }

    @Test fun loanRepaymentIsMatched() {
        val out = sms("حوالة داخلية صادرة بـSR 500\nمن1234\nلـ9012;FRIEND X\n26/11/18 19:57")!!
        assertFalse(out.internal)
        val id = db().saveLoan(Loan(0, out.id, "FRIEND X", "9012", 500.0, 0.0, null, now))
        sms("حوالة داخلية واردة بـSR 200\nلـ1234\nمن9012;FRIEND X\n26/11/19 09:36", now + 60 * min)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        assertTrue(shadowOf(nm).allNotifications.any { it.extras.getString("android.title")?.contains("FRIEND X") == true })
        Plan.repay(ctx, id, 200.0)
        assertEquals(200.0, db().loans().single().repaid, 0.01)
    }

    @Test fun oneTimePasswordsAndAdsAreIgnored() {
        assertNull(Processor.handle(ctx, sender, "OTP: 4321\nOne Time Password to confirm your online transaction\nAmount: SAR 1500.0", now, true))
        assertNull(Processor.handle(ctx, "SAB-AD", purchase("50", "1234", "SHOP", "18/11/26 9:00"), now, true))
        assertTrue(db().txns().isEmpty())
    }

    @Test fun rereadKeepsUserChoices() {
        val t = sms(purchase("50", "1234", "SHOP", "18/11/26 9:00"))!!
        db().updateTxn(t.copy(note = "gift", alert = AlertState.OK))
        Processor.reparseAll(ctx)
        val after = db().txn(t.id)!!
        assertEquals("gift", after.note)
        assertEquals(me, after.memberId)
    }

    @Test fun planSummariesRun() {
        for (i in 0 until 12) sms(purchase("${15 + i}", "1234", "COFFEE", "18/11/26 9:$i"), now - i * 26 * 60 * min)
        sms("حوالة داخلية واردة بـSR 9000\nلـ1234\nمن8901;EMPLOYER CO\nراتب\nرصيد:9500 SR", now - 2 * min)
        db().setBudget(Plan.BUDGET_CATEGORY, Categories.CAFES, 100.0)
        Plan.checkPlanAlerts(ctx, db().txns().first())
        Plan.weekly(ctx)
        Plan.daily(ctx)
        assertFalse(AppLog.read(ctx).contains(" E plan"))
    }

    @Test fun everyScreenOpensWithoutCrashing() {
        // Some data so the charts and lists have content.
        for (i in 0 until 8) sms(purchase("${20 + i * 7}", if (i % 2 == 0) "1234" else "2345", "SHOP $i", "18/11/26 9:$i"), now - i * 3 * 60 * min)
        sms(purchase("89", "1234", "SHOP ONE", "18/11/26 9:00"), now - 3 * min)
        sms(purchase("89", "1234", "SHOP ONE", "18/11/26 9:04"), now - min)
        sms("حوالة داخلية واردة بـSR 9000\nلـ1234\nمن8901;EMPLOYER CO\nراتب\nرصيد:9500 SR", now - 2 * min)
        sms("خصم رسوم سنوية بطاقة ائتمانية\nبطاقة:1234\nمبلغ:SR 115\nرصيد:900 SR", now - 5 * min)

        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        fun idle() = shadowOf(Looper.getMainLooper()).idle()
        for (tab in listOf(1, 2, 3, 6, 4, 1)) { a.nav.selectedItemId = tab; idle() }
        a.dashboard.more = true; a.refresh(); idle()
        assertTrue(a.goBack()); idle()
        for (sub in listOf("recurring", "budgets", "loans", "duplicates", "fees", "habits", "people")) { a.plan().openSub(sub); idle() }
        assertTrue(a.goBack()); idle()
        a.openSettings(); idle()
        for (page in listOf("alerts", "plan", "location", "banks", "backup", "updates", "advanced")) { a.settings.page = page; a.refresh(); idle() }
        assertTrue(a.goBack()); idle()
        assertTrue(a.goBack()); idle()
        a.feedback.show(); idle()
        a.feedback.onSpeech("The app works well")
        a.updateMenu()
        val t = db().txns().first { it.dupOf != null }
        TxnDetail.show(a, t); idle()
        a.openList(TxnFilter(kind = Parser.Kind.PURCHASE)); idle()
        assertFalse("errors in log:\n" + AppLog.read(ctx), AppLog.read(ctx).lines().any { it.contains(" E ") })
    }
}
