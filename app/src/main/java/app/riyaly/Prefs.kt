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

    /** Everything except the stored (device-bound) backup password, for backups. */
    fun exportable(): Map<String, Any?> = sp.all.filterKeys { it != "backup_pw" && it != "backup_uri" && it != "update_token" && it != "reader_version" }

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
