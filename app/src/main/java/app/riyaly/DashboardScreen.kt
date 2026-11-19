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

    private fun monthStart(monthsBack: Int): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.MONTH, -monthsBack)
    }.timeInMillis

    override fun build(): View {
        val c = a
        val root = c.vbox(16)
        val members = a.db.members()
        val cards = a.db.cards().associateBy { it.last4 }
        val colorOf: Map<Long?, Int> = members.associate { it.id as Long? to it.color }
        val primary = c.attrColor(androidx.appcompat.R.attr.colorPrimary)

        // Setup reminders.
        val missing = a.missingPermissions()
        if (missing.isNotEmpty()) root.addView(c.card(
            c.text(c.getString(R.string.setup_permissions_title), 16f, true),
            c.muted(c.getString(R.string.setup_permissions_body)),
            c.button(c.getString(R.string.grant)) { a.askPermissions() },
        ))
        if (cards.isEmpty()) root.addView(c.card(
            c.text(c.getString(R.string.setup_cards_title), 16f, true),
            c.muted(c.getString(R.string.setup_cards_body)),
            c.button(c.getString(R.string.tab_family)) { a.nav.selectedItemId = 4 },
        ))

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
        tile(row1, "SR " + Fmt.whole(spend.sumOf { it.amount }), c.getString(R.string.t_total)) { a.openList(base) }
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
            val byMember = spend.groupBy { it.memberId }.map { (id, l) -> id to l.sumOf { it.amount } }.sortedByDescending { it.second }
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
            list.filter { it.ts in s until e }.sumOf { it.amount }
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

        if (spend.isEmpty()) return c.scroll(root)

        // Top merchants.
        root.addView(c.section(c.getString(R.string.d_top_merchants)))
        val byMerchant = spend.filter { it.merchant != null && it.kind == Parser.Kind.PURCHASE }.groupBy { it.merchant!! }
            .map { (m, l) -> Triple(m, l.sumOf { it.amount }, l.size) }.sortedByDescending { it.second }.take(8)
        root.addView(c.card(HBarChart(c).apply {
            items = byMerchant.map { (m, v, n) -> HBarChart.Item(m, v, primary, m, "SR ${Fmt.whole(v)} · $n") }
            onTap = { tag -> a.openList(base.copy(merchant = tag as String)) }
        }, c.muted(c.getString(R.string.d_merchant_hint), 12f)))

        // Categories.
        root.addView(c.section(c.getString(R.string.d_categories)))
        val byCat = spend.groupBy { Categories.forTxn(a.db, it) }.map { (k, l) -> k to l.sumOf { it.amount } }.sortedByDescending { it.second }
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
