package app.riyaly

import android.content.Context
import android.content.SharedPreferences

class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("riyaly", Context.MODE_PRIVATE)

    var homeCity: String
        get() = sp.getString("home_city", "Riyadh") ?: "Riyadh"
        set(v) = sp.edit().putString("home_city", v).apply()

    var homeLabel: String
        get() = sp.getString("home_label", "") ?: ""
        set(v) = sp.edit().putString("home_label", v).apply()

    var homeLat: Double?
        get() = sp.getString("home_lat", null)?.toDoubleOrNull()
        set(v) = sp.edit().putString("home_lat", v?.toString()).apply()

    var homeLon: Double?
        get() = sp.getString("home_lon", null)?.toDoubleOrNull()
        set(v) = sp.edit().putString("home_lon", v?.toString()).apply()

    var farKm: Int
        get() = sp.getInt("far_km", 30)
        set(v) = sp.edit().putInt("far_km", v).apply()

    var lookupEnabled: Boolean
        get() = sp.getBoolean("lookup", true)
        set(v) = sp.edit().putBoolean("lookup", v).apply()

    var largeAmount: Int
        get() = sp.getInt("large_amount", 1000)
        set(v) = sp.edit().putInt("large_amount", v).apply()

    var transferLimit: Int
        get() = sp.getInt("transfer_limit", 5000)
        set(v) = sp.edit().putInt("transfer_limit", v).apply()

    /** Version of the message reader the stored transactions were read with. */
    var readerVersion: Int
        get() = sp.getInt("reader_version", 1)
        set(v) = sp.edit().putInt("reader_version", v).apply()

    var updateTokenEnc: String?
        get() = sp.getString("update_token", null)
        set(v) = sp.edit().putString("update_token", v).apply()

    /** Message patterns the user marked "Not a transaction", one per line. */
    var ignoredPatterns: List<String>
        get() = (sp.getString("ignored_patterns", "") ?: "").split("\n").filter { it.isNotBlank() }
        set(v) = sp.edit().putString("ignored_patterns", v.distinct().joinToString("\n")).apply()

    var quietStart: Int
        get() = sp.getInt("quiet_start", 1)
        set(v) = sp.edit().putInt("quiet_start", v).apply()

    var quietEnd: Int
        get() = sp.getInt("quiet_end", 5)
        set(v) = sp.edit().putInt("quiet_end", v).apply()

    var notifyEvery: Boolean
        get() = sp.getBoolean("notify_every", true)
        set(v) = sp.edit().putBoolean("notify_every", v).apply()

    fun rule(code: String): Boolean = sp.getBoolean("rule_$code", true)
    fun setRule(code: String, on: Boolean) = sp.edit().putBoolean("rule_$code", on).apply()

    var backupTreeUri: String?
        get() = sp.getString("backup_uri", null)
        set(v) = sp.edit().putString("backup_uri", v).apply()

    var backupPasswordEnc: String?
        get() = sp.getString("backup_pw", null)
        set(v) = sp.edit().putString("backup_pw", v).apply()

    var autoBackup: Boolean
        get() = sp.getBoolean("auto_backup", false)
        set(v) = sp.edit().putBoolean("auto_backup", v).apply()

    var lastBackup: Long
        get() = sp.getLong("last_backup", 0)
        set(v) = sp.edit().putLong("last_backup", v).apply()

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    // ---------- plan & insights ----------
    private fun str(k: String) = sp.getString(k, null)
    private fun put(k: String, v: String?) = sp.edit().putString(k, v).apply()
    private fun set(k: String): Set<String> = (str(k) ?: "").split("\n").filter { it.isNotBlank() }.toSet()
    private fun putSet(k: String, v: Set<String>) = put(k, v.joinToString("\n"))
    private fun map(k: String): Map<String, String> = set(k).mapNotNull { l -> l.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    private fun putMap(k: String, v: Map<String, String>) = putSet(k, v.map { "${it.key}=${it.value}" }.toSet())

    /** The family member who is "me" (allowances and loans are from this person). */
    var ownerMemberId: Long?
        get() = sp.getLong("owner_member", -1).takeIf { it >= 0 }
        set(v) = sp.edit().putLong("owner_member", v ?: -1).apply()

    /** Cards/accounts whose SMS balance counts for "Safe to spend"; empty means all accounts. */
    var balanceCards: Set<String>
        get() = set("balance_cards")
        set(v) = putSet("balance_cards", v)

    var manualBalance: Double?
        get() = str("manual_balance")?.toDoubleOrNull()
        set(v) = put("manual_balance", v?.toString())

    var manualBalanceTs: Long
        get() = sp.getLong("manual_balance_ts", 0)
        set(v) = sp.edit().putLong("manual_balance_ts", v).apply()

    /** Day of month of the salary, when it cannot be detected. */
    var paydayDay: Int?
        get() = sp.getInt("payday_day", 0).takeIf { it in 1..31 }
        set(v) = sp.edit().putInt("payday_day", v ?: 0).apply()

    /** Amount set aside for saving each pay period (kept out of "Safe to spend"). */
    var setAside: Int
        get() = sp.getInt("set_aside", 0)
        set(v) = sp.edit().putInt("set_aside", v).apply()

    /** Saving target as a percent of income. */
    var savingTarget: Int
        get() = sp.getInt("saving_target", 20)
        set(v) = sp.edit().putInt("saving_target", v).apply()

    var needsTarget: Int
        get() = sp.getInt("needs_target", 50)
        set(v) = sp.edit().putInt("needs_target", v).apply()

    var wantsTarget: Int
        get() = sp.getInt("wants_target", 30)
        set(v) = sp.edit().putInt("wants_target", v).apply()

    /** A purchase below this amount (SR) counts as small. */
    var smallThreshold: Int
        get() = sp.getInt("small_threshold", 50)
        set(v) = sp.edit().putInt("small_threshold", v).apply()

    /** Spending pace warning when this percent above normal. */
    var paceThreshold: Int
        get() = sp.getInt("pace_threshold", 15)
        set(v) = sp.edit().putInt("pace_threshold", v).apply()

    /** Unusual months ("2026-03") left out of the normal level. */
    var excludedMonths: Set<String>
        get() = set("excluded_months")
        set(v) = putSet("excluded_months", v)

    /** Categories counted as family spending (not personal). */
    var familyCategories: Set<String>
        get() = str("family_categories")?.let { set("family_categories") }
            ?: setOf(Categories.GROCERIES, Categories.BILLS, Categories.TELECOM, Categories.PHARMACY)
        set(v) = putSet("family_categories", v)

    /** Category → need / want / saving, overriding the defaults. */
    var bucketOverrides: Map<String, String>
        get() = map("bucket_overrides")
        set(v) = putMap("bucket_overrides", v)

    /** Periods shown on the dashboard: "month" or "payday". */
    var periodMode: String
        get() = str("period_mode") ?: "month"
        set(v) = put("period_mode", v)

    /** Exchange-rate cost check (uses the internet for market rates). */
    var fxEnabled: Boolean
        get() = sp.getBoolean("fx_enabled", true)
        set(v) = sp.edit().putBoolean("fx_enabled", v).apply()

    /** Learned quiet hours instead of the fixed ones. */
    var learnedHours: Boolean
        get() = sp.getBoolean("learned_hours", true)
        set(v) = sp.edit().putBoolean("learned_hours", v).apply()

    /** Notification switches for the summaries. */
    fun summaryOn(key: String): Boolean = sp.getBoolean("sum_$key", true)
    fun setSummary(key: String, on: Boolean) = sp.edit().putBoolean("sum_$key", on).apply()

    /** One-time flags (alerts sent once per period). */
    fun flag(key: String): Boolean = sp.getBoolean("flag_$key", false)
    fun setFlag(key: String) = sp.edit().putBoolean("flag_$key", true).apply()

    var updateAvailableTag: String?
        get() = str("update_available")
        set(v) = put("update_available", v)

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0)
        set(v) = sp.edit().putLong("last_update_check", v).apply()

    var lastReview: Long
        get() = sp.getLong("last_review", 0)
        set(v) = sp.edit().putLong("last_review", v).apply()

    /** Everything except the stored (device-bound) backup password, for backups. */
    fun exportable(): Map<String, Any?> = sp.all.filterKeys { it != "backup_pw" && it != "backup_uri" && it != "update_token" && it != "reader_version" && !it.startsWith("flag_") && it != "update_available" }

    fun importFrom(map: Map<String, Any?>) {
        val e = sp.edit()
        for ((k, v) in map) when (v) {
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is String -> e.putString(k, v)
            is Double -> e.putInt(k, v.toInt())
        }
        e.apply()
    }
}
