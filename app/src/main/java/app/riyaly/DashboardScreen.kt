package app.riyaly

import android.view.View
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DashboardScreen(a: MainActivity) : Screen(a) {
    override val titleRes = R.string.tab_dashboard
    private var period = 0          // 0 this month, 1 last 3 months, 2 last 12 months
    private var memberSel: Long? = null
    /** "More charts" page. */
    var more = false

    override fun titleText(): String? = if (more) a.getString(R.string.more_charts) else null

    private fun monthStart(monthsBack: Int): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.MONTH, -monthsBack)
    }.timeInMillis

    override fun build(): View = if (more) buildCharts() else buildHome()

    private fun pct(v: Double?): String = v?.let { (if (it >= 0) "+" else "−") + Math.round(kotlin.math.abs(it) * 100) + "%" } ?: "—"

    /** The short home page: the few numbers that matter, then "More charts". */
    private fun buildHome(): View {
        val c = a
        val root = c.vbox(16)
        val missing = a.missingPermissions()
        if (missing.isNotEmpty()) root.addView(c.card(
            c.text(c.getString(R.string.setup_permissions_title), 16f, true),
            c.muted(c.getString(R.string.setup_permissions_body)),
            c.button(c.getString(R.string.grant)) { a.askPermissions() },
        ))
        if (a.db.cards().isEmpty()) root.addView(c.card(
            c.text(c.getString(R.string.setup_cards_title), 16f, true),
            c.muted(c.getString(R.string.setup_cards_body)),
            c.button(c.getString(R.string.tab_family)) { a.nav.selectedItemId = 4 },
        ))
        val now = System.currentTimeMillis()
        val txns = a.db.txns(now - 400 * Insights.DAY)
        val period = Plan.period(c, txns, now)
        val gap = c.dp(12)

        // Safe to spend today.
        val safe = Plan.safe(c, txns, now)
        if (safe != null) {
            val color = when (Plan.safeColor(safe, Plan.spentToday(txns, now))) { "red" -> Ui.ALERT; "amber" -> Ui.PALETTE[2]; else -> Ui.PALETTE[0] }
            val value = if (safe.available <= 0) c.getString(R.string.safe_none) else "SR " + Fmt.whole(safe.daily)
            root.addView(c.card(
                c.muted(c.getString(R.string.safe_title)),
                c.text(value, 28f, true, color),
                c.muted(c.getString(R.string.safe_sub, safe.daysLeft, Fmt.whole(safe.billsDue))),
                onClick = { a.nav.selectedItemId = 6 },
            ))
        } else root.addView(c.card(
            c.text(c.getString(R.string.safe_title), 16f, true),
            c.muted(c.getString(R.string.safe_setup)),
            onClick = { a.nav.selectedItemId = 6 },
        ))

        fun tile(row: LinearLayout, value: String, label: String, color: Int? = null, onClick: () -> Unit) {
            val v = c.card(c.text(value, 19f, true, color), c.muted(label, 12f), onClick = onClick)
            (v.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; marginEnd = c.dp(8); bottomMargin = gap }
            row.addView(v)
        }
        val flow = Insights.flow(txns, period)
        val cmp = Insights.compare(txns.filter { it.isSpend }, now, a.prefs.excludedMonths)
        val base = TxnFilter(from = period.from, to = period.to)
        val row1 = c.hbox()
        tile(row1, "SR " + Fmt.whole(flow.spend), c.getString(R.string.t_spent_vs, pct(cmp.vsLast))) { a.openList(base) }
        tile(row1, "+SR " + Fmt.whole(flow.income), c.getString(R.string.t_money_in), Ui.PALETTE[0]) { a.openList(base.copy(kind = Parser.Kind.TRANSFER_IN)) }
        root.addView(row1)
        val row2 = c.hbox()
        val net = flow.net
        tile(row2, (if (net >= 0) "+" else "−") + "SR " + Fmt.whole(kotlin.math.abs(net)),
            c.getString(R.string.t_net, flow.savingRate?.let { Math.round(it * 100).toString() + "%" } ?: "—"),
            if (net >= 0) Ui.PALETTE[0] else Ui.ALERT) { more = true; a.refresh() }
        val open = a.db.openAlerts().size
        tile(row2, "$open", c.getString(R.string.t_alerts), if (open > 0) Ui.ALERT else null) { a.nav.selectedItemId = 3 }
        root.addView(row2)
        val row3 = c.hbox()
        val dups = txns.count { it.dupState == DupState.OPEN || it.dupState == DupState.CONFIRMED }
        tile(row3, "$dups", c.getString(R.string.t_duplicates), if (dups > 0) Ui.PALETTE[2] else null) { a.plan().openSub("duplicates") }
        tile(row3, "SR " + Fmt.whole(flow.fees), c.getString(R.string.t_fees)) { a.plan().openSub("fees") }
        root.addView(row3)

        // Where the money went: needs / wants / saving.
        if (flow.income > 0 || flow.spend > 0) {
            val sp = Plan.split(c, txns, period)
            val total = (sp.needs + sp.wants + sp.saving).coerceAtLeast(1.0)
            fun share(v: Double) = Math.round(v / total * 100).toInt()
            root.addView(c.section(c.getString(R.string.h_where)))
            root.addView(c.card(HBarChart(c).apply {
                items = listOf(
                    HBarChart.Item(c.getString(R.string.b_needs, a.prefs.needsTarget), sp.needs, Ui.PALETTE[1], "need", "${share(sp.needs)}% · SR ${Fmt.whole(sp.needs)}"),
                    HBarChart.Item(c.getString(R.string.b_wants, a.prefs.wantsTarget), sp.wants, Ui.PALETTE[2], "want", "${share(sp.wants)}% · SR ${Fmt.whole(sp.wants)}"),
                    HBarChart.Item(c.getString(R.string.b_saving, a.prefs.savingTarget), sp.saving, Ui.PALETTE[0], "saving", "${share(sp.saving)}% · SR ${Fmt.whole(sp.saving)}"),
                )
                onTap = { more = true; a.refresh() }
            }, c.muted(c.getString(R.string.h_where_hint), 12f)))
        }

        // This month vs normal, and why.
        if (cmp.normalSameDay > 0) {
            val lines = mutableListOf(c.getString(R.string.h_vs_normal, pct(cmp.vsNormal), Fmt.whole(cmp.projected), Fmt.whole(cmp.normalMonth)))
            Plan.biggestChange(c, txns, now)?.let { (cat, nowV, normV) ->
                lines += c.getString(R.string.h_biggest_change, Categories.label(c, cat), Fmt.whole(nowV), Fmt.whole(normV))
            }
            root.addView(c.section(c.getString(R.string.h_month)))
            root.addView(c.card(*lines.map { c.text(it, 14f) }.toTypedArray(), onClick = { more = true; a.refresh() }))
        }

        // Next payments.
        val upcoming = Plan.recurring(c, txns).filter { it.nextTs in now..(now + 7 * Insights.DAY) }.sortedBy { it.nextTs }
        if (upcoming.isNotEmpty()) {
            root.addView(c.section(c.getString(R.string.h_upcoming)))
            val df = SimpleDateFormat("EEE d MMM", Locale.getDefault())
            root.addView(c.card(*upcoming.take(4).map { c.text("${df.format(it.nextTs)} · ${it.name} · SR ${Fmt.whole(it.lastAmount)}", 14f) }.toTypedArray(),
                onClick = { a.plan().openSub("recurring") }))
        }

        if (txns.isEmpty()) root.addView(c.card(c.muted(c.getString(R.string.no_data_yet))))
        root.addView(c.button(c.getString(R.string.more_charts), outlined = true) { more = true; a.refresh() }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = gap
        })
        return c.scroll(root)
    }

    private fun buildCharts(): View {
        val c = a
        val root = c.vbox(16)
        val members = a.db.members()
        val cards = a.db.cards().associateBy { it.last4 }
        val colorOf: Map<Long?, Int> = members.associate { it.id as Long? to it.color }
        val primary = c.attrColor(androidx.appcompat.R.attr.colorPrimary)

        // Filters.
        val periods = c.chips()
        listOf(R.string.p_this_month, R.string.p_3_months, R.string.p_12_months).forEachIndexed { i, s ->
            periods.addView(c.chip(c.getString(s), period == i) { period = i; a.refresh() })
        }
        root.addView(periods)
        if (members.isNotEmpty()) {
            val mg = c.chips()
            mg.addView(c.chip(c.getString(R.string.all_members), memberSel == null) { memberSel = null; a.refresh() })
            members.forEach { m -> mg.addView(c.chip(m.name, memberSel == m.id) { memberSel = m.id; a.refresh() }) }
            root.addView(mg)
        }

        val from = monthStart(when (period) { 0 -> 0; 1 -> 2; else -> 11 })
        val all = a.db.txns(from).filter { memberSel == null || it.memberId == memberSel }
        val spend = all.filter { it.isSpend }
        val moneyIn = all.filter { it.isMoneyIn }
        val transfersOut = all.filter { it.isTransferOut }
        val base = TxnFilter(memberId = memberSel, from = from)

        // Summary tiles.
        fun tile(row: LinearLayout, value: String, label: String, color: Int? = null, onClick: () -> Unit) {
            val v = c.card(c.text(value, 19f, true, color), c.muted(label, 12f), onClick = onClick)
            (v.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; marginEnd = c.dp(6) }
            row.addView(v)
        }
        val open = a.db.openAlerts().count { memberSel == null || it.memberId == memberSel }
        val row1 = c.hbox()
        tile(row1, "SR " + Fmt.whole(spend.sumOf { it.netSpend }), c.getString(R.string.t_total)) { a.openList(base) }
        tile(row1, "${spend.size}", c.getString(R.string.t_count)) { a.openList(base) }
        tile(row1, "$open", c.getString(R.string.t_alerts), if (open > 0) Ui.ALERT else null) { a.nav.selectedItemId = 3 }
        root.addView(row1)
        val row2 = c.hbox()
        tile(row2, "+SR " + Fmt.whole(moneyIn.sumOf { it.amount }), c.getString(R.string.t_money_in), Ui.PALETTE[0]) {
            a.openList(base.copy(kind = Parser.Kind.TRANSFER_IN))
        }
        tile(row2, "SR " + Fmt.whole(transfersOut.sumOf { it.amount }), c.getString(R.string.t_transfers_out), Ui.PALETTE[4]) {
            a.openList(base.copy(kind = Parser.Kind.TRANSFER_OUT))
        }
        root.addView(row2)

        if (all.isEmpty()) {
            root.addView(c.card(c.muted(c.getString(R.string.no_data_yet))))
            return c.scroll(root)
        }

        // By member.
        if (memberSel == null && spend.isNotEmpty()) {
            root.addView(c.section(c.getString(R.string.d_by_member)))
            val byMember = spend.groupBy { it.memberId }.map { (id, l) -> id to l.sumOf { it.netSpend } }.sortedByDescending { it.second }
            root.addView(c.card(HBarChart(c).apply {
                items = byMember.map { (id, v) ->
                    HBarChart.Item(a.db.member(id)?.name ?: c.getString(R.string.unidentified), v, colorOf[id] ?: Ui.GREY, id, "SR " + Fmt.whole(v))
                }
                onTap = { tag -> a.openList(if (tag == null) base.copy(unidentified = true) else base.copy(memberId = tag as Long)) }
            }))
        }

        // By card or account (spending + transfers out).
        val outByCard = (spend + transfersOut).filter { it.card != null }.groupBy { it.card!! }
            .map { (k, l) -> k to l.sumOf { it.amount } }.sortedByDescending { it.second }
        if (outByCard.isNotEmpty()) {
            root.addView(c.section(c.getString(R.string.d_by_card)))
            root.addView(c.card(HBarChart(c).apply {
                items = outByCard.map { (k, v) ->
                    val card = cards[k]
                    val owner = card?.let { a.db.member(it.memberId)?.name }
                    val name = listOfNotNull(owner, card?.display() ?: "•••• $k").joinToString(" · ")
                    HBarChart.Item(name, v, colorOf[card?.memberId] ?: Ui.GREY, k, "SR " + Fmt.whole(v))
                }
                onTap = { tag -> a.openList(base.copy(card = tag as String)) }
            }, c.muted(c.getString(R.string.d_by_card_hint), 12f)))
        }

        // Monthly money in vs out, and spending trend.
        val months = if (period == 2) 12 else 6
        val mf = SimpleDateFormat("MMM", Locale.getDefault())
        val starts = (months - 1 downTo 0).map { monthStart(it) }
        val trendAll = a.db.txns(starts.first()).filter { memberSel == null || it.memberId == memberSel }
        fun bucket(list: List<Txn>) = starts.mapIndexed { i, s ->
            val e = if (i + 1 < starts.size) starts[i + 1] else Long.MAX_VALUE
            list.filter { it.ts in s until e }.sumOf { if (it.isSpend) it.netSpend else it.amount }
        }
        root.addView(c.section(c.getString(R.string.d_in_out)))
        root.addView(c.card(LineChart(c).apply {
            labels = starts.map { mf.format(it) }
            series = listOf(
                LineChart.Series(c.getString(R.string.t_money_in), Ui.PALETTE[0], bucket(trendAll.filter { it.isMoneyIn })),
                LineChart.Series(c.getString(R.string.t_spending), Ui.PALETTE[2], bucket(trendAll.filter { it.isSpend })),
                LineChart.Series(c.getString(R.string.t_transfers_out), Ui.PALETTE[4], bucket(trendAll.filter { it.isTransferOut })),
            )
            onTap = { i ->
                val e = if (i + 1 < starts.size) starts[i + 1] else Long.MAX_VALUE
                a.openList(TxnFilter(memberId = memberSel, from = starts[i], to = e, label = labels[i]))
            }
        }))
        if (memberSel == null) {
            val spendTrend = trendAll.filter { it.isSpend }
            val ids = spendTrend.mapNotNull { it.memberId }.distinct()
            if (ids.size > 1) {
                root.addView(c.section(c.getString(R.string.d_trend)))
                root.addView(c.card(LineChart(c).apply {
                    labels = starts.map { mf.format(it) }
                    series = ids.map { id -> LineChart.Series(a.db.member(id)?.name ?: "?", colorOf[id] ?: Ui.GREY, bucket(spendTrend.filter { it.memberId == id })) }
                    onTap = { i ->
                        val e = if (i + 1 < starts.size) starts[i + 1] else Long.MAX_VALUE
                        a.openList(TxnFilter(from = starts[i], to = e, label = labels[i]))
                    }
                }))
            }
        }

        // Transfer parties.
        val parties = (moneyIn + transfersOut).filter { it.counterparty != null && it.kind in setOf(Parser.Kind.TRANSFER_IN, Parser.Kind.TRANSFER_OUT) }
        if (parties.isNotEmpty()) {
            root.addView(c.section(c.getString(R.string.d_parties)))
            val byParty = parties.groupBy { it.counterparty!! }.map { (p, l) ->
                Triple(p, l.filter { it.kind == Parser.Kind.TRANSFER_IN }.sumOf { it.amount }, l.filter { it.kind == Parser.Kind.TRANSFER_OUT }.sumOf { it.amount })
            }.sortedByDescending { it.second + it.third }.take(8)
            root.addView(c.card(HBarChart(c).apply {
                items = byParty.map { (p, inn, out) ->
                    val txt = listOfNotNull(if (inn > 0) "+${Fmt.whole(inn)}" else null, if (out > 0) "−${Fmt.whole(out)}" else null).joinToString(" / ")
                    HBarChart.Item(p, inn + out, if (inn >= out) Ui.PALETTE[0] else Ui.PALETTE[4], p, txt)
                }
                onTap = { tag -> a.openList(base.copy(counterparty = tag as String)) }
            }, c.muted(c.getString(R.string.d_parties_hint), 12f)))
        }

        // Income sources.
        val src = Insights.incomeSources(all, Insights.Period(from, Long.MAX_VALUE))
        if (src.salary + src.people + src.refunds + src.other > 0) {
            root.addView(c.section(c.getString(R.string.d_income_sources)))
            root.addView(c.card(HBarChart(c).apply {
                items = listOf(
                    HBarChart.Item(c.getString(R.string.inc_salary), src.salary, Ui.PALETTE[0], "salary", "SR " + Fmt.whole(src.salary)),
                    HBarChart.Item(c.getString(R.string.inc_people), src.people, Ui.PALETTE[1], "people", "SR " + Fmt.whole(src.people)),
                    HBarChart.Item(c.getString(R.string.inc_refunds), src.refunds, Ui.PALETTE[5], "refunds", "SR " + Fmt.whole(src.refunds)),
                    HBarChart.Item(c.getString(R.string.inc_other), src.other, Ui.GREY, "other", "SR " + Fmt.whole(src.other)),
                ).filter { it.value > 0 }
                onTap = { tag -> if (tag == "refunds") a.openList(base.copy(kind = Parser.Kind.REFUND)) else a.openList(base.copy(kind = Parser.Kind.TRANSFER_IN)) }
            }))
        }

        // Balance trend (latest SMS balance each day of the chosen accounts).
        val chosen = a.prefs.balanceCards
        val balPoints = a.db.txns(monthStart(5)).filter { it.balance != null && it.card != null && (chosen.isEmpty() || it.card in chosen) }
        if (balPoints.size >= 3) {
            val bf = SimpleDateFormat("d MMM", Locale.getDefault())
            val weeks = (11 downTo 0).map { System.currentTimeMillis() - it * 14 * Insights.DAY }
            val values = weeks.map { w -> balPoints.filter { it.ts <= w }.groupBy { it.card }.values.sumOf { l -> l.maxByOrNull { it.ts }!!.balance!! } }
            root.addView(c.section(c.getString(R.string.d_balance)))
            root.addView(c.card(LineChart(c).apply {
                labels = weeks.map { bf.format(it) }
                series = listOf(LineChart.Series(c.getString(R.string.d_balance), Ui.PALETTE[1], values))
            }))
        }

        if (spend.isEmpty()) return c.scroll(root)

        // Small purchases that add up.
        val small = a.prefs.smallThreshold.toDouble()
        val habits = Insights.habits(spend, Insights.Period(from, Long.MAX_VALUE), System.currentTimeMillis(), small) { it.merchant ?: "?" }.take(6)
        if (habits.isNotEmpty()) {
            val smallTotal = habits.sumOf { it.total }
            root.addView(c.section(c.getString(R.string.d_small, Fmt.whole(small))))
            root.addView(c.card(HBarChart(c).apply {
                items = habits.map { h -> HBarChart.Item(h.group, h.total, Ui.PALETTE[3], h.group, "${h.count}× · SR ${Fmt.whole(h.total)}") }
                onTap = { tag -> a.openList(base.copy(merchant = tag as String)) }
            }, c.muted(c.getString(R.string.d_small_hint, Fmt.whole(smallTotal), Fmt.whole(habits.sumOf { it.yearPace })), 12f)))
        }

        // Top merchants.
        root.addView(c.section(c.getString(R.string.d_top_merchants)))
        val byMerchant = spend.filter { it.merchant != null && it.kind == Parser.Kind.PURCHASE }.groupBy { it.merchant!! }
            .map { (m, l) -> Triple(m, l.sumOf { it.netSpend }, l.size) }.sortedByDescending { it.second }.take(8)
        root.addView(c.card(HBarChart(c).apply {
            items = byMerchant.map { (m, v, n) -> HBarChart.Item(m, v, primary, m, "SR ${Fmt.whole(v)} · $n") }
            onTap = { tag -> a.openList(base.copy(merchant = tag as String)) }
        }, c.muted(c.getString(R.string.d_merchant_hint), 12f)))

        // Categories.
        root.addView(c.section(c.getString(R.string.d_categories)))
        val byCat = spend.groupBy { Categories.forTxn(a.db, it) }.map { (k, l) -> k to l.sumOf { it.netSpend } }.sortedByDescending { it.second }
        root.addView(c.card(HBarChart(c).apply {
            items = byCat.mapIndexed { i, (k, v) -> HBarChart.Item(Categories.label(c, k), v, Ui.PALETTE[i % Ui.PALETTE.size], k, "SR " + Fmt.whole(v)) }
            onTap = { tag -> a.openList(base.copy(category = tag as String)) }
        }))

        // Time-of-day pattern.
        root.addView(c.section(c.getString(R.string.d_when)))
        val counts = Array(7) { IntArray(24) }
        val cal = Calendar.getInstance()
        spend.forEach { cal.timeInMillis = it.ts; counts[cal.get(Calendar.DAY_OF_WEEK) - 1][cal.get(Calendar.HOUR_OF_DAY)]++ }
        root.addView(c.card(Heatmap(c).apply {
            this.counts = counts
            dayLabels = c.resources.getStringArray(R.array.days_short).toList()
            color = memberSel?.let { colorOf[it] } ?: primary
            onTap = { d, h -> a.openList(base.copy(day = d, hour = h, label = "${dayLabels[d]} $h:00")) }
        }, c.muted(c.getString(R.string.d_when_hint), 12f)))

        // Alerts overview.
        root.addView(c.section(c.getString(R.string.d_alerts)))
        val flagged = all.filter { it.reasons.isNotEmpty() }
        root.addView(c.card(
            c.text(c.getString(R.string.d_alerts_line, flagged.count { it.alert == AlertState.OPEN }, flagged.count { it.alert == AlertState.CHECK },
                flagged.count { it.alert == AlertState.OK })),
            c.textButton(c.getString(R.string.open_alerts)) { a.nav.selectedItemId = 3 },
        ))
        return c.scroll(root)
    }
}
