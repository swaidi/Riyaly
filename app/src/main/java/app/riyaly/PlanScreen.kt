package app.riyaly

import android.content.Intent
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Plan: safe to spend, budgets, recurring payments, who owes me, possible duplicates, fees, small
 * purchases and the family. The first page is short; each card opens its own page.
 */
class PlanScreen(a: MainActivity) : Screen(a) {
    override val titleRes = R.string.tab_plan
    /** The open sub-page, or null for the overview. */
    var sub: String? = null
    private val df = SimpleDateFormat("d MMM", Locale.getDefault())

    override fun titleText(): String? = when (sub) {
        "recurring" -> a.getString(R.string.pl_recurring)
        "loans" -> a.getString(R.string.pl_loans)
        "duplicates" -> a.getString(R.string.pl_duplicates)
        "fees" -> a.getString(R.string.pl_fees)
        "habits" -> a.getString(R.string.pl_habits)
        "people" -> a.getString(R.string.pl_people)
        "budgets" -> a.getString(R.string.pl_budgets)
        else -> null
    }

    fun openSub(key: String) {
        sub = key
        if (a.nav.selectedItemId == 6) a.refresh() else a.nav.selectedItemId = 6
    }

    override fun build(): View {
        val c = a
        val root = c.vbox(16)
        val now = System.currentTimeMillis()
        val txns = a.db.txns(now - 400 * Insights.DAY)
        AppLog.safe("plan screen", Unit) {
            when (sub) {
                "recurring" -> recurring(root, txns, now)
                "loans" -> loans(root)
                "duplicates" -> duplicates(root, txns)
                "fees" -> fees(root, txns, now)
                "habits" -> habits(root, txns, now)
                "people" -> people(root, txns, now)
                "budgets" -> budgets(root, txns, now)
                else -> overview(root, txns, now)
            }
        }
        return c.scroll(root)
    }

    private fun entry(root: LinearLayout, title: String, line: String, key: String) {
        root.addView(a.card(a.text(title, 16f, true), a.muted(line), onClick = { sub = key; a.refresh() }))
    }

    // ---------- overview ----------
    private fun overview(root: LinearLayout, txns: List<Txn>, now: Long) {
        val c = a
        val p = a.prefs
        // Safe to spend.
        root.addView(c.section(c.getString(R.string.safe_title)))
        val bal = Plan.balance(c, txns)
        val payday = Plan.nextPayday(c, txns, now)
        val safe = Plan.safe(c, txns, now)
        val box = mutableListOf<View>()
        if (safe != null) {
            val color = when (Plan.safeColor(safe, Plan.spentToday(txns, now))) { "red" -> Ui.ALERT; "amber" -> Ui.PALETTE[2]; else -> Ui.PALETTE[0] }
            box += c.text(if (safe.available <= 0) c.getString(R.string.safe_none) else c.getString(R.string.safe_per_day, Fmt.whole(safe.daily)), 24f, true, color)
            box += c.muted(c.getString(R.string.safe_detail, Fmt.whole(safe.balance), Fmt.whole(safe.billsDue), Fmt.whole(safe.setAside), df.format(Date(safe.payday)), safe.daysLeft))
            val weekSpent = txns.filter { it.isSpend && it.ts > now - 7 * Insights.DAY }.sumOf { it.netSpend }
            box += c.text(c.getString(R.string.safe_week, Fmt.whole(maxOf(0.0, safe.daily) * 7), Fmt.whole(weekSpent)), 14f)
        } else {
            box += c.muted(c.getString(if (bal == null) R.string.safe_need_balance else R.string.safe_need_payday))
        }
        bal?.let { box += c.muted(c.getString(if (it.manual) R.string.balance_manual else R.string.balance_sms, Fmt.whole(it.amount), df.format(Date(it.ts))), 12f) }
        box += c.hbox().apply {
            addView(c.textButton(c.getString(R.string.correct_balance)) { correctBalance() })
            addView(c.textButton(c.getString(R.string.set_payday)) { setPayday(payday) })
            addView(c.textButton(c.getString(R.string.set_aside)) { setAside() })
        }.let { c.scrollH(it) }
        root.addView(c.card(*box.toTypedArray()))

        // Sections.
        root.addView(c.section(c.getString(R.string.pl_more)))
        val rec = Plan.recurring(c, txns)
        entry(root, c.getString(R.string.pl_recurring), c.getString(R.string.pl_recurring_line, rec.size, Fmt.whole(rec.sumOf { it.yearly })), "recurring")
        val budgetsCount = a.db.budgets(Plan.BUDGET_CATEGORY).size + a.db.budgets(Plan.BUDGET_MEMBER).size
        entry(root, c.getString(R.string.pl_budgets), c.getString(R.string.pl_budgets_line, budgetsCount), "budgets")
        val loans = a.db.loans().filter { it.open }
        entry(root, c.getString(R.string.pl_loans), c.getString(R.string.pl_loans_line, loans.size, Fmt.whole(loans.sumOf { it.amount - it.repaid })), "loans")
        val dups = txns.filter { it.dupState == DupState.OPEN || it.dupState == DupState.CONFIRMED }
        entry(root, c.getString(R.string.pl_duplicates), c.getString(R.string.pl_duplicates_line, dups.size, Fmt.whole(dups.sumOf { it.amount })), "duplicates")
        val month = Insights.month(now)
        val year = Insights.Period(now - 365 * Insights.DAY, now + 1)
        entry(root, c.getString(R.string.pl_fees), c.getString(R.string.pl_fees_line, Fmt.whole(txns.filter { it.ts in month }.sumOf { it.feesPaid }), Fmt.whole(txns.filter { it.ts in year }.sumOf { it.feesPaid })), "fees")
        val small = txns.filter { it.isSpend && it.ts in month && it.kind == Parser.Kind.PURCHASE && it.netSpend <= p.smallThreshold }
        entry(root, c.getString(R.string.pl_habits), c.getString(R.string.pl_habits_line, small.size, Fmt.whole(small.sumOf { it.netSpend })), "habits")
        entry(root, c.getString(R.string.pl_people), c.getString(R.string.pl_people_line, a.db.members().size), "people")
    }

    private fun correctBalance() {
        val c = a
        val box = c.vbox(20)
        val f = c.field(c.getString(R.string.balance_now), "", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, c.getString(R.string.balance_now_helper))
        box.addView(f.layout)
        val cards = a.db.cards()
        if (cards.isNotEmpty()) {
            box.addView(c.muted(c.getString(R.string.balance_accounts), 12f))
            val chips = c.chips(singleSelection = false)
            val chosen = a.prefs.balanceCards.toMutableSet()
            cards.forEach { card ->
                chips.addView(c.chip(card.display(), chosen.isEmpty() || card.last4 in chosen) {
                    if (!chosen.add(card.last4)) chosen.remove(card.last4)
                    a.prefs.balanceCards = chosen
                })
            }
            box.addView(chips)
        }
        c.dialog(c.getString(R.string.correct_balance)).setView(c.scroll(box)).setPositiveButton(R.string.save) { _, _ ->
            val v = f.value.replace(",", "").toDoubleOrNull()
            a.prefs.manualBalance = v
            a.prefs.manualBalanceTs = if (v == null) 0 else System.currentTimeMillis()
            a.refresh()
        }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun setPayday(next: Long?) {
        val c = a
        val box = c.vbox(20)
        if (next != null) box.addView(c.muted(c.getString(R.string.payday_detected, df.format(Date(next)))))
        val f = c.field(c.getString(R.string.payday_day), a.prefs.paydayDay?.toString() ?: "", InputType.TYPE_CLASS_NUMBER, c.getString(R.string.payday_helper))
        box.addView(f.layout)
        c.dialog(c.getString(R.string.set_payday)).setView(box).setPositiveButton(R.string.save) { _, _ ->
            a.prefs.paydayDay = f.value.toIntOrNull()?.takeIf { it in 1..31 }; a.refresh()
        }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun setAside() {
        val c = a
        val box = c.vbox(20)
        val f = c.field(c.getString(R.string.set_aside_amount), "${a.prefs.setAside}", InputType.TYPE_CLASS_NUMBER, c.getString(R.string.set_aside_helper))
        box.addView(f.layout)
        c.dialog(c.getString(R.string.set_aside)).setView(box).setPositiveButton(R.string.save) { _, _ ->
            a.prefs.setAside = f.value.toIntOrNull() ?: 0; a.refresh()
        }.setNegativeButton(R.string.cancel, null).show()
    }

    // ---------- recurring ----------
    private fun recurring(root: LinearLayout, txns: List<Txn>, now: Long) {
        val c = a
        val rec = Plan.recurring(c, txns)
        if (rec.isEmpty()) { root.addView(c.card(c.muted(c.getString(R.string.rec_none)))); return }
        root.addView(c.card(
            c.text(c.getString(R.string.rec_total, Fmt.whole(rec.sumOf { it.monthly }), Fmt.whole(rec.sumOf { it.yearly })), 16f, true),
            c.muted(c.getString(R.string.rec_upcoming, rec.count { it.nextTs in now..(now + 7 * Insights.DAY) }, Fmt.whole(rec.filter { it.nextTs in now..(now + 7 * Insights.DAY) }.sumOf { it.lastAmount }))),
        ))
        // The same service paid on more than one card or by more than one person.
        rec.filter { it.members.size > 1 || it.cards.size > 1 }.forEach {
            root.addView(c.card(c.text(c.getString(R.string.rec_twice, it.name), 14f, true), c.muted(c.getString(R.string.rec_twice_hint), 12f)))
        }
        val states = a.db.recStates()
        for (r in rec) {
            val period = when (r.periodDays) { 7 -> R.string.per_week; 14 -> R.string.per_2weeks; 30 -> R.string.per_month; 91 -> R.string.per_quarter; 365 -> R.string.per_year; else -> R.string.per_days }
            val lines = mutableListOf<View>(
                c.text("${a.db.merchant(r.name)?.displayName ?: r.name} · SR ${Fmt.whole(r.lastAmount)} " + c.getString(period, r.periodDays), 15f, true),
                c.muted(c.getString(R.string.rec_line, df.format(Date(r.nextTs)), Fmt.whole(r.yearly))),
            )
            if (r.foreign && r.feesPerYear > 0.5) lines += c.muted(c.getString(R.string.rec_foreign_fees, Fmt.whole(r.feesPerYear)), 12f)
            if (r.avgAmount > 0 && r.lastAmount > r.avgAmount * 1.05) lines += c.badge(c.getString(R.string.rec_price_up), Ui.PALETTE[2])
            if (states[r.key]?.state == "keep") lines += c.muted(c.getString(R.string.rec_kept), 12f)
            root.addView(c.card(*lines.toTypedArray(), onClick = { recurringActions(r) }))
        }
        root.addView(c.muted(c.getString(R.string.rec_hint), 12f))
    }

    private fun recurringActions(r: Insights.Recurring) {
        val c = a
        val opts = listOf(R.string.rec_keep, R.string.rec_cancelled, R.string.rec_not_recurring, R.string.rec_change_period)
        c.dialog(r.name).setItems(opts.map { c.getString(it) }.toTypedArray()) { _, i ->
            when (i) {
                0 -> a.db.setRecState(r.key, "keep", null)
                1 -> { a.db.setRecState(r.key, "cancelled", null); c.toast(c.getString(R.string.rec_cancelled_toast)) }
                2 -> a.db.setRecState(r.key, "not_recurring", null)
                3 -> {
                    val days = listOf(7, 14, 30, 91, 365)
                    c.dialog(c.getString(R.string.rec_change_period)).setItems(days.map { c.getString(R.string.every_days, it) }.toTypedArray()) { _, j ->
                        a.db.setRecState(r.key, null, days[j]); a.refresh()
                    }.show()
                }
            }
            a.refresh()
        }.setNegativeButton(R.string.cancel, null).show()
    }

    // ---------- loans ----------
    private fun loans(root: LinearLayout) {
        val c = a
        val loans = a.db.loans()
        if (loans.isEmpty()) { root.addView(c.card(c.muted(c.getString(R.string.loan_none)))); return }
        root.addView(c.card(c.text(c.getString(R.string.loan_total, Fmt.whole(loans.filter { it.open }.sumOf { it.amount - it.repaid })), 16f, true)))
        for (l in loans.sortedByDescending { it.open }) {
            val lines = mutableListOf<View>(
                c.text("${l.counterparty} · SR ${Fmt.whole(l.amount - l.repaid)}", 15f, true, if (l.open) null else Ui.GREY),
                c.muted(c.getString(R.string.loan_line, Fmt.whole(l.amount), Fmt.whole(l.repaid), df.format(Date(l.createdTs)))),
            )
            l.dueTs?.let { lines += c.muted(c.getString(R.string.loan_due, df.format(Date(it))), 12f) }
            root.addView(c.card(*lines.toTypedArray(), onClick = { loanActions(l) }))
        }
    }

    private fun loanActions(l: Loan) {
        val c = a
        val box = c.vbox(20)
        val f = c.field(c.getString(R.string.loan_repaid_amount), Fmt.whole(l.amount - l.repaid).replace(",", ""), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        box.addView(f.layout)
        c.dialog(l.counterparty).setView(box)
            .setPositiveButton(R.string.btn_mark_repaid) { _, _ -> f.value.toDoubleOrNull()?.let { Plan.repay(c, l.id, it) }; a.refresh() }
            .setNeutralButton(R.string.delete) { _, _ -> a.db.deleteLoan(l.id); a.refresh() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    // ---------- duplicates ----------
    private fun duplicates(root: LinearLayout, txns: List<Txn>) {
        val c = a
        val list = txns.filter { it.dupOf != null && it.dupState != null }.sortedByDescending { it.ts }
        if (list.isEmpty()) { root.addView(c.card(c.muted(c.getString(R.string.dup_none)))); return }
        root.addView(c.muted(c.getString(R.string.dup_hint), 12f))
        for (t in list) {
            val state = when (t.dupState) {
                DupState.OPEN -> c.getString(R.string.dup_open); DupState.CONFIRMED -> c.getString(R.string.dup_confirmed)
                DupState.REFUNDED -> c.getString(R.string.dup_refunded); else -> c.getString(R.string.dup_is_normal)
            }
            root.addView(txnRow(a, t) { a.refresh() })
            root.addView(c.muted(state + (t.explain?.lines()?.firstOrNull()?.let { " · $it" } ?: ""), 12f).apply { setPadding(c.dp(8), 0, 0, c.dp(12)) })
        }
    }

    // ---------- fees ----------
    private fun fees(root: LinearLayout, txns: List<Txn>, now: Long) {
        val c = a
        val year = txns.filter { it.ts > now - 365 * Insights.DAY }
        val month = txns.filter { it.ts in Insights.month(now) }
        root.addView(c.card(
            c.text(c.getString(R.string.fee_totals, Fmt.whole(month.sumOf { it.feesPaid }), Fmt.whole(year.sumOf { it.feesPaid })), 16f, true),
            c.muted(c.getString(R.string.fee_fx, Fmt.whole(year.sumOf { it.fxExtra ?: 0.0 })), 12f),
        ))
        val byType = year.flatMap { t ->
            listOfNotNull(
                if (t.kind == Parser.Kind.FEE && !t.isDeclined) (t.feeType ?: "other") to t.amount else null,
                t.fees?.takeIf { it > 0 }?.let { "international" to it },
                t.fxExtra?.takeIf { it > 0 }?.let { "fx" to it },
            )
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }.toList().sortedByDescending { it.second }
        if (byType.isNotEmpty()) {
            root.addView(c.section(c.getString(R.string.fee_by_type)))
            root.addView(c.card(HBarChart(c).apply {
                items = byType.mapIndexed { i, (k, v) ->
                    HBarChart.Item(if (k == "fx") c.getString(R.string.fee_fx_label) else Analyzer.feeTypeLabel(c, k), v, Ui.PALETTE[i % Ui.PALETTE.size], k, "SR " + Fmt.whole(v))
                }
            }))
        }
        // Cheapest card abroad.
        val rates = Insights.foreignFeeRateByCard(year)
        if (rates.size >= 2) {
            val best = rates.minByOrNull { it.value }!!
            val worst = rates.maxByOrNull { it.value }!!
            val foreignOnWorst = year.filter { it.isForeign && it.isSpend && it.card == worst.key }.sumOf { it.amount }
            root.addView(c.section(c.getString(R.string.fee_abroad)))
            root.addView(c.card(
                c.text(c.getString(R.string.fee_cheapest, a.db.card(best.key)?.display() ?: best.key, pct1(best.value)), 14f),
                c.text(c.getString(R.string.fee_dearest, a.db.card(worst.key)?.display() ?: worst.key, pct1(worst.value)), 14f),
                c.muted(c.getString(R.string.fee_saving, Fmt.whole(foreignOnWorst * (worst.value - best.value))), 12f),
            ))
        }
        root.addView(c.switchRow(c.getString(R.string.fx_switch), a.prefs.fxEnabled) { a.prefs.fxEnabled = it })
        root.addView(c.muted(c.getString(R.string.fx_note), 12f))
    }

    private fun pct1(v: Double) = "%.1f%%".format(Locale.US, v * 100)

    // ---------- small purchases ----------
    private fun habits(root: LinearLayout, txns: List<Txn>, now: Long) {
        val c = a
        val p = a.prefs
        val small = p.smallThreshold.toDouble()
        val month = Insights.month(now)
        val spends = txns.filter { it.isSpend }
        val keyOf = Processor.keyResolver(a.db)
        val habits = Insights.habits(spends, month, now, small) { keyOf(it.merchant) ?: "?" }
        val last = Insights.habits(spends, Insights.month(now, 1), now, small) { keyOf(it.merchant) ?: "?" }.associateBy { it.group }
        val monthSpend = spends.filter { it.ts in month }.sumOf { it.netSpend }
        val total = habits.sumOf { it.total }
        root.addView(c.card(
            c.text(c.getString(R.string.hab_total, habits.sumOf { it.count }, Fmt.whole(total)), 16f, true),
            c.muted(c.getString(R.string.hab_share, if (monthSpend > 0) Math.round(total / monthSpend * 100).toInt() else 0, Fmt.whole(small))),
            c.textButton(c.getString(R.string.hab_threshold)) {
                val box = c.vbox(20)
                val f = c.field(c.getString(R.string.hab_threshold), "${p.smallThreshold}", InputType.TYPE_CLASS_NUMBER)
                box.addView(f.layout)
                c.dialog(c.getString(R.string.hab_threshold)).setView(box).setPositiveButton(R.string.save) { _, _ ->
                    f.value.toIntOrNull()?.takeIf { it > 0 }?.let { p.smallThreshold = it }; a.refresh()
                }.setNegativeButton(R.string.cancel, null).show()
            },
        ))
        // By person.
        val byPerson = spends.filter { it.ts in month && it.kind == Parser.Kind.PURCHASE && it.netSpend <= small }.groupBy { it.memberId }
        if (byPerson.size > 1) root.addView(c.card(*byPerson.map { (id, l) ->
            c.text("${a.db.member(id)?.name ?: c.getString(R.string.unidentified)}: ${l.size}× · SR ${Fmt.whole(l.sumOf { it.netSpend })}", 14f)
        }.toTypedArray()))
        val budgets = a.db.budgets(Plan.BUDGET_HABIT)
        for (h in habits.take(12)) {
            val prev = last[h.group]
            val name = a.db.merchant(h.group)?.displayName ?: txns.firstOrNull { keyOf(it.merchant) == h.group }?.merchant ?: h.group
            val lines = mutableListOf<View>(
                c.text("$name · ${h.count}× · SR ${Fmt.whole(h.total)}", 15f, true),
                c.muted(c.getString(R.string.hab_line, Fmt.whole(h.yearPace), prev?.let { Fmt.whole(it.total) } ?: "0")),
                c.muted(c.getString(R.string.hab_whatif, Fmt.whole(h.yearPace / 2)), 12f),
            )
            budgets[h.group]?.let { lines += c.muted(c.getString(R.string.budget_progress, Fmt.whole(h.total), Fmt.whole(it)), 12f) }
            root.addView(c.card(*lines.toTypedArray(), onClick = { editBudget(Plan.BUDGET_HABIT, h.group, name) }))
        }
        if (habits.isEmpty()) root.addView(c.card(c.muted(c.getString(R.string.hab_none))))
    }

    // ---------- people ----------
    private fun people(root: LinearLayout, txns: List<Txn>, now: Long) {
        val c = a
        val month = Insights.month(now)
        val spends = txns.filter { it.isSpend }
        val family = a.prefs.familyCategories
        val budgets = a.db.budgets(Plan.BUDGET_MEMBER)
        val owner = a.prefs.ownerMemberId
        val members = a.db.members()
        if (members.isEmpty()) { root.addView(c.card(c.muted(c.getString(R.string.add_member_first)))); return }
        val changes = Insights.changes(spends, now, { (it.memberId ?: -1L).toString() }, a.prefs.excludedMonths).associateBy { it.first }
        for (m in members) {
            val mine = spends.filter { it.memberId == m.id && it.ts in month }
            val total = mine.sumOf { it.netSpend }
            val usual = changes["${m.id}"]?.third ?: 0.0
            val soFarNormal = changes["${m.id}"]?.second ?: total
            val cats = mine.groupBy { Categories.forTxn(a.db, it) }.mapValues { e -> e.value.sumOf { it.netSpend } }.toList().sortedByDescending { it.second }
            val shops = mine.filter { it.merchant != null }.groupBy { it.merchant!! }.mapValues { e -> e.value.sumOf { it.netSpend } }.toList().sortedByDescending { it.second }
            val fam = mine.filter { Categories.forTxn(a.db, it) in family }.sumOf { it.netSpend }
            val lines = mutableListOf<View>(
                c.hbox().apply { addView(c.colorDot(m.color)); add(c.text("${m.name} · SR ${Fmt.whole(total)}", 16f, true), weight = 1f) },
                c.muted(if (usual > 0) c.getString(R.string.ppl_vs_usual, Math.round((soFarNormal - usual) / usual * 100).toInt(), Fmt.whole(usual)) else c.getString(R.string.ppl_no_usual)),
            )
            if (cats.isNotEmpty()) lines += c.muted(c.getString(R.string.ppl_top_cats, cats.take(3).joinToString(", ") { "${Categories.label(c, it.first)} ${Fmt.whole(it.second)}" }), 12f)
            if (shops.isNotEmpty()) lines += c.muted(c.getString(R.string.ppl_top_shops, shops.take(3).joinToString(", ") { "${it.first} ${Fmt.whole(it.second)}" }), 12f)
            lines += c.muted(c.getString(R.string.ppl_family_split, Fmt.whole(fam), Fmt.whole(total - fam)), 12f)
            if (owner != null && owner != m.id) {
                val sent = Plan.allowanceSent(txns, m.name, month)
                if (sent > 0) lines += c.muted(c.getString(R.string.ppl_allowance, Fmt.whole(sent), Fmt.whole(total)), 12f)
            }
            budgets["${m.id}"]?.let { lines += c.muted(c.getString(R.string.budget_progress, Fmt.whole(total), Fmt.whole(it)), 12f) }
            Insights.changes(spends.filter { it.memberId == m.id }, now, { Categories.forTxn(a.db, it) }, a.prefs.excludedMonths)
                .firstOrNull { abs(it.second - it.third) >= 50 }?.let { (k, n, u) ->
                    lines += c.muted(c.getString(R.string.h_biggest_change, Categories.label(c, k), Fmt.whole(n), Fmt.whole(u)), 12f)
                }
            lines += c.hbox().apply {
                addView(c.textButton(c.getString(R.string.ppl_budget)) { editBudget(Plan.BUDGET_MEMBER, "${m.id}", m.name) })
                addView(c.textButton(c.getString(R.string.ppl_share)) { share(m, total, cats.take(3).map { Categories.label(c, it.first) }) })
            }
            root.addView(c.card(*lines.toTypedArray()))
        }
        root.addView(c.textButton(c.getString(R.string.ppl_who_am_i, owner?.let { a.db.member(it)?.name } ?: c.getString(R.string.not_set))) {
            c.dialog(c.getString(R.string.ppl_who_am_i_q)).setItems(members.map { it.name }.toTypedArray()) { _, i -> a.prefs.ownerMemberId = members[i].id; a.refresh() }.show()
        })
        root.addView(c.textButton(c.getString(R.string.ppl_family_cats)) { chooseFamilyCategories() })
    }

    private fun share(m: Member, total: Double, cats: List<String>) {
        val msg = a.getString(R.string.ppl_share_msg, m.name, Fmt.whole(total), cats.joinToString(", "))
        a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg), a.getString(R.string.ppl_share)))
    }

    private fun chooseFamilyCategories() {
        val c = a
        val cats = Categories.ALL
        val sel = a.prefs.familyCategories.toMutableSet()
        c.dialog(c.getString(R.string.ppl_family_cats)).setMultiChoiceItems(cats.map { Categories.label(c, it) }.toTypedArray(), cats.map { it in sel }.toBooleanArray()) { _, i, on ->
            if (on) sel += cats[i] else sel -= cats[i]
        }.setPositiveButton(R.string.save) { _, _ -> a.prefs.familyCategories = sel; a.refresh() }.setNegativeButton(R.string.cancel, null).show()
    }

    // ---------- budgets ----------
    private fun budgets(root: LinearLayout, txns: List<Txn>, now: Long) {
        val c = a
        val month = Insights.month(now)
        val spends = txns.filter { it.isSpend && it.ts in month }
        val catBudgets = a.db.budgets(Plan.BUDGET_CATEGORY)
        val bySpend = spends.groupBy { Categories.forTxn(a.db, it) }.mapValues { e -> e.value.sumOf { it.netSpend } }
        val ov = a.prefs.bucketOverrides
        root.addView(c.muted(c.getString(R.string.budget_hint), 12f))
        for (cat in Categories.ALL) {
            val spent = bySpend[cat] ?: 0.0
            val limit = catBudgets[cat]
            if (spent <= 0 && limit == null) continue
            val color = when { limit == null -> null; spent >= limit -> Ui.ALERT; spent >= limit * 0.8 -> Ui.PALETTE[2]; else -> Ui.PALETTE[0] }
            val bucket = Insights.bucketOf(cat, ov)
            root.addView(c.card(
                c.text("${Categories.label(c, cat)} · SR ${Fmt.whole(spent)}", 15f, true, color),
                c.muted(listOfNotNull(
                    limit?.let { c.getString(R.string.budget_progress, Fmt.whole(spent), Fmt.whole(it)) },
                    c.getString(if (bucket == "need") R.string.bucket_need else R.string.bucket_want),
                ).joinToString(" · "), 12f),
                onClick = { categoryActions(cat) },
            ))
        }
        // Targets.
        val p = a.prefs
        root.addView(c.section(c.getString(R.string.targets)))
        val needs = c.field(c.getString(R.string.target_needs), "${p.needsTarget}", InputType.TYPE_CLASS_NUMBER)
        val wants = c.field(c.getString(R.string.target_wants), "${p.wantsTarget}", InputType.TYPE_CLASS_NUMBER)
        val saving = c.field(c.getString(R.string.target_saving), "${p.savingTarget}", InputType.TYPE_CLASS_NUMBER)
        val pace = c.field(c.getString(R.string.pace_threshold), "${p.paceThreshold}", InputType.TYPE_CLASS_NUMBER, c.getString(R.string.pace_helper))
        root.addView(c.card(needs.layout, wants.layout, saving.layout, pace.layout, c.button(c.getString(R.string.save)) {
            needs.value.toIntOrNull()?.let { p.needsTarget = it }
            wants.value.toIntOrNull()?.let { p.wantsTarget = it }
            saving.value.toIntOrNull()?.let { p.savingTarget = it }
            pace.value.toIntOrNull()?.takeIf { it > 0 }?.let { p.paceThreshold = it }
            c.toast(c.getString(R.string.saved))
        }))
        // Unusual months left out of "normal".
        root.addView(c.section(c.getString(R.string.excluded_months)))
        root.addView(c.muted(c.getString(R.string.excluded_months_hint), 12f))
        val chips = c.chips(singleSelection = false)
        val mf = SimpleDateFormat("MMM yyyy", Locale.getDefault())
        val sel = p.excludedMonths.toMutableSet()
        (1..12).map { Insights.month(now, it) }.forEach { m ->
            val k = Insights.monthKey(m.from)
            chips.addView(c.chip(mf.format(Date(m.from)), k in sel) { if (!sel.add(k)) sel.remove(k); p.excludedMonths = sel })
        }
        root.addView(chips)
        // Period.
        root.addView(c.section(c.getString(R.string.period_mode)))
        val pm = c.chips()
        pm.addView(c.chip(c.getString(R.string.period_month), p.periodMode == "month") { p.periodMode = "month" })
        pm.addView(c.chip(c.getString(R.string.period_payday), p.periodMode == "payday") { p.periodMode = "payday" })
        root.addView(pm)
    }

    private fun categoryActions(cat: String) {
        val c = a
        val opts = listOf(R.string.budget_set, R.string.bucket_set_need, R.string.bucket_set_want)
        c.dialog(Categories.label(c, cat)).setItems(opts.map { c.getString(it) }.toTypedArray()) { _, i ->
            when (i) {
                0 -> editBudget(Plan.BUDGET_CATEGORY, cat, Categories.label(c, cat))
                1 -> { a.prefs.bucketOverrides = a.prefs.bucketOverrides + (cat to "need"); a.refresh() }
                2 -> { a.prefs.bucketOverrides = a.prefs.bucketOverrides + (cat to "want"); a.refresh() }
            }
        }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun editBudget(kind: String, key: String, label: String) {
        val c = a
        val box = c.vbox(20)
        val f = c.field(c.getString(R.string.budget_monthly), a.db.budgets(kind)[key]?.let { Fmt.whole(it).replace(",", "") } ?: "", InputType.TYPE_CLASS_NUMBER,
            c.getString(R.string.budget_helper))
        box.addView(f.layout)
        c.dialog(label).setView(box).setPositiveButton(R.string.save) { _, _ ->
            a.db.setBudget(kind, key, f.value.toDoubleOrNull()?.takeIf { it > 0 }); a.refresh()
        }.setNegativeButton(R.string.cancel, null).show()
    }
}
