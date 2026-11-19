package app.riyaly

import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val rowDate = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

fun matches(a: MainActivity, t: Txn, f: TxnFilter, query: String): Boolean {
    if (t.ts < f.from || t.ts >= f.to) return false
    if (f.unidentified && t.memberId != null) return false
    if (f.memberId != null && t.memberId != f.memberId) return false
    if (f.card != null && t.card != f.card) return false
    if (f.kind != null && t.kind != f.kind) return false
    if (f.counterparty != null && t.counterparty != f.counterparty) return false
    if (f.merchant != null && !t.merchant.equals(f.merchant, true)) return false
    if (f.category != null && (!t.isSpend || Categories.forTxn(a.db, t) != f.category)) return false
    if (f.day != null || f.hour != null) {
        val c = Calendar.getInstance().apply { timeInMillis = t.ts }
        if (f.day != null && c.get(Calendar.DAY_OF_WEEK) - 1 != f.day) return false
        if (f.hour != null && c.get(Calendar.HOUR_OF_DAY) != f.hour) return false
    }
    if (f.day != null || f.hour != null || f.merchant != null) {
        if (!t.isSpend) return false
    }
    if (query.isNotBlank()) {
        val q = query.lowercase()
        if (listOfNotNull(t.merchant, t.body, t.counterparty, Notifier.who(a, t)).none { it.lowercase().contains(q) }) return false
    }
    return true
}

fun kindColor(kind: String): Int = when (kind) {
    Parser.Kind.TRANSFER_IN, Parser.Kind.REFUND, Parser.Kind.CREDIT_IN -> Ui.PALETTE[0]
    Parser.Kind.TRANSFER_OUT -> Ui.PALETTE[4]
    Parser.Kind.WITHDRAWAL -> Ui.PALETTE[2]
    else -> Ui.GREY
}

private fun View.withGap(c: Context) {
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = c.dp(6) }
}

fun txnRow(a: MainActivity, t: Txn, onChanged: () -> Unit): View {
    val c: Context = a
    val member = a.db.member(t.memberId)
    val top = c.hbox()
    top.addView(c.colorDot(member?.color ?: Ui.GREY))
    top.add(c.text(Notifier.who(a, t), 15f, true), weight = 1f)
    val amountColor = if (t.isCredit) Ui.PALETTE[0] else null
    top.addView(c.text((if (t.isCredit) "+" else "") + Fmt.money(t.amount, t.currency), 15f, true, amountColor))
    val party = when (t.kind) {
        Parser.Kind.TRANSFER_IN -> t.counterparty?.let { c.getString(R.string.from_party, it) }
        Parser.Kind.TRANSFER_OUT -> t.counterparty?.let { c.getString(R.string.to_party, it) }
        else -> t.merchant
    }
    val line2 = listOfNotNull(party, rowDate.format(Date(t.ts))).joinToString(" · ")
    val views = mutableListOf<View>(top, c.muted(line2))
    val badges = c.hbox()
    if (t.kind != Parser.Kind.PURCHASE) badges.addView(c.badge(Analyzer.kindLabel(c, t.kind), kindColor(t.kind)).apply { withGap(c) })
    if (t.duringTravel) badges.addView(c.badge("✈ " + c.getString(R.string.during_travel), Ui.PALETTE[1]).apply { withGap(c) })
    if (t.isDeclined) badges.addView(c.badge(c.getString(R.string.declined) + (t.declineReason?.let { ": $it" } ?: ""), Ui.ALERT))
    if (badges.childCount > 0) views += badges
    if (t.reasons.isNotEmpty()) {
        val color = when (t.alert) { AlertState.OK -> Ui.GREY; AlertState.CHECK -> Ui.PALETTE[2]; else -> Ui.ALERT }
        views += c.badge(Xlsx.statusLabel(c, t) + ": " + t.reasons.joinToString(", ") { Analyzer.label(c, it) }, color)
    }
    return c.card(*views.toTypedArray(), onClick = { TxnDetail.show(a, t, onChanged) })
}

class TxnListScreen(a: MainActivity) : Screen(a) {
    override val titleRes = R.string.tab_transactions
    private var query = ""
    private var limit = 200

    override fun build(): View {
        val c = a
        val root = c.vbox(16)
        val f = a.filter
        if (f != TxnFilter()) {
            val parts = listOfNotNull(
                f.memberId?.let { a.db.member(it)?.name }, if (f.unidentified) c.getString(R.string.unidentified) else null,
                f.card?.let { a.db.card(it)?.display() ?: it }, f.kind?.let { Analyzer.kindLabel(c, it) }, f.counterparty,
                f.merchant, f.category?.let { Categories.label(c, it) }, f.label,
            )
            val bar = c.hbox()
            bar.add(c.muted(c.getString(R.string.filtered_by, parts.joinToString(" · ").ifEmpty { c.getString(R.string.period) })), weight = 1f)
            bar.addView(c.textButton(c.getString(R.string.clear)) { a.filter = TxnFilter(); a.refresh() })
            root.addView(bar)
        }
        val search = c.field(c.getString(R.string.search_hint), query)
        root.addView(search.layout)
        val listBox = c.vbox()
        root.addView(listBox)

        fun fill() {
            listBox.removeAllViews()
            val items = a.db.txns().filter { matches(a, it, f, query) }
            if (items.isEmpty()) { listBox.addView(c.muted(c.getString(R.string.no_transactions))); return }
            val spend = items.filter { it.isSpend }
            val moneyIn = items.filter { it.isMoneyIn }.sumOf { it.amount }
            val out = items.filter { it.isTransferOut }.sumOf { it.amount }
            val line = c.getString(R.string.list_total, items.size, Fmt.money(spend.sumOf { it.amount }, "SAR")) +
                (if (moneyIn > 0) " · " + c.getString(R.string.list_in, Fmt.money(moneyIn, "SAR")) else "") +
                (if (out > 0) " · " + c.getString(R.string.list_out, Fmt.money(out, "SAR")) else "")
            listBox.addView(c.muted(line).apply {
                setPadding(0, c.dp(4), 0, c.dp(8))
            })
            items.take(limit).forEach { listBox.addView(txnRow(a, it) { a.refresh() }) }
            if (items.size > limit) listBox.addView(c.button(c.getString(R.string.show_more), outlined = true) { limit += 200; fill() })
        }
        fill()
        search.edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, co: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, be: Int, co: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; fill() }
        })
        return c.scroll(root)
    }
}

class AlertsScreen(a: MainActivity) : Screen(a) {
    override val titleRes = R.string.tab_alerts

    override fun build(): View {
        val c = a
        val root = c.vbox(16)
        val open = a.db.openAlerts()
        if (open.isEmpty()) {
            root.addView(c.card(c.text(c.getString(R.string.no_alerts), 16f, true), c.muted(c.getString(R.string.no_alerts_body))))
        }
        for (t in open) {
            val row = txnRow(a, t) { a.refresh() }
            root.addView(row)
            val actions = c.hbox()
            if (t.match == Match.AMBIGUOUS || t.match == Match.UNKNOWN) {
                actions.addView(c.button(c.getString(R.string.choose_person), outlined = true) { TxnDetail.choosePerson(a, t) { a.refresh() } })
            }
            if (t.alert == AlertState.OPEN || t.alert == AlertState.CHECK) {
                actions.addView(c.button(c.getString(R.string.btn_confirmed_ok)) { confirmOk(a, t); a.refresh() })
                actions.addView(c.textButton(c.getString(R.string.btn_check_member)) { TxnDetail.checkWithMember(a, t); a.refresh() })
            }
            root.addView(actions)
        }
        val past = a.db.txns(System.currentTimeMillis() - 60L * 24 * 3600 * 1000).filter { it.alert == AlertState.OK && it.reasons.isNotEmpty() }
        if (past.isNotEmpty()) {
            root.addView(c.section(c.getString(R.string.recently_confirmed)))
            past.take(30).forEach { root.addView(txnRow(a, it) { a.refresh() }) }
        }
        return c.scroll(root)
    }
}

object TxnDetail {
    fun show(a: MainActivity, t0: Txn, onChanged: () -> Unit = { a.refresh() }) {
        val t = a.db.txn(t0.id) ?: t0
        val c: Context = a
        val box = c.vbox(20)
        fun row(label: Int, value: String?) {
            if (value.isNullOrBlank()) return
            val r = c.hbox()
            r.add(c.muted(c.getString(label)), weight = 0.38f)
            r.add(c.text(value), weight = 0.62f)
            r.setPadding(0, c.dp(3), 0, c.dp(3))
            box.addView(r)
        }
        row(R.string.x_member, Notifier.person(a, t))
        row(R.string.x_card, a.db.card(t.card)?.display() ?: t.card)
        row(R.string.x_kind, Analyzer.kindLabel(c, t.kind))
        row(R.string.x_amount, (if (t.isCredit) "+" else "") + Fmt.money(t.amount, t.currency))
        if (t.origAmount != null) row(R.string.x_original, Fmt.money(t.origAmount, t.origCurrency ?: "SAR"))
        row(R.string.x_fees, t.fees?.let { Fmt.money(it, "SAR") })
        row(R.string.x_from_to, when (t.kind) {
            Parser.Kind.TRANSFER_IN -> c.getString(R.string.from_party, listOfNotNull(t.counterparty, t.counterpartyAcct).joinToString(" · "))
            Parser.Kind.TRANSFER_OUT -> c.getString(R.string.to_party, listOfNotNull(t.counterparty, t.counterpartyAcct).joinToString(" · "))
            else -> null
        })
        row(R.string.x_merchant, t.merchant)
        if (t.isSpend) row(R.string.x_category, Categories.label(c, Categories.forTxn(a.db, t)))
        row(R.string.x_country, t.country)
        row(R.string.x_decline_reason, t.declineReason)
        if (t.duringTravel) row(R.string.x_travel, "✈ " + c.getString(R.string.during_travel))
        row(R.string.x_method, t.method)
        row(R.string.x_type, t.type)
        row(R.string.x_date, rowDate.format(Date(t.ts)))
        row(R.string.x_balance, t.balance?.let { Fmt.money(it, "SAR") })
        row(R.string.x_city, t.city)
        row(R.string.x_distance, t.distanceKm?.let { c.getString(R.string.km_from_home, Math.round(it).toInt()) })
        row(R.string.x_status, if (t.reasons.isEmpty() && t.match != Match.AMBIGUOUS) null else Xlsx.statusLabel(c, t))
        if (t.reasons.isNotEmpty()) row(R.string.x_alerts, t.reasons.joinToString("\n") { "• " + Analyzer.label(c, it) })
        box.addView(c.section(c.getString(R.string.x_message)))
        box.addView(c.text(t.body, 14f).apply { setTextIsSelectable(true) })

        val actions = c.vbox()
        val dlg = c.dialog(Fmt.money(t.amount, t.currency)).setView(c.scroll(c.vbox().apply { addView(box); addView(actions) }))
            .setPositiveButton(R.string.close, null).create()
        fun act(label: Int, outlined: Boolean = true, f: () -> Unit) {
            actions.addView(c.button(c.getString(label), outlined) { dlg.dismiss(); f(); onChanged() }.apply {
                (layoutParams as? LinearLayout.LayoutParams)?.marginStart = c.dp(20)
            })
        }
        actions.setPadding(c.dp(20), 0, c.dp(20), c.dp(8))
        if (t.alert == AlertState.OPEN || t.alert == AlertState.CHECK) {
            act(R.string.btn_confirmed_ok, false) { confirmOk(a, t) }
            act(R.string.btn_check_member) { checkWithMember(a, t) }
        }
        act(if (t.match == Match.IDENTIFIED) R.string.change_person else R.string.choose_person) { choosePerson(a, t, onChanged) }
        if (t.merchant != null && t.kind == Parser.Kind.PURCHASE) act(R.string.change_category) { chooseCategory(a, t, onChanged) }
        act(R.string.not_txn) {
            c.confirm(c.getString(R.string.not_txn), c.getString(R.string.not_txn_q), c.getString(R.string.remove)) {
                Processor.markNotTransaction(a, t); onChanged()
            }
        }
        act(R.string.delete_txn) { c.confirm(c.getString(R.string.delete_txn), c.getString(R.string.delete_txn_q), c.getString(R.string.delete)) { a.db.deleteTxn(t.id); onChanged() } }
        dlg.show()
    }

    fun choosePerson(a: MainActivity, t: Txn, onChanged: () -> Unit) {
        val members = if (t.match == Match.AMBIGUOUS && t.candidates.isNotEmpty())
            t.candidates.mapNotNull { a.db.member(it) } + a.db.members().filter { it.id !in t.candidates }
        else a.db.members()
        if (members.isEmpty()) { a.toast(a.getString(R.string.add_member_first)); return }
        a.dialog(a.getString(R.string.choose_person)).setItems(members.map { it.name }.toTypedArray()) { _, i ->
            val m = members[i]
            Processor.assign(a, t, m.id)
            val card = t.card
            if (t.match == Match.UNKNOWN && card != null && a.db.cards().none { it.last4 == card }) {
                a.confirm(a.getString(R.string.register_card_q_title), a.getString(R.string.register_card_q, card, m.name), a.getString(R.string.register)) {
                    a.db.saveCard(card, m.id); Inbox.reidentify(a); onChanged()
                }
            }
            onChanged()
        }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun chooseCategory(a: MainActivity, t: Txn, onChanged: () -> Unit) {
        val merchant = t.merchant ?: return
        val cats = Categories.ALL
        a.dialog(a.getString(R.string.category_for, merchant)).setItems(cats.map { Categories.label(a, it) }.toTypedArray()) { _, i ->
            a.db.setMerchantCategory(merchant, cats[i]); onChanged()
        }.setNegativeButton(R.string.cancel, null).show()
    }

    /** Marks as "checking" and opens the share sheet with a ready question for the family member. */
    fun checkWithMember(a: MainActivity, t: Txn) {
        a.db.updateTxn(t.copy(alert = AlertState.CHECK))
        val msg = a.getString(R.string.check_message, Fmt.money(t.amount, t.currency), t.merchant ?: "-", rowDate.format(Date(t.ts)), t.card ?: "-")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg)
        a.startActivity(Intent.createChooser(send, a.getString(R.string.btn_check_member)))
        a.refresh()
    }
}
