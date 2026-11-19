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
)

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
) {
    val isSpend: Boolean get() = !isDeclined && currency == "SAR" && kind in Parser.Kind.SPEND
    val isMoneyIn: Boolean get() = !isDeclined && currency == "SAR" && kind in Parser.Kind.INFLOW
    val isTransferOut: Boolean get() = !isDeclined && currency == "SAR" && kind == Parser.Kind.TRANSFER_OUT
}

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "riyaly.db", null, 2) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }
        val TABLES = listOf("members", "cards", "banks", "txns", "merchants", "watch")
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
        onUpgrade(db, 1, 2)
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
    fun banks(): List<Bank> = readableDatabase.rawQuery("SELECT id,name,senders,balance_words,merchant_prefixes FROM banks ORDER BY id", null).use { c ->
        c.all { Bank(it.getLong(0), it.getString(1) ?: "", split(it.getString(2)), split(it.getString(3)), split(it.getString(4))) }
    }

    fun saveBank(b: Bank): Long {
        val cv = ContentValues().apply {
            put("name", b.name)
            put("senders", b.senders.joinToString(","))
            put("balance_words", b.balanceWords.joinToString(","))
            put("merchant_prefixes", b.merchantPrefixes.joinToString(","))
        }
        return if (b.id <= 0) writableDatabase.insert("banks", null, cv)
        else { writableDatabase.update("banks", cv, "id=?", arrayOf("${b.id}")); b.id }
    }

    fun deleteBank(id: Long) { writableDatabase.delete("banks", "id=?", arrayOf("$id")) }

    /** The bank whose sender list matches this SMS address (case-insensitive, ignoring spaces/dashes). */
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
    data class MerchantMeta(val name: String, val category: String?, val lat: Double?, val lon: Double?, val lookedUp: Boolean, val trusted: Boolean)

    fun merchant(name: String): MerchantMeta? = readableDatabase.rawQuery(
        "SELECT name,category,lat,lon,looked_up,trusted FROM merchants WHERE name=?", arrayOf(name)
    ).use { c -> c.all { readMerchant(it) }.firstOrNull() }

    fun merchants(): List<MerchantMeta> = readableDatabase.rawQuery("SELECT name,category,lat,lon,looked_up,trusted FROM merchants", null).use { c ->
        c.all { readMerchant(it) }
    }

    private fun readMerchant(c: Cursor) = MerchantMeta(
        c.getString(0), c.getStringOrNull(1), c.getDoubleOrNull(2), c.getDoubleOrNull(3), c.getInt(4) == 1, c.getInt(5) == 1
    )

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
    }

    fun txn(id: Long): Txn? = queryTxns("id=?", arrayOf("$id")).firstOrNull()

    fun txns(fromTs: Long = 0, toTs: Long = Long.MAX_VALUE): List<Txn> =
        queryTxns("ts>=? AND ts<?", arrayOf("$fromTs", "$toTs"))

    fun openAlerts(): List<Txn> = queryTxns("alert IN ('open','check') OR match_state='ambiguous'", emptyArray())

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
           country,counterparty,counterparty_acct,decline_reason,during_travel FROM txns WHERE $where ORDER BY ts DESC""",
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
