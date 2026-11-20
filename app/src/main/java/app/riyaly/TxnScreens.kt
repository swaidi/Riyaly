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
        else -> t.merchant?.let { a.db.merchant(it)?.displayName ?: it }
    }
    val line2 = listOfNotNull(party, rowDate.format(Date(t.ts))).joinToString(" · ")
    val views = mutableListOf<View>(top, c.muted(line2))
    val badges = c.hbox()
    if (t.kind != Parser.Kind.PURCHASE) badges.addView(c.badge(Analyzer.kindLabel(c, t.kind), kindColor(t.kind)).apply { withGap(c) })
    if (t.duringTravel) badges.addView(c.badge("✈ " + c.getString(R.string.during_travel), Ui.PALETTE[1]).apply { withGap(c) })
    if (t.isDeclined) badges.addView(c.badge(c.getString(R.string.declined) + (t.declineReason?.let { ": $it" } ?: ""), Ui.ALERT))
    if (t.refunded > 0.01) badges.addView(c.badge(c.getString(if (t.netSpend < 0.01) R.string.b_refunded else R.string.b_part_refunded), Ui.PALETTE[0]).apply { withGap(c) })
    if (t.internal) badges.addView(c.badge(c.getString(R.string.b_internal), Ui.GREY).apply { withGap(c) })
    if (t.incomeType == "salary") badges.addView(c.badge(c.getString(R.string.inc_salary), Ui.PALETTE[0]).apply { withGap(c) })
    if (badges.childCount > 0) views += badges
    if (!t.note.isNullOrBlank()) views += c.muted("✎ " + t.note, 12f)
    if (t.reasons.isNotEmpty() && t.risk != Risk.LOW) {
        val color = when {
            t.alert == AlertState.OK -> Ui.GREY
            t.alert == AlertState.CHECK -> Ui.PALETTE[2]
            t.risk == Risk.MEDIUM -> Ui.PALETTE[2]
            else -> Ui.ALERT
        }
        val level = when (t.risk) { Risk.MEDIUM -> c.getString(R.string.risk_medium); Risk.HIGH -> c.getString(R.string.risk_high); else -> null }
        views += c.badge(listOfNotNull(level, Xlsx.statusLabel(c, t)).joinToString(" · ") + ": " + t.reasons.joinToString(", ") { Analyzer.label(c, it) }, color)
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
            val line = c.getString(R.string.list_total, items.size, Fmt.money(spend.sumOf { it.netSpend }, "SAR")) +
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
        val high = open.filter { it.risk != Risk.MEDIUM }
        val medium = open.filter { it.risk == Risk.MEDIUM }
        fun list(items: List<Txn>) = items.forEach { t ->
            root.addView(txnRow(a, t) { a.refresh() })
            val actions = c.hbox()
            if (t.match == Match.AMBIGUOUS || t.match == Match.UNKNOWN) {
                actions.addView(c.button(c.getString(R.string.choose_person), outlined = true) { TxnDetail.choosePerson(a, t) { a.refresh() } })
            }
            if (t.alert == AlertState.OPEN || t.alert == AlertState.CHECK) {
                actions.addView(c.button(c.getString(R.string.btn_ok_this_time)) { confirmOk(a, t); a.refresh() })
                if (t.merchant != null) actions.addView(c.textButton(c.getString(R.string.btn_always_ok)) { confirmOk(a, t, always = true); a.refresh() })
                actions.addView(c.textButton(c.getString(R.string.btn_check_member)) { TxnDetail.checkWithMember(a, t); a.refresh() })
            }
            root.addView(c.scrollH(actions))
        }
        if (high.isNotEmpty()) { root.addView(c.section(c.getString(R.string.alerts_high))); list(high) }
        if (medium.isNotEmpty()) {
            root.addView(c.section(c.getString(R.string.alerts_medium)))
            root.addView(c.muted(c.getString(R.string.alerts_medium_hint), 12f))
            list(medium)
        }
        val past = a.db.txns(System.currentTimeMillis() - 60L * 24 * 3600 * 1000).filter { it.alert == AlertState.OK && it.reasons.isNotEmpty() && it.risk != Risk.LOW }
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
        row(R.string.x_fx_extra, t.fxExtra?.takeIf { it > 0.01 }?.let { Fmt.money(it, "SAR") })
        row(R.string.x_refunded, t.refunded.takeIf { it > 0.01 }?.let { Fmt.money(it, "SAR") })
        row(R.string.x_refund_of, t.refundOf?.let { a.db.txn(it) }?.let { o -> listOfNotNull(o.merchant, rowDate.format(Date(o.ts)), Fmt.money(o.amount, o.currency)).joinToString(" · ") })
        row(R.string.x_income_type, when (t.incomeType) {
            "salary" -> c.getString(R.string.inc_salary); "person" -> c.getString(R.string.inc_people)
            "internal" -> c.getString(R.string.b_internal); "other", "not_salary" -> c.getString(R.string.inc_other); else -> null
        })
        row(R.string.x_note, t.note)
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
        if (t.reasons.isNotEmpty()) {
            row(R.string.x_risk, when (t.risk) { Risk.HIGH -> c.getString(R.string.risk_high); Risk.MEDIUM -> c.getString(R.string.risk_medium); Risk.LOW -> c.getString(R.string.risk_low); else -> null })
            row(R.string.x_alerts, t.explain?.lines()?.filter { it.isNotBlank() }?.joinToString("\n") { "• $it" }
                ?: t.reasons.joinToString("\n") { "• " + Analyzer.label(c, it) })
        }
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
            act(R.string.btn_ok_this_time, false) { confirmOk(a, t) }
            if (t.merchant != null) act(R.string.btn_always_ok) { confirmOk(a, t, always = true) }
            act(R.string.btn_check_member) { checkWithMember(a, t) }
        }
        Notifier.callBankIntent(a, t)?.let { call -> if (t.reasons.isNotEmpty() || t.dupOf != null) act(R.string.btn_call_bank) { a.startActivity(call) } }
        if (t.dupOf != null && (t.dupState == DupState.OPEN || t.dupState == DupState.NORMAL)) {
            act(R.string.dup_confirm) { a.db.updateTxn(t.copy(dupState = DupState.CONFIRMED)); a.toast(a.getString(R.string.dup_confirmed_toast)) }
            act(R.string.dup_normal) {
                a.db.updateTxn(t.copy(dupState = DupState.NORMAL, alert = if (t.alert == AlertState.OPEN) AlertState.OK else t.alert))
                t.merchant?.let { a.db.setRepeatsNormal(it, true) }
            }
        }
        act(if (t.match == Match.IDENTIFIED) R.string.change_person else R.string.choose_person) { choosePerson(a, t, onChanged) }
        if (t.merchant != null && t.kind in setOf(Parser.Kind.PURCHASE, Parser.Kind.BILL)) {
            act(R.string.change_category) { chooseCategory(a, t, onChanged) }
            act(R.string.rename_merchant) { renameMerchant(a, t, onChanged) }
        }
        if (t.kind == Parser.Kind.TRANSFER_OUT && !t.internal) act(R.string.mark_loan) { markLoan(a, t, onChanged) }
        if (t.kind == Parser.Kind.TRANSFER_IN || t.kind == Parser.Kind.CREDIT_IN) {
            if (t.incomeType == "salary") act(R.string.not_salary) { a.db.updateTxn(t.copy(incomeType = "not_salary")) }
            else act(R.string.mark_salary) { a.db.updateTxn(t.copy(incomeType = "salary")) }
        }
        act(if (t.note.isNullOrBlank()) R.string.add_note else R.string.edit_note) { editNote(a, t, onChanged) }
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

    private fun editNote(a: MainActivity, t: Txn, onChanged: () -> Unit) {
        val box = a.vbox(20)
        val f = a.field(a.getString(if (t.kind == Parser.Kind.WITHDRAWAL) R.string.note_cash_hint else R.string.note_hint), t.note ?: "")
        box.addView(f.layout)
        a.dialog(a.getString(R.string.add_note)).setView(box).setPositiveButton(R.string.save) { _, _ ->
            a.db.updateTxn(t.copy(note = f.value.ifBlank { null })); onChanged()
        }.setNegativeButton(R.string.cancel, null).show()
    }

    /** A friendly name for a merchant, or merge it with another spelling of the same place. */
    private fun renameMerchant(a: MainActivity, t: Txn, onChanged: () -> Unit) {
        val merchant = t.merchant ?: return
        val meta = a.db.merchant(merchant)
        val box = a.vbox(20)
        val name = a.field(a.getString(R.string.merchant_friendly), meta?.displayName ?: "")
        val others = a.db.txns(System.currentTimeMillis() - 400 * Insights.DAY).mapNotNull { it.merchant }.distinct()
            .filter { !it.equals(merchant, true) }.sorted()
        box.addView(name.layout)
        box.addView(a.muted(a.getString(R.string.merchant_merge_hint), 12f))
        var merge: String? = meta?.aliasOf
        val mergeBtn = a.textButton(merge?.let { a.getString(R.string.merchant_merged_with, it) } ?: a.getString(R.string.merchant_merge)) {}
        mergeBtn.setOnClickListener {
            a.dialog(a.getString(R.string.merchant_merge)).setItems((listOf(a.getString(R.string.none)) + others).toTypedArray()) { _, i ->
                merge = if (i == 0) null else others[i - 1]
                mergeBtn.text = merge?.let { a.getString(R.string.merchant_merged_with, it) } ?: a.getString(R.string.merchant_merge)
            }.show()
        }
        box.addView(mergeBtn)
        a.dialog(merchant).setView(box).setPositiveButton(R.string.save) { _, _ ->
            a.db.setMerchantName(merchant, name.value.ifBlank { null }, merge); onChanged()
        }.setNegativeButton(R.string.cancel, null).show()
    }

    /** "Who owes me": a transfer out recorded as a loan, with an optional due date. */
    private fun markLoan(a: MainActivity, t: Txn, onChanged: () -> Unit) {
        val box = a.vbox(20)
        val who = a.field(a.getString(R.string.loan_who), t.counterparty ?: "")
        val days = a.field(a.getString(R.string.loan_due_days), "", android.text.InputType.TYPE_CLASS_NUMBER, a.getString(R.string.loan_due_helper))
        box.addView(who.layout); box.addView(days.layout)
        a.dialog(a.getString(R.string.mark_loan)).setView(box).setPositiveButton(R.string.save) { _, _ ->
            val due = days.value.toIntOrNull()?.takeIf { it > 0 }?.let { System.currentTimeMillis() + it * Insights.DAY }
            a.db.saveLoan(Loan(0, t.id, who.value.ifBlank { t.counterparty ?: "?" }, t.counterpartyAcct, t.amount, 0.0, due, System.currentTimeMillis()))
            a.toast(a.getString(R.string.loan_saved)); onChanged()
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
