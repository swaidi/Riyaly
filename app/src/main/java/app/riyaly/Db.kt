package app.riyaly

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Member(val id: Long, val name: String, val color: Int)
object CardKind { const val CARD = "card"; const val ACCOUNT = "account" }

data class Card(
    val last4: String,
    val memberId: Long,
    val label: String? = null,
    val kind: String = CardKind.CARD,
    val travelFrom: Long? = null,
    val travelTo: Long? = null,
    val travelDest: String? = null,
    val limitSingle: Double? = null,
    val limitMonthly: Double? = null,
    val limitWithdrawal: Double? = null,
) {
    /** "Salary account (5678)", or "•••• 5678" when there is no label. */
    fun display(): String = if (label.isNullOrBlank()) "•••• $last4" else "$label ($last4)"

    /** Travel mode covers the whole of the start and end days. */
    fun travelActive(ts: Long): Boolean {
        val f = travelFrom ?: return false
        val t = travelTo ?: return false
        return ts >= f && ts < t + 24L * 3600 * 1000
    }
}
data class Bank(
    val id: Long,
    val name: String,
    val senders: List<String>,
    val balanceWords: List<String>,
    val merchantPrefixes: List<String>,
    /** The bank's phone number for reporting a lost card or fraud (optional). */
    val phone: String? = null,
)

/** Risk level of a transaction's alert. */
object Risk { const val HIGH = "high"; const val MEDIUM = "medium"; const val LOW = "low" }

/** State of a possible duplicate charge. */
object DupState { const val OPEN = "open"; const val CONFIRMED = "confirmed"; const val REFUNDED = "refunded"; const val NORMAL = "normal" }

data class Loan(
    val id: Long, val txnId: Long?, val counterparty: String, val acct: String?, val amount: Double,
    val repaid: Double, val dueTs: Long?, val createdTs: Long,
) { val open: Boolean get() = repaid + 0.01 < amount }

object Match { const val IDENTIFIED = "identified"; const val UNKNOWN = "unknown"; const val AMBIGUOUS = "ambiguous" }
object AlertState { const val NONE = "none"; const val OPEN = "open"; const val OK = "ok"; const val CHECK = "check" }

data class Txn(
    val id: Long,
    val smsHash: String,
    val sender: String,
    val bankId: Long?,
    val body: String,
    val ts: Long,
    val amount: Double,
    val currency: String,
    val balance: Double?,
    val merchant: String?,
    val method: String?,
    val type: String?,
    val isCredit: Boolean,
    val isDeclined: Boolean,
    val isForeign: Boolean,
    val card: String?,
    val memberId: Long?,
    val match: String,
    val candidates: List<Long>,
    val alert: String,
    val reasons: List<String>,
    val city: String?,
    val distanceKm: Double?,
    val kind: String = Parser.Kind.PURCHASE,
    val fees: Double? = null,
    val origAmount: Double? = null,
    val origCurrency: String? = null,
    val country: String? = null,
    val counterparty: String? = null,
    val counterpartyAcct: String? = null,
    val declineReason: String? = null,
    val duringTravel: Boolean = false,
    /** For a refund: the purchase it returns money for. */
    val refundOf: Long? = null,
    /** For a purchase: how much of it has been refunded so far. */
    val refunded: Double = 0.0,
    /** A transfer between the family's own registered accounts. */
    val internal: Boolean = false,
    /** For money in: salary, person, refund or other. */
    val incomeType: String? = null,
    val risk: String? = null,
    val dupOf: Long? = null,
    val dupState: String? = null,
    /** The user's own note, e.g. what cash was spent on. */
    val note: String? = null,
    /** Estimated extra cost of the bank's exchange rate versus the market rate (SAR). */
    val fxExtra: Double? = null,
    /** Plain-language reasons for the alert, one per line. */
    val explain: String? = null,
    val feeType: String? = null,
) {
    /** Money spent (purchases, cash, bills, fees), counted net of refunds. */
    val isSpend: Boolean get() = !isDeclined && currency == "SAR" && kind in Parser.Kind.SPEND
    val netSpend: Double get() = (amount - refunded).coerceAtLeast(0.0)
    /** Income: transfers in and deposits, not refunds and not moves between the family's own accounts. */
    val isMoneyIn: Boolean get() = !isDeclined && currency == "SAR" && kind in setOf(Parser.Kind.TRANSFER_IN, Parser.Kind.CREDIT_IN) && !internal
    val isTransferOut: Boolean get() = !isDeclined && currency == "SAR" && kind == Parser.Kind.TRANSFER_OUT && !internal
    val isRefund: Boolean get() = !isDeclined && kind == Parser.Kind.REFUND
    /** Total fees paid on this transaction: listed fees, fee charges and the exchange-rate cost. */
    val feesPaid: Double get() = (fees ?: 0.0) + (if (kind == Parser.Kind.FEE && !isDeclined) amount else 0.0) + (fxExtra ?: 0.0)
}

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "riyaly.db", null, 3) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }
        /** Tests only: forget the open database so the next test starts clean. */
        fun resetForTests() = synchronized(this) { inst?.close(); inst = null }
        val TABLES = listOf("members", "cards", "banks", "txns", "merchants", "watch", "loans", "budgets", "trust", "rec_state")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE members(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, color INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE cards(last4 TEXT PRIMARY KEY, member_id INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE banks(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, senders TEXT, balance_words TEXT, merchant_prefixes TEXT)")
        db.execSQL(
            """CREATE TABLE txns(id INTEGER PRIMARY KEY AUTOINCREMENT, sms_hash TEXT UNIQUE, sender TEXT, bank_id INTEGER,
            body TEXT, ts INTEGER, amount REAL, currency TEXT, balance REAL, merchant TEXT, method TEXT, type TEXT,
            is_credit INTEGER, is_declined INTEGER, is_foreign INTEGER, card TEXT, member_id INTEGER, match_state TEXT,
            candidates TEXT, alert TEXT, reasons TEXT, city TEXT, distance_km REAL)"""
        )
        db.execSQL("CREATE INDEX txns_ts ON txns(ts)")
        db.execSQL("CREATE TABLE merchants(name TEXT PRIMARY KEY, category TEXT, lat REAL, lon REAL, looked_up INTEGER DEFAULT 0, trusted INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE watch(keyword TEXT PRIMARY KEY)")
        val cv = ContentValues().apply {
            put("name", "Al Rajhi Bank")
            put("senders", "AlRajhiBank")
            put("balance_words", Parser.DEFAULT_BALANCE_WORDS.joinToString(","))
            put("merchant_prefixes", Parser.DEFAULT_MERCHANT_PREFIXES.joinToString(","))
        }
        db.insert("banks", null, cv)
        onUpgrade(db, 1, 3)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            listOf(
                "cards" to "label TEXT", "cards" to "kind TEXT DEFAULT 'card'", "cards" to "travel_from INTEGER",
                "cards" to "travel_to INTEGER", "cards" to "travel_dest TEXT", "cards" to "limit_single REAL",
                "cards" to "limit_monthly REAL", "cards" to "limit_withdrawal REAL",
                "txns" to "kind TEXT DEFAULT 'purchase'", "txns" to "fees REAL", "txns" to "orig_amount REAL",
                "txns" to "orig_currency TEXT", "txns" to "country TEXT", "txns" to "counterparty TEXT",
                "txns" to "counterparty_acct TEXT", "txns" to "decline_reason TEXT", "txns" to "during_travel INTEGER DEFAULT 0",
            ).forEach { (t, col) -> db.execSQL("ALTER TABLE $t ADD COLUMN $col") }
        }
        if (oldVersion < 3) {
            listOf(
                "txns" to "refund_of INTEGER", "txns" to "refunded REAL DEFAULT 0", "txns" to "internal INTEGER DEFAULT 0",
                "txns" to "income_type TEXT", "txns" to "risk TEXT", "txns" to "dup_of INTEGER", "txns" to "dup_state TEXT",
                "txns" to "note TEXT", "txns" to "fx_extra REAL", "txns" to "explain TEXT", "txns" to "fee_type TEXT",
                "merchants" to "display_name TEXT", "merchants" to "alias_of TEXT", "merchants" to "repeats_normal INTEGER DEFAULT 0",
                "banks" to "phone TEXT",
            ).forEach { (t, col) -> db.execSQL("ALTER TABLE $t ADD COLUMN $col") }
            db.execSQL("CREATE TABLE IF NOT EXISTS loans(id INTEGER PRIMARY KEY AUTOINCREMENT, txn_id INTEGER, counterparty TEXT, acct TEXT, amount REAL, repaid REAL DEFAULT 0, due_ts INTEGER, created_ts INTEGER)")
            db.execSQL("CREATE TABLE IF NOT EXISTS budgets(kind TEXT, key TEXT, amount REAL, PRIMARY KEY(kind, key))")
            db.execSQL("CREATE TABLE IF NOT EXISTS trust(member_id INTEGER, merchant TEXT, PRIMARY KEY(member_id, merchant))")
            db.execSQL("CREATE TABLE IF NOT EXISTS rec_state(key TEXT PRIMARY KEY, state TEXT, period_days INTEGER, reviewed_ts INTEGER)")
        }
    }

    // ---------- loans ----------
    fun loans(): List<Loan> = readableDatabase.rawQuery(
        "SELECT id,txn_id,counterparty,acct,amount,repaid,due_ts,created_ts FROM loans ORDER BY created_ts DESC", null
    ).use { c -> c.all { Loan(it.getLong(0), it.getLongOrNull(1), it.getString(2) ?: "", it.getStringOrNull(3), it.getDouble(4), it.getDouble(5), it.getLongOrNull(6), it.getLong(7)) } }

    fun saveLoan(l: Loan): Long {
        val cv = ContentValues().apply {
            if (l.txnId == null) putNull("txn_id") else put("txn_id", l.txnId)
            put("counterparty", l.counterparty); put("acct", l.acct); put("amount", l.amount); put("repaid", l.repaid)
            if (l.dueTs == null) putNull("due_ts") else put("due_ts", l.dueTs)
            put("created_ts", l.createdTs)
        }
        return if (l.id <= 0) writableDatabase.insert("loans", null, cv) else { writableDatabase.update("loans", cv, "id=?", arrayOf("${l.id}")); l.id }
    }

    fun deleteLoan(id: Long) { writableDatabase.delete("loans", "id=?", arrayOf("$id")) }

    // ---------- budgets (kind: category, habit, member) ----------
    fun budgets(kind: String): Map<String, Double> = readableDatabase.rawQuery("SELECT key,amount FROM budgets WHERE kind=?", arrayOf(kind)).use { c ->
        c.all { it.getString(0) to it.getDouble(1) }.toMap()
    }

    fun setBudget(kind: String, key: String, amount: Double?) {
        if (amount == null || amount <= 0) writableDatabase.delete("budgets", "kind=? AND key=?", arrayOf(kind, key))
        else writableDatabase.insertWithOnConflict("budgets", null, ContentValues().apply { put("kind", kind); put("key", key); put("amount", amount) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------- per-person trust ("Always OK") ----------
    fun trusted(memberId: Long?): Set<String> = readableDatabase.rawQuery("SELECT merchant FROM trust WHERE member_id=?", arrayOf("${memberId ?: -1}")).use { c ->
        c.all { it.getString(0) }.toSet()
    }

    fun trust(memberId: Long?, merchant: String) {
        writableDatabase.insertWithOnConflict("trust", null, ContentValues().apply { put("member_id", memberId ?: -1); put("merchant", merchant) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    // ---------- recurring payment states ----------
    data class RecState(val key: String, val state: String?, val periodDays: Int?, val reviewedTs: Long?)

    fun recStates(): Map<String, RecState> = readableDatabase.rawQuery("SELECT key,state,period_days,reviewed_ts FROM rec_state", null).use { c ->
        c.all { RecState(it.getString(0), it.getStringOrNull(1), it.getLongOrNull(2)?.toInt(), it.getLongOrNull(3)) }.associateBy { it.key }
    }

    fun setRecState(key: String, state: String?, periodDays: Int? = null) {
        val old = recStates()[key]
        writableDatabase.insertWithOnConflict("rec_state", null, ContentValues().apply {
            put("key", key); put("state", state)
            val p = periodDays ?: old?.periodDays
            if (p == null) putNull("period_days") else put("period_days", p)
            put("reviewed_ts", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------- members & cards ----------
    fun members(): List<Member> = readableDatabase.rawQuery("SELECT id,name,color FROM members ORDER BY name", null).use { c ->
        c.all { Member(it.getLong(0), it.getString(1), it.getInt(2)) }
    }

    fun member(id: Long?): Member? = members().firstOrNull { it.id == id }

    fun saveMember(id: Long?, name: String, color: Int): Long {
        val cv = ContentValues().apply { put("name", name); put("color", color) }
        return if (id == null) writableDatabase.insert("members", null, cv)
        else { writableDatabase.update("members", cv, "id=?", arrayOf("$id")); id }
    }

    fun deleteMember(id: Long) {
        writableDatabase.delete("cards", "member_id=?", arrayOf("$id"))
        writableDatabase.delete("members", "id=?", arrayOf("$id"))
    }

    fun cards(): List<Card> = readableDatabase.rawQuery(
        "SELECT last4,member_id,label,kind,travel_from,travel_to,travel_dest,limit_single,limit_monthly,limit_withdrawal FROM cards ORDER BY last4", null
    ).use { c ->
        c.all {
            Card(
                it.getString(0), it.getLong(1), it.getStringOrNull(2), it.getStringOrNull(3) ?: CardKind.CARD,
                it.getLongOrNull(4), it.getLongOrNull(5), it.getStringOrNull(6),
                it.getDoubleOrNull(7), it.getDoubleOrNull(8), it.getDoubleOrNull(9),
            )
        }
    }

    fun card(last4: String?): Card? = last4?.let { l -> cards().firstOrNull { it.last4 == l } }

    fun saveCard(last4: String, memberId: Long) {
        val existing = card(last4)
        saveCard((existing ?: Card(last4, memberId)).copy(memberId = memberId))
    }

    fun saveCard(c: Card) {
        val cv = ContentValues().apply {
            put("last4", c.last4); put("member_id", c.memberId); put("label", c.label); put("kind", c.kind)
            if (c.travelFrom == null) putNull("travel_from") else put("travel_from", c.travelFrom)
            if (c.travelTo == null) putNull("travel_to") else put("travel_to", c.travelTo)
            put("travel_dest", c.travelDest)
            if (c.limitSingle == null) putNull("limit_single") else put("limit_single", c.limitSingle)
            if (c.limitMonthly == null) putNull("limit_monthly") else put("limit_monthly", c.limitMonthly)
            if (c.limitWithdrawal == null) putNull("limit_withdrawal") else put("limit_withdrawal", c.limitWithdrawal)
        }
        writableDatabase.insertWithOnConflict("cards", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteCard(last4: String) { writableDatabase.delete("cards", "last4=?", arrayOf(last4)) }

    // ---------- banks ----------
    fun banks(): List<Bank> = readableDatabase.rawQuery("SELECT id,name,senders,balance_words,merchant_prefixes,phone FROM banks ORDER BY id", null).use { c ->
        c.all { Bank(it.getLong(0), it.getString(1) ?: "", split(it.getString(2)), split(it.getString(3)), split(it.getString(4)), it.getStringOrNull(5)) }
    }

    fun bank(id: Long?): Bank? = banks().firstOrNull { it.id == id }

    fun saveBank(b: Bank): Long {
        val cv = ContentValues().apply {
            put("name", b.name)
            put("senders", b.senders.joinToString(","))
            put("balance_words", b.balanceWords.joinToString(","))
            put("merchant_prefixes", b.merchantPrefixes.joinToString(","))
            put("phone", b.phone)
        }
        return if (b.id <= 0) writableDatabase.insert("banks", null, cv)
        else { writableDatabase.update("banks", cv, "id=?", arrayOf("${b.id}")); b.id }
    }

    fun deleteBank(id: Long) { writableDatabase.delete("banks", "id=?", arrayOf("$id")) }

    /**
     * The bank whose sender list matches this SMS address exactly (ignoring capitals, spaces, dashes and dots).
     * Advertising senders, which in Saudi Arabia end in "-AD" (e.g. "SAB-Ad"), are never read.
     */
    fun bankForSender(address: String): Bank? {
        if (isAdSender(address)) return null
        val a = simplify(address)
        return banks().firstOrNull { b -> b.senders.any { s -> simplify(s).let { it.isNotEmpty() && a == it } } }
    }

    private fun simplify(s: String) = s.lowercase().replace(Regex("[\\s\\-_.]"), "")

    // ---------- watch list ----------
    fun watch(): List<String> = readableDatabase.rawQuery("SELECT keyword FROM watch ORDER BY keyword", null).use { c -> c.all { it.getString(0) } }
    fun addWatch(k: String) {
        writableDatabase.insertWithOnConflict("watch", null, ContentValues().apply { put("keyword", k.trim()) }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun deleteWatch(k: String) { writableDatabase.delete("watch", "keyword=?", arrayOf(k)) }

    // ---------- merchants ----------
    data class MerchantMeta(
        val name: String, val category: String?, val lat: Double?, val lon: Double?, val lookedUp: Boolean, val trusted: Boolean,
        val displayName: String? = null, val aliasOf: String? = null, val repeatsNormal: Boolean = false,
    )

    private val MCOLS = "name,category,lat,lon,looked_up,trusted,display_name,alias_of,repeats_normal"

    fun merchant(name: String): MerchantMeta? = readableDatabase.rawQuery(
        "SELECT $MCOLS FROM merchants WHERE name=?", arrayOf(name)
    ).use { c -> c.all { readMerchant(it) }.firstOrNull() }

    fun merchants(): List<MerchantMeta> = readableDatabase.rawQuery("SELECT $MCOLS FROM merchants", null).use { c ->
        c.all { readMerchant(it) }
    }

    private fun readMerchant(c: Cursor) = MerchantMeta(
        c.getString(0), c.getStringOrNull(1), c.getDoubleOrNull(2), c.getDoubleOrNull(3), c.getInt(4) == 1, c.getInt(5) == 1,
        c.getStringOrNull(6), c.getStringOrNull(7), (c.getLongOrNull(8) ?: 0L) == 1L,
    )

    /** Friendly name and merging: "HUNGERSTA" can show as "HungerStation" and count together with its other spellings. */
    fun setMerchantName(name: String, displayName: String?, aliasOf: String?) {
        ensureMerchant(name)
        writableDatabase.update("merchants", ContentValues().apply { put("display_name", displayName); put("alias_of", aliasOf) }, "name=?", arrayOf(name))
    }

    fun setRepeatsNormal(name: String, normal: Boolean) {
        ensureMerchant(name)
        writableDatabase.update("merchants", ContentValues().apply { put("repeats_normal", if (normal) 1 else 0) }, "name=?", arrayOf(name))
    }

    private fun ensureMerchant(name: String) {
        writableDatabase.insertWithOnConflict("merchants", null, ContentValues().apply { put("name", name) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun setMerchantCategory(name: String, category: String) {
        ensureMerchant(name)
        writableDatabase.update("merchants", ContentValues().apply { put("category", category) }, "name=?", arrayOf(name))
    }

    fun setMerchantLocation(name: String, lat: Double?, lon: Double?) {
        ensureMerchant(name)
        writableDatabase.update("merchants", ContentValues().apply {
            if (lat == null) putNull("lat") else put("lat", lat)
            if (lon == null) putNull("lon") else put("lon", lon)
            put("looked_up", 1)
        }, "name=?", arrayOf(name))
    }

    fun setMerchantTrusted(name: String, trusted: Boolean) {
        ensureMerchant(name)
        writableDatabase.update("merchants", ContentValues().apply { put("trusted", if (trusted) 1 else 0) }, "name=?", arrayOf(name))
    }

    // ---------- transactions ----------
    fun insertTxn(t: Txn): Long {
        val cv = txnValues(t)
        return writableDatabase.insertWithOnConflict("txns", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun updateTxn(t: Txn) { writableDatabase.update("txns", txnValues(t), "id=?", arrayOf("${t.id}")) }

    fun deleteTxn(id: Long) { writableDatabase.delete("txns", "id=?", arrayOf("$id")) }

    private fun txnValues(t: Txn) = ContentValues().apply {
        put("sms_hash", t.smsHash); put("sender", t.sender)
        if (t.bankId == null) putNull("bank_id") else put("bank_id", t.bankId)
        put("body", t.body); put("ts", t.ts); put("amount", t.amount); put("currency", t.currency)
        if (t.balance == null) putNull("balance") else put("balance", t.balance)
        put("merchant", t.merchant); put("method", t.method); put("type", t.type)
        put("is_credit", if (t.isCredit) 1 else 0); put("is_declined", if (t.isDeclined) 1 else 0)
        put("is_foreign", if (t.isForeign) 1 else 0); put("card", t.card)
        if (t.memberId == null) putNull("member_id") else put("member_id", t.memberId)
        put("match_state", t.match); put("candidates", t.candidates.joinToString(","))
        put("alert", t.alert); put("reasons", t.reasons.joinToString(","))
        put("city", t.city)
        if (t.distanceKm == null) putNull("distance_km") else put("distance_km", t.distanceKm)
        put("kind", t.kind)
        if (t.fees == null) putNull("fees") else put("fees", t.fees)
        if (t.origAmount == null) putNull("orig_amount") else put("orig_amount", t.origAmount)
        put("orig_currency", t.origCurrency); put("country", t.country)
        put("counterparty", t.counterparty); put("counterparty_acct", t.counterpartyAcct)
        put("decline_reason", t.declineReason); put("during_travel", if (t.duringTravel) 1 else 0)
        if (t.refundOf == null) putNull("refund_of") else put("refund_of", t.refundOf)
        put("refunded", t.refunded); put("internal", if (t.internal) 1 else 0); put("income_type", t.incomeType)
        put("risk", t.risk)
        if (t.dupOf == null) putNull("dup_of") else put("dup_of", t.dupOf)
        put("dup_state", t.dupState); put("note", t.note)
        if (t.fxExtra == null) putNull("fx_extra") else put("fx_extra", t.fxExtra)
        put("explain", t.explain); put("fee_type", t.feeType)
    }

    fun txn(id: Long): Txn? = queryTxns("id=?", arrayOf("$id")).firstOrNull()

    fun txns(fromTs: Long = 0, toTs: Long = Long.MAX_VALUE): List<Txn> =
        queryTxns("ts>=? AND ts<?", arrayOf("$fromTs", "$toTs"))

    /** Alerts that need attention: open or being checked, and every ambiguous card. Medium-risk ones are listed too. */
    fun openAlerts(): List<Txn> = queryTxns("alert IN ('open','check') OR match_state='ambiguous'", emptyArray())

    fun highOpenAlerts(): List<Txn> = openAlerts().filter { it.risk != Risk.MEDIUM && it.risk != Risk.LOW }

    fun hashExists(h: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM txns WHERE sms_hash=?", arrayOf(h)).use { it.moveToFirst() }

    /** The same SMS can carry slightly different times (network time vs. received time); treat ±15 min as one message. */
    fun sameMessageExists(sender: String, body: String, ts: Long): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM txns WHERE sender=? AND body=? AND ts BETWEEN ? AND ?",
        arrayOf(sender, body, "${ts - 15 * 60_000}", "${ts + 15 * 60_000}"),
    ).use { it.moveToFirst() }

    private fun queryTxns(where: String, args: Array<String>): List<Txn> = readableDatabase.rawQuery(
        """SELECT id,sms_hash,sender,bank_id,body,ts,amount,currency,balance,merchant,method,type,is_credit,is_declined,
           is_foreign,card,member_id,match_state,candidates,alert,reasons,city,distance_km,kind,fees,orig_amount,orig_currency,
           country,counterparty,counterparty_acct,decline_reason,during_travel,refund_of,refunded,internal,income_type,risk,
           dup_of,dup_state,note,fx_extra,explain,fee_type FROM txns WHERE $where ORDER BY ts DESC""",
        args
    ).use { c ->
        c.all {
            Txn(
                id = it.getLong(0), smsHash = it.getString(1) ?: "", sender = it.getString(2) ?: "",
                bankId = it.getLongOrNull(3), body = it.getString(4) ?: "", ts = it.getLong(5),
                amount = it.getDouble(6), currency = it.getString(7) ?: "SAR", balance = it.getDoubleOrNull(8),
                merchant = it.getStringOrNull(9), method = it.getStringOrNull(10), type = it.getStringOrNull(11),
                isCredit = it.getInt(12) == 1, isDeclined = it.getInt(13) == 1, isForeign = it.getInt(14) == 1,
                card = it.getStringOrNull(15), memberId = it.getLongOrNull(16), match = it.getString(17) ?: Match.UNKNOWN,
                candidates = split(it.getString(18)).mapNotNull { s -> s.toLongOrNull() },
                alert = it.getString(19) ?: AlertState.NONE, reasons = split(it.getString(20)),
                city = it.getStringOrNull(21), distanceKm = it.getDoubleOrNull(22),
                kind = it.getStringOrNull(23) ?: Parser.Kind.PURCHASE, fees = it.getDoubleOrNull(24),
                origAmount = it.getDoubleOrNull(25), origCurrency = it.getStringOrNull(26), country = it.getStringOrNull(27),
                counterparty = it.getStringOrNull(28), counterpartyAcct = it.getStringOrNull(29),
                declineReason = it.getStringOrNull(30), duringTravel = (it.getLongOrNull(31) ?: 0L) == 1L,
                refundOf = it.getLongOrNull(32), refunded = it.getDoubleOrNull(33) ?: 0.0, internal = (it.getLongOrNull(34) ?: 0L) == 1L,
                incomeType = it.getStringOrNull(35), risk = it.getStringOrNull(36), dupOf = it.getLongOrNull(37),
                dupState = it.getStringOrNull(38), note = it.getStringOrNull(39), fxExtra = it.getDoubleOrNull(40),
                explain = it.getStringOrNull(41), feeType = it.getStringOrNull(42),
            )
        }
    }

    fun clearAll() {
        writableDatabase.beginTransaction()
        try {
            TABLES.forEach { writableDatabase.delete(it, null, null) }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
}

fun isAdSender(address: String): Boolean = Regex("[-_ .]ad$", RegexOption.IGNORE_CASE).containsMatchIn(address.trim())

fun split(s: String?): List<String> = s?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

inline fun <T> Cursor.all(read: (Cursor) -> T): List<T> {
    val out = ArrayList<T>(count.coerceAtLeast(0))
    while (moveToNext()) out += read(this)
    return out
}

fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
fun Cursor.getDoubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)
fun Cursor.getLongOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)
