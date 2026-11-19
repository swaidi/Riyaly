package app.riyaly

import android.content.Context
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A small Excel (.xlsx) writer — enough for the monthly report, with no extra libraries. */
object Xlsx {
    class Sheet(val name: String, val rows: List<List<Any?>>, val widths: List<Int> = emptyList())

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }

    private fun col(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }
        return sb.toString()
    }

    fun write(out: OutputStream, sheets: List<Sheet>) {
        ZipOutputStream(out).use { z ->
            fun put(path: String, text: String) { z.putNextEntry(ZipEntry(path)); z.write(text.toByteArray()); z.closeEntry() }
            put("[Content_Types].xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
                append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""")
                append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
                append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
                sheets.indices.forEach { append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""") }
                append("</Types>")
            })
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
                sheets.forEachIndexed { i, s -> append("""<sheet name="${esc(s.name.take(31))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
                append("</sheets></workbook>")
            })
            put("xl/_rels/workbook.xml.rels", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                sheets.indices.forEach { append("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""") }
                append("""<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
                append("</Relationships>")
            })
            // Styles: 0 normal, 1 bold header with fill, 2 number #,##0.00
            put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0.00"/></numFmts><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF1F6F50"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs></styleSheet>""")
            sheets.forEachIndexed { si, s ->
                put("xl/worksheets/sheet${si + 1}.xml", buildString {
                    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
                    append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
                    if (s.widths.isNotEmpty()) {
                        append("<cols>")
                        s.widths.forEachIndexed { i, w -> append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
                        append("</cols>")
                    }
                    append("<sheetData>")
                    s.rows.forEachIndexed { ri, row ->
                        append("""<row r="${ri + 1}">""")
                        row.forEachIndexed { ci, v ->
                            val ref = col(ci) + (ri + 1)
                            val style = if (ri == 0) 1 else 0
                            when (v) {
                                null -> {}
                                is Number -> append("""<c r="$ref" s="${if (ri == 0) 1 else if (v is Double) 2 else 0}"><v>$v</v></c>""")
                                else -> append("""<c r="$ref" t="inlineStr" s="$style"><is><t xml:space="preserve">${esc(v.toString())}</t></is></c>""")
                            }
                        }
                        append("</row>")
                    }
                    append("</sheetData></worksheet>")
                })
            }
        }
    }

    /** Monthly report: summary per member, every transaction, and merchants. */
    fun monthlyReport(ctx: Context, year: Int, month: Int, out: OutputStream) {
        val db = Db.get(ctx)
        val cal = Calendar.getInstance().apply { clear(); set(year, month, 1) }
        val from = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val txns = db.txns(from, cal.timeInMillis).sortedBy { it.ts }
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val tf = SimpleDateFormat("HH:mm", Locale.US)
        fun who(t: Txn) = Notifier.person(ctx, t)
        fun cardName(t: Txn) = db.card(t.card)?.display() ?: t.card

        val spend = txns.filter { it.isSpend }
        val summary = mutableListOf<List<Any?>>(listOf(
            ctx.getString(R.string.x_member), ctx.getString(R.string.x_transactions), ctx.getString(R.string.x_total_sar),
            ctx.getString(R.string.x_average_sar), ctx.getString(R.string.x_largest_sar), ctx.getString(R.string.x_cash_sar),
            ctx.getString(R.string.x_money_in_sar), ctx.getString(R.string.x_transfers_out_sar), ctx.getString(R.string.x_alerts),
        ))
        txns.filter { !it.isDeclined && it.currency == "SAR" }.groupBy { who(it) }.toSortedMap().forEach { (name, list) ->
            val sp = list.filter { it.isSpend }
            summary += listOf(
                name, sp.size, sp.sumOf { it.amount }, if (sp.isEmpty()) null else sp.sumOf { it.amount } / sp.size, sp.maxOfOrNull { it.amount },
                sp.filter { it.kind == Parser.Kind.WITHDRAWAL }.sumOf { it.amount }, list.filter { it.isMoneyIn }.sumOf { it.amount },
                list.filter { it.isTransferOut }.sumOf { it.amount }, list.count { it.reasons.isNotEmpty() },
            )
        }
        summary += listOf(
            ctx.getString(R.string.x_total), spend.size, spend.sumOf { it.amount }, null, null,
            spend.filter { it.kind == Parser.Kind.WITHDRAWAL }.sumOf { it.amount }, txns.filter { it.isMoneyIn }.sumOf { it.amount },
            txns.filter { it.isTransferOut }.sumOf { it.amount }, txns.count { it.reasons.isNotEmpty() },
        )

        val rows = mutableListOf<List<Any?>>(listOf(
            ctx.getString(R.string.x_date), ctx.getString(R.string.x_time), ctx.getString(R.string.x_member), ctx.getString(R.string.x_card),
            ctx.getString(R.string.x_kind), ctx.getString(R.string.x_amount), ctx.getString(R.string.x_currency),
            ctx.getString(R.string.x_original), ctx.getString(R.string.x_fees), ctx.getString(R.string.x_merchant),
            ctx.getString(R.string.x_from_to), ctx.getString(R.string.x_category), ctx.getString(R.string.x_method),
            ctx.getString(R.string.x_type), ctx.getString(R.string.x_country), ctx.getString(R.string.x_balance),
            ctx.getString(R.string.x_travel), ctx.getString(R.string.x_alerts), ctx.getString(R.string.x_status), ctx.getString(R.string.x_message),
        ))
        for (t in txns) rows += listOf(
            df.format(Date(t.ts)), tf.format(Date(t.ts)), who(t), cardName(t), Analyzer.kindLabel(ctx, t.kind),
            if (t.isCredit) t.amount else -t.amount, t.currency,
            t.origAmount?.let { Fmt.money(it, t.origCurrency ?: "SAR") }, t.fees, t.merchant,
            listOfNotNull(t.counterparty, t.counterpartyAcct).joinToString(" · ").ifEmpty { null },
            if (t.isSpend) Categories.label(ctx, Categories.forTxn(db, t)) else null,
            t.method, t.type, t.country, t.balance, if (t.duringTravel) "✈" else null,
            (t.reasons.map { Analyzer.label(ctx, it) } + listOfNotNull(t.declineReason)).joinToString(", "),
            statusLabel(ctx, t), t.body,
        )

        val merchants = mutableListOf<List<Any?>>(listOf(
            ctx.getString(R.string.x_merchant), ctx.getString(R.string.x_category), ctx.getString(R.string.x_transactions),
            ctx.getString(R.string.x_total_sar), ctx.getString(R.string.x_members),
        ))
        spend.filter { it.merchant != null && it.kind == Parser.Kind.PURCHASE }.groupBy { it.merchant!! }.entries.sortedByDescending { e -> e.value.sumOf { it.amount } }.forEach { (m, list) ->
            merchants += listOf(m, Categories.label(ctx, Categories.of(db, m)), list.size, list.sumOf { it.amount },
                list.map { who(it) }.distinct().joinToString(", "))
        }

        write(out, listOf(
            Sheet(ctx.getString(R.string.x_sheet_summary), summary, listOf(24, 14, 16, 16, 16, 16, 16, 18, 10)),
            Sheet(ctx.getString(R.string.x_sheet_transactions), rows, listOf(12, 8, 20, 22, 16, 12, 9, 14, 10, 24, 26, 16, 18, 18, 10, 12, 8, 30, 14, 60)),
            Sheet(ctx.getString(R.string.x_sheet_merchants), merchants, listOf(28, 16, 14, 16, 30)),
        ))
    }

    fun statusLabel(ctx: Context, t: Txn): String = when {
        t.match == Match.AMBIGUOUS -> ctx.getString(R.string.st_choose)
        t.alert == AlertState.OPEN -> ctx.getString(R.string.st_open)
        t.alert == AlertState.CHECK -> ctx.getString(R.string.st_checking)
        t.alert == AlertState.OK -> ctx.getString(R.string.st_confirmed)
        else -> ctx.getString(R.string.st_normal)
    }
}
