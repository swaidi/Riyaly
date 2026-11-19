package app.riyaly

import android.text.InputType
import android.view.View
import androidx.core.util.Pair as UPair
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FamilyScreen(a: MainActivity) : Screen(a) {
    override val titleRes = R.string.tab_family
    private val dayFmt get() = SimpleDateFormat("d MMM", Locale.getDefault())

    override fun build(): View {
        val c = a
        val root = c.vbox(16)
        root.addView(c.muted(c.getString(R.string.family_intro)))
        val members = a.db.members()
        val cards = a.db.cards()
        val now = System.currentTimeMillis()
        for (m in members) {
            val head = c.hbox()
            head.addView(c.colorDot(m.color, 16))
            head.add(c.text(m.name, 17f, true), weight = 1f)
            head.addView(c.textButton(c.getString(R.string.edit)) { editMember(m) })
            val chipBox = c.chips(singleSelection = false)
            cards.filter { it.memberId == m.id }.forEach { card ->
                val travel = card.travelTo?.takeIf { it + 24L * 3600 * 1000 > now }
                val icon = if (card.kind == CardKind.ACCOUNT) "🏦 " else "💳 "
                chipBox.addView(Chip(c).apply {
                    text = icon + card.display() + (travel?.let { "  ✈ " + c.getString(R.string.until_date, dayFmt.format(Date(it))) } ?: "")
                    isCheckable = false
                    setOnClickListener { editCard(card) }
                })
            }
            val add = c.textButton(c.getString(R.string.add_card)) { addCard(m) }
            root.addView(c.card(head, chipBox, add))
        }
        root.addView(c.button(c.getString(R.string.add_member)) { editMember(null) })
        if (cards.isNotEmpty()) root.addView(c.muted(c.getString(R.string.tap_card_hint), 12f).apply { setPadding(0, c.dp(8), 0, 0) })
        if (members.isNotEmpty() && cards.isNotEmpty()) {
            root.addView(c.section(c.getString(R.string.history_title)))
            root.addView(c.card(
                c.muted(c.getString(R.string.history_body)),
                c.button(c.getString(R.string.import_inbox), outlined = true) { a.settings.importInbox() },
            ))
        }
        return c.scroll(root)
    }

    private fun editMember(m: Member?) {
        val c = a
        val box = c.vbox(20)
        val name = c.field(c.getString(R.string.member_name), m?.name ?: "", helper = c.getString(R.string.member_name_helper))
        box.addView(name.layout)
        var color = m?.color ?: Ui.PALETTE[a.db.members().size % Ui.PALETTE.size]
        val colors = c.hbox().apply { setPadding(0, c.dp(14), 0, 0) }
        fun drawColors() {
            colors.removeAllViews()
            Ui.PALETTE.forEach { col ->
                colors.addView(c.colorDot(col, if (col == color) 30 else 22).apply { setOnClickListener { color = col; drawColors() } })
            }
        }
        drawColors()
        box.addView(colors)
        val d = c.dialog(c.getString(if (m == null) R.string.add_member else R.string.edit_member)).setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                if (name.value.isNotEmpty()) { a.db.saveMember(m?.id, name.value, color); a.refresh() }
            }
            .setNegativeButton(R.string.cancel, null)
        if (m != null) d.setNeutralButton(R.string.delete) { _, _ ->
            c.confirm(c.getString(R.string.delete_member), c.getString(R.string.delete_member_q, m.name), c.getString(R.string.delete)) {
                a.db.deleteMember(m.id); a.refresh()
            }
        }
        d.show()
    }

    private fun addCard(m: Member) {
        val c = a
        val box = c.vbox(20)
        val f = c.field(c.getString(R.string.card_digits), "", InputType.TYPE_CLASS_NUMBER, c.getString(R.string.card_digits_helper))
        f.edit.filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        val label = c.field(c.getString(R.string.card_label), "", helper = c.getString(R.string.card_label_helper))
        var kind = CardKind.CARD
        val kinds = c.chips()
        kinds.addView(c.chip(c.getString(R.string.kind_card), true) { kind = CardKind.CARD })
        kinds.addView(c.chip(c.getString(R.string.kind_account), false) { kind = CardKind.ACCOUNT })
        box.addView(kinds); box.addView(f.layout); box.addView(label.layout)
        c.dialog(c.getString(R.string.add_card_for, m.name)).setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val d = Parser.normalize(f.value)
                if (!Regex("\\d{3,4}").matches(d)) { c.toast(c.getString(R.string.card_digits_error)); return@setPositiveButton }
                val owner = a.db.cards().firstOrNull { it.last4 == d }
                if (owner != null && owner.memberId != m.id) {
                    c.toast(c.getString(R.string.card_taken, d, a.db.member(owner.memberId)?.name ?: "?")); return@setPositiveButton
                }
                a.db.saveCard((owner ?: Card(d, m.id)).copy(memberId = m.id, label = label.value.ifEmpty { null }, kind = kind))
                Thread { Inbox.reidentify(a); a.runOnUiThread { a.refresh() } }.start()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun editCard(card0: Card) {
        val c = a
        var card = card0
        val owner = a.db.member(card.memberId)?.name ?: ""
        val box = c.vbox(20)

        // Name and type.
        var kind = card.kind
        val kinds = c.chips()
        kinds.addView(c.chip(c.getString(R.string.kind_card), kind == CardKind.CARD) { kind = CardKind.CARD })
        kinds.addView(c.chip(c.getString(R.string.kind_account), kind == CardKind.ACCOUNT) { kind = CardKind.ACCOUNT })
        val label = c.field(c.getString(R.string.card_label), card.label ?: "", helper = c.getString(R.string.card_label_helper))
        box.addView(kinds); box.addView(label.layout)

        // Limits.
        fun num(v: Double?) = v?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: ""
        val numType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        box.addView(c.section(c.getString(R.string.card_limits)))
        box.addView(c.muted(c.getString(R.string.card_limits_hint, a.prefs.largeAmount), 12f))
        val single = c.field(c.getString(R.string.limit_single), num(card.limitSingle), numType)
        val withdrawal = c.field(c.getString(R.string.limit_withdrawal), num(card.limitWithdrawal), numType)
        val monthly = c.field(c.getString(R.string.limit_monthly), num(card.limitMonthly), numType)
        box.addView(single.layout); box.addView(withdrawal.layout); box.addView(monthly.layout)

        // Travel mode.
        box.addView(c.section(c.getString(R.string.travel_mode)))
        box.addView(c.muted(c.getString(R.string.travel_hint), 12f))
        var from = card.travelFrom
        var to = card.travelTo
        val datesText = c.text("")
        fun drawDates() {
            datesText.text = if (from != null && to != null)
                c.getString(R.string.travel_dates, dayFmt.format(Date(from!!)), dayFmt.format(Date(to!!)))
            else c.getString(R.string.travel_off)
        }
        drawDates()
        val dest = c.field(c.getString(R.string.travel_dest), card.travelDest ?: "", helper = c.getString(R.string.travel_dest_helper))
        val travelButtons = c.hbox()
        travelButtons.addView(c.button(c.getString(R.string.travel_choose_dates), outlined = true) {
            val b = MaterialDatePicker.Builder.dateRangePicker().setTitleText(c.getString(R.string.travel_choose_dates))
            if (from != null && to != null) b.setSelection(UPair(Travel.localDayToUtc(from!!), Travel.localDayToUtc(to!!)))
            val picker = b.build()
            picker.addOnPositiveButtonClickListener { sel ->
                val s = sel.first; val e = sel.second
                if (s != null && e != null) { from = Travel.utcDayToLocal(s); to = Travel.utcDayToLocal(e); drawDates() }
            }
            picker.show(a.supportFragmentManager, "travel")
        })
        travelButtons.addView(c.textButton(c.getString(R.string.travel_turn_off)) { from = null; to = null; drawDates() })
        box.addView(datesText); box.addView(travelButtons); box.addView(dest.layout)

        val d = c.dialog(owner + " · " + card.display()).setView(c.scroll(box))
            .setPositiveButton(R.string.save) { _, _ ->
                card = card.copy(
                    label = label.value.ifEmpty { null }, kind = kind,
                    limitSingle = single.value.toDoubleOrNull(), limitWithdrawal = withdrawal.value.toDoubleOrNull(),
                    limitMonthly = monthly.value.toDoubleOrNull(),
                    travelFrom = from, travelTo = to, travelDest = if (from != null) dest.value.ifEmpty { null } else null,
                )
                a.db.saveCard(card)
                Travel.schedule(a, card)
                a.refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.remove_card) { _, _ ->
                c.confirm(c.getString(R.string.remove_card), c.getString(R.string.remove_card_q, card.last4, owner), c.getString(R.string.delete)) {
                    a.db.deleteCard(card.last4); Travel.schedule(a, card.copy(travelTo = null)); a.refresh()
                }
            }
        d.show()
    }
}
