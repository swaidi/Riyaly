package app.riyaly

import android.content.Context
import java.util.Calendar
import kotlin.math.abs

/** Planning: safe to spend, budgets, recurring payments, loans, summaries and the plan alerts. */
object Plan {
    const val BUDGET_CATEGORY = "category"
    const val BUDGET_MEMBER = "member"
    const val BUDGET_HABIT = "habit"

    fun now() = System.currentTimeMillis()

    fun salaryTimes(txns: List<Txn>): List<Long> = txns.filter { it.isMoneyIn && it.incomeType == "salary" }.map { it.ts }

    /** The period used on screens: calendar month, or payday to payday. */
    fun period(ctx: Context, txns: List<Txn>, now: Long = now()): Insights.Period {
        if (Prefs(ctx).periodMode == "payday") Insights.paydayPeriod(salaryTimes(txns), now)?.let { if (now in it) return it }
        return Insights.month(now)
    }

    // ---------- balance & safe to spend ----------
    data class Balance(val amount: Double, val ts: Long, val manual: Boolean)

    /** Latest balance: the sum of the latest SMS balance of each chosen account, or the manual correction when newer. */
    fun balance(ctx: Context, txns: List<Txn>): Balance? {
        val p = Prefs(ctx)
        val chosen = p.balanceCards
        val latest = txns.filter { it.balance != null && it.card != null && (chosen.isEmpty() || it.card in chosen) }
            .groupBy { it.card }.mapValues { e -> e.value.maxByOrNull { it.ts }!! }.values
        val sms = if (latest.isEmpty()) null else Balance(latest.sumOf { it.balance!! }, latest.maxOf { it.ts }, false)
        val manual = p.manualBalance?.let { Balance(it, p.manualBalanceTs, true) }
        return when {
            manual != null && (sms == null || manual.ts >= sms.ts) -> {
                // Spending after the manual correction lowers it.
                val after = txns.filter { it.ts > manual.ts && (chosen.isEmpty() || it.card in chosen) }
                val delta = after.filter { it.isMoneyIn }.sumOf { it.amount } - after.filter { it.isSpend }.sumOf { it.amount } - after.filter { it.isTransferOut }.sumOf { it.amount }
                manual.copy(amount = manual.amount + delta)
            }
            else -> sms
        }
    }

    /** Recurring payments still active (not cancelled and not marked "not recurring"). */
    fun recurring(ctx: Context, txns: List<Txn>): List<Insights.Recurring> = AppLog.safe("plan recurring", emptyList()) {
        val db = Db.get(ctx)
        val keyOf = Processor.keyResolver(db)
        val states = db.recStates()
        val manual = states.values.filter { it.state == "not_recurring" || it.periodDays != null }
            .associate { it.key to if (it.state == "not_recurring") 0 else it.periodDays!! }
        val now = now()
        Insights.recurring(txns, { keyOf(it.merchant) }, manual)
            .filter { states[it.key]?.state != "cancelled" && now - it.lastTs <= it.periodDays * 2.5 * Insights.DAY }
    }

    fun nextPayday(ctx: Context, txns: List<Txn>, now: Long = now()): Long? =
        Insights.nextPayday(salaryTimes(txns), Prefs(ctx).paydayDay, now)

    fun safe(ctx: Context, txns: List<Txn>? = null, now: Long = now()): Insights.Safe? = AppLog.safe("safe to spend", null) {
        val all = txns ?: Db.get(ctx).txns(now - 400 * Insights.DAY)
        val bal = balance(ctx, all) ?: return@safe null
        val payday = nextPayday(ctx, all, now) ?: return@safe null
        Insights.safeToSpend(bal.amount, payday, now, recurring(ctx, all), Prefs(ctx).setAside.toDouble())
    }

    /** green / amber / red for the Safe-to-spend tile. */
    fun safeColor(s: Insights.Safe, spentToday: Double): String = when {
        s.available <= 0 -> "red"
        spentToday > s.daily -> "amber"
        else -> "green"
    }

    /** One line for purchase notifications: "Safe to spend today: SR 120". */
    fun safeLine(ctx: Context): String? = safe(ctx)?.let { s ->
        if (s.available <= 0) ctx.getString(R.string.safe_line_none) else ctx.getString(R.string.safe_line, Fmt.whole(s.daily))
    }

    fun spentToday(txns: List<Txn>, now: Long = now()): Double {
        val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        return txns.filter { it.isSpend && it.ts >= c.timeInMillis && it.ts <= now }.sumOf { it.netSpend }
    }

    // ---------- people ----------
    fun memberSpend(txns: List<Txn>, memberId: Long, p: Insights.Period) =
        txns.filter { it.isSpend && it.memberId == memberId && it.ts in p }.sumOf { it.netSpend }

    /** Money the owner sent to a member's registered accounts (allowance). */
    fun allowanceSent(txns: List<Txn>, memberName: String, p: Insights.Period) =
        txns.filter { it.kind == Parser.Kind.TRANSFER_OUT && !it.isDeclined && it.ts in p && it.counterparty == memberName }.sumOf { it.amount }

    // ---------- plan alerts after each live transaction ----------
    private fun crossed(before: Double, after: Double, limit: Double): Int? = when {
        limit <= 0 -> null
        before < limit && after >= limit -> 100
        before < limit * 0.8 && after >= limit * 0.8 -> 80
        else -> null
    }

    private fun once(ctx: Context, key: String, block: () -> Unit) {
        val p = Prefs(ctx)
        if (p.flag(key)) return
        p.setFlag(key)
        block()
    }

    fun checkPlanAlerts(ctx: Context, t: Txn, now: Long = t.ts) {
        val db = Db.get(ctx)
        val p = Prefs(ctx)
        val txns = db.txns(now - 400 * Insights.DAY)
        val month = Insights.month(now)
        val mk = Insights.monthKey(now)
        val inMonth = txns.filter { it.isSpend && it.ts in month }

        if (t.isSpend) {
            // Category budget.
            val cat = Categories.forTxn(db, t)
            db.budgets(BUDGET_CATEGORY)[cat]?.let { limit ->
                val after = inMonth.filter { Categories.forTxn(db, it) == cat }.sumOf { it.netSpend }
                crossed(after - t.netSpend, after, limit)?.let { pct ->
                    once(ctx, "cat_${cat}_${mk}_$pct") {
                        Notifier.info(ctx, "budget_cat_$cat".hashCode(), ctx.getString(R.string.n_budget_title, Categories.label(ctx, cat), pct),
                            ctx.getString(R.string.n_budget_text, Fmt.whole(after), Fmt.whole(limit)))
                    }
                }
            }
            // Person budget (all their cards).
            t.memberId?.let { mid ->
                db.budgets(BUDGET_MEMBER)["$mid"]?.let { limit ->
                    val after = inMonth.filter { it.memberId == mid }.sumOf { it.netSpend }
                    crossed(after - t.netSpend, after, limit)?.let { pct ->
                        once(ctx, "mem_${mid}_${mk}_$pct") {
                            val name = db.member(mid)?.name ?: ""
                            Notifier.info(ctx, "budget_mem_$mid".hashCode(), ctx.getString(R.string.n_budget_title, name, pct),
                                ctx.getString(R.string.n_budget_text, Fmt.whole(after), Fmt.whole(limit)))
                        }
                    }
                }
            }
            // Habit budget (small purchases at one place).
            val key = Processor.keyResolver(db)(t.merchant)
            if (key != null && t.netSpend <= p.smallThreshold) db.budgets(BUDGET_HABIT)[key]?.let { limit ->
                val after = inMonth.filter { Processor.keyResolver(db)(it.merchant) == key && it.netSpend <= p.smallThreshold }.sumOf { it.netSpend }
                crossed(after - t.netSpend, after, limit)?.let { pct ->
                    once(ctx, "habit_${key}_${mk}_$pct") {
                        Notifier.info(ctx, "budget_habit_$key".hashCode(), ctx.getString(R.string.n_budget_title, t.merchant ?: key, pct),
                            ctx.getString(R.string.n_budget_text, Fmt.whole(after), Fmt.whole(limit)))
                    }
                }
            }
            // Spending pace (from the 7th day of the month, with a normal level to compare).
            val c = Insights.compare(txns.filter { it.isSpend }, now, p.excludedMonths)
            val pace = c.vsNormal
            if (Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_MONTH) >= 7 && pace != null && pace * 100 > p.paceThreshold) {
                once(ctx, "pace_$mk") {
                    Notifier.info(ctx, 9101, ctx.getString(R.string.n_pace_title, Math.round(pace * 100).toInt()),
                        ctx.getString(R.string.n_pace_text, Fmt.whole(c.soFar), Fmt.whole(c.normalSameDay), Fmt.whole(c.projected)))
                }
            }
        }

        // Money out above money in this month, and the saving target at risk (mid-month projection).
        val flow = Insights.flow(txns, month)
        if (flow.income > 0 && flow.out > flow.income) once(ctx, "outin_$mk") {
            Notifier.info(ctx, 9102, ctx.getString(R.string.n_outin_title), ctx.getString(R.string.n_outin_text, Fmt.whole(flow.out), Fmt.whole(flow.income)))
        }
        val day = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_MONTH)
        if (day >= 10 && flow.income > 0) {
            val projectedOut = Insights.projected(flow.out, month, now)
            val projectedRate = (flow.income - projectedOut) / flow.income
            if (projectedRate * 100 < p.savingTarget) once(ctx, "target_$mk") {
                Notifier.info(ctx, 9103, ctx.getString(R.string.n_target_title, p.savingTarget),
                    ctx.getString(R.string.n_target_text, Math.round(projectedRate * 100).toInt()))
            }
        }

        // A bill due before payday that the balance may not cover.
        safe(ctx, txns, now)?.let { s ->
            if (s.billsDue > 0 && s.balance < s.billsDue + s.setAside) once(ctx, "billrisk_${s.payday / Insights.DAY}") {
                Notifier.info(ctx, 9104, ctx.getString(R.string.n_billrisk_title),
                    ctx.getString(R.string.n_billrisk_text, Fmt.whole(s.billsDue), Fmt.whole(s.balance)))
            }
        }

        // Salary arrived: payday summary of the period that just ended.
        if (t.isMoneyIn && t.incomeType == "salary") once(ctx, "payday_${t.id}") { paydaySummary(ctx, txns, t) }
    }

    private fun paydaySummary(ctx: Context, txns: List<Txn>, salary: Txn) {
        if (!Prefs(ctx).summaryOn("payday")) return
        val prev = salaryTimes(txns).filter { it < salary.ts - 15 * Insights.DAY }.maxOrNull() ?: return
        val f = Insights.flow(txns, Insights.Period(prev, salary.ts))
        Notifier.info(ctx, 9105, ctx.getString(R.string.n_payday_title),
            ctx.getString(R.string.n_payday_text, Fmt.whole(f.income), Fmt.whole(f.out), Fmt.whole(f.net), Fmt.whole(f.fees)))
    }

    // ---------- loans ----------
    /** A transfer in from someone who owes money: ask whether it is the repayment. */
    fun loanRepaymentPrompt(ctx: Context, t: Txn) {
        val loans = Db.get(ctx).loans().filter { it.open }
        val l = loans.firstOrNull { l ->
            (l.acct != null && t.counterpartyAcct != null && l.acct.takeLast(4) == t.counterpartyAcct.takeLast(4)) ||
                (t.counterparty != null && l.counterparty.equals(t.counterparty, ignoreCase = true))
        } ?: return
        Notifier.loanPrompt(ctx, t, l)
    }

    /** Records a repayment (part or full). */
    fun repay(ctx: Context, loanId: Long, amount: Double) {
        val db = Db.get(ctx)
        val l = db.loans().firstOrNull { it.id == loanId } ?: return
        db.saveLoan(l.copy(repaid = (l.repaid + amount).coerceAtMost(l.amount)))
        AppLog.i("loan repayment recorded")
    }

    // ---------- daily and weekly ----------
    fun daily(ctx: Context, now: Long = now()) {
        val db = Db.get(ctx)
        val p = Prefs(ctx)
        val txns = db.txns(now - 400 * Insights.DAY)

        AppLog.safe("update check", Unit) { checkUpdate(ctx) }
        AppLog.safe("fx", Unit) { Fx.fillMissing(ctx) }

        AppLog.safe("loans due", Unit) {
            db.loans().filter { it.open && it.dueTs != null && it.dueTs <= now + Insights.DAY }.forEach { l ->
                once(ctx, "loan_due_${l.id}") {
                    Notifier.info(ctx, 9200 + l.id.toInt(), ctx.getString(R.string.n_loan_due_title, l.counterparty),
                        ctx.getString(R.string.n_loan_due_text, Fmt.whole(l.amount - l.repaid)))
                }
            }
        }

        AppLog.safe("duplicate follow-up", Unit) {
            txns.filter { it.dupState == DupState.CONFIRMED && it.refunded < 0.01 }.forEach { d ->
                val age = (now - d.ts) / Insights.DAY
                if (age >= 14) once(ctx, "dup14_${d.id}") {
                    Notifier.info(ctx, 9300 + d.id.toInt(), ctx.getString(R.string.n_dup14_title), ctx.getString(R.string.n_dup14_text, Fmt.whole(d.amount), d.merchant ?: ""), callBankFor = d)
                } else if (age >= 7) once(ctx, "dup7_${d.id}") {
                    Notifier.info(ctx, 9300 + d.id.toInt(), ctx.getString(R.string.n_dup7_title), ctx.getString(R.string.n_dup7_text, Fmt.whole(d.amount), d.merchant ?: ""))
                }
            }
        }

        val rec = recurring(ctx, txns)
        AppLog.safe("upcoming", Unit) {
            if (p.summaryOn("upcoming")) rec.filter { it.nextTs in now..(now + 3 * Insights.DAY) }.forEach { r ->
                once(ctx, "upcoming_${r.key}_${r.nextTs / Insights.DAY}") {
                    Notifier.info(ctx, "up_${r.key}".hashCode(), ctx.getString(R.string.n_upcoming_title, r.name), ctx.getString(R.string.n_upcoming_text, Fmt.whole(r.lastAmount)))
                }
            }
        }
        AppLog.safe("review", Unit) {
            if (rec.isNotEmpty() && now - p.lastReview > 90 * Insights.DAY) {
                if (p.lastReview > 0) Notifier.info(ctx, 9106, ctx.getString(R.string.n_review_title, rec.size),
                    ctx.getString(R.string.n_review_text, Fmt.whole(rec.sumOf { it.lastAmount * 365.0 / it.periodDays })))
                p.lastReview = now
            }
        }
        AppLog.safe("month end", Unit) {
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            if (cal.get(Calendar.DAY_OF_MONTH) == 1 && p.summaryOn("month")) {
                val last = Insights.month(now, 1)
                once(ctx, "monthend_${Insights.monthKey(last.from)}") {
                    val f = Insights.flow(txns, last)
                    val prev = Insights.flow(txns, Insights.month(now, 2))
                    Notifier.info(ctx, 9107, ctx.getString(R.string.n_month_title),
                        ctx.getString(R.string.n_month_text, Fmt.whole(f.spend), Fmt.whole(prev.spend), Fmt.whole(f.net), Fmt.whole(f.fees)))
                }
            }
        }
    }

    /** Sunday summary: small purchases, week spent vs allowed, Medium alerts to review. */
    fun weekly(ctx: Context, now: Long = now()) {
        val db = Db.get(ctx)
        val p = Prefs(ctx)
        if (!p.summaryOn("week")) return
        val txns = db.txns(now - 400 * Insights.DAY)
        val week = Insights.Period(now - 7 * Insights.DAY, now)
        val spends = txns.filter { it.isSpend && it.ts in week }
        val small = spends.filter { it.kind == Parser.Kind.PURCHASE && it.netSpend <= p.smallThreshold }
        val medium = db.openAlerts().count { it.risk == Risk.MEDIUM }
        val lines = mutableListOf(
            ctx.getString(R.string.n_week_spent, Fmt.whole(spends.sumOf { it.netSpend })),
            ctx.getString(R.string.n_week_small, small.size, Fmt.whole(small.sumOf { it.netSpend })),
        )
        safe(ctx, txns, now)?.let { if (it.available > 0) lines += ctx.getString(R.string.n_week_allowed, Fmt.whole(it.daily * 7)) }
        if (medium > 0) lines += ctx.getString(R.string.n_week_review, medium)
        Notifier.info(ctx, 9108, ctx.getString(R.string.n_week_title), lines.joinToString("\n"))
    }

    fun checkUpdate(ctx: Context) {
        val p = Prefs(ctx)
        val r = Updater.latest(Updater.token(ctx))
        p.lastUpdateCheck = now()
        if (r != null && r.versionCode > BuildConfig.VERSION_CODE) {
            if (p.updateAvailableTag != r.tag) {
                p.updateAvailableTag = r.tag
                Notifier.info(ctx, 9109, ctx.getString(R.string.n_update_title), ctx.getString(R.string.n_update_text, r.tag))
            }
        } else p.updateAvailableTag = null
        AppLog.i("update check: latest=${r?.tag} installed=${BuildConfig.VERSION_NAME}")
    }

    // ---------- needs / wants / saving ----------
    data class Split(val needs: Double, val wants: Double, val saving: Double, val income: Double)

    fun split(ctx: Context, txns: List<Txn>, p: Insights.Period): Split {
        val db = Db.get(ctx)
        val ov = Prefs(ctx).bucketOverrides
        val sp = txns.filter { it.isSpend && it.ts in p }
        var needs = 0.0; var wants = 0.0
        sp.forEach { if (Insights.bucketOf(Categories.forTxn(db, it), ov) == "need") needs += it.netSpend else wants += it.netSpend }
        val f = Insights.flow(txns, p)
        return Split(needs, wants, (f.income - f.out).coerceAtLeast(0.0), f.income)
    }

    /** Biggest change this month (category) for the "Why it changed" line. */
    fun biggestChange(ctx: Context, txns: List<Txn>, now: Long = now()): Triple<String, Double, Double>? {
        val db = Db.get(ctx)
        return Insights.changes(txns.filter { it.isSpend }, now, { Categories.forTxn(db, it) }, Prefs(ctx).excludedMonths)
            .firstOrNull { abs(it.second - it.third) >= 50 }
    }
}
