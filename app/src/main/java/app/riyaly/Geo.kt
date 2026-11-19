package app.riyaly

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** OpenStreetMap (Nominatim) lookups. Works without Google services. Used only when switched on. */
object Geo {
    data class Place(val name: String, val display: String, val lat: Double, val lon: Double, val cls: String)

    private val lock = Any()
    private var lastCall = 0L

    fun search(query: String, limit: Int = 5, near: Pair<Double, Double>? = null): List<Place> = synchronized(lock) {
        val wait = 1100 - (System.currentTimeMillis() - lastCall) // service policy: at most one request per second
        if (wait > 0) Thread.sleep(wait)
        lastCall = System.currentTimeMillis()
        val q = URLEncoder.encode(query, "UTF-8")
        val box = near?.let { (la, lo) -> "&viewbox=${lo - 0.6},${la + 0.6},${lo + 0.6},${la - 0.6}" } ?: ""
        val url = URL("https://nominatim.openstreetmap.org/search?q=$q&format=jsonv2&limit=$limit&countrycodes=sa&namedetails=1$box")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "Riyaly/${BuildConfig.VERSION_NAME} (personal Android app)")
            setRequestProperty("Accept-Language", "en,ar")
            connectTimeout = 15000; readTimeout = 15000
        }
        try {
            if (conn.responseCode != 200) return emptyList()
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val names = o.optJSONObject("namedetails")
                val name = names?.optString("name:en")?.takeIf { it.isNotBlank() } ?: names?.optString("name") ?: o.optString("name")
                Place(name ?: "", o.optString("display_name"), o.getString("lat").toDouble(), o.getString("lon").toDouble(), o.optString("category", o.optString("class")))
            }
        } finally {
            conn.disconnect()
        }
    }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    private val PLACE_CLASSES = setOf("shop", "amenity", "leisure", "tourism", "building", "craft", "office")

    /** Finds a merchant only when the result's name clearly matches; otherwise returns null ("Unknown"). */
    fun findMerchant(merchant: String, homeCity: String, home: Pair<Double, Double>): Place? {
        val tokens = merchant.uppercase().split(Regex("[^A-Z0-9\\u0600-\\u06FF]+")).filter { it.length >= 3 }
        if (tokens.isEmpty()) return null
        val results = search("$merchant $homeCity", 5, home) + search(merchant, 5, null)
        return results.firstOrNull { p ->
            val n = p.name.uppercase()
            p.cls in PLACE_CLASSES && n.isNotBlank() && tokens.first().let { n.contains(it) } &&
                tokens.count { n.contains(it) } >= minOf(tokens.size, 2).coerceAtLeast(1)
        }
    }
}

class LookupWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val db = Db.get(ctx)
        val p = Prefs(ctx)
        val t = db.txn(inputData.getLong("txn", -1)) ?: return Result.success()
        val merchant = t.merchant ?: return Result.success()
        val hLat = p.homeLat ?: return Result.success()
        val hLon = p.homeLon ?: return Result.success()
        val meta = db.merchant(merchant)
        if (meta?.trusted == true) return Result.success()
        var lat = meta?.lat
        var lon = meta?.lon
        if (meta?.lookedUp != true) {
            val place = try { Geo.findMerchant(merchant, p.homeCity, hLat to hLon) } catch (e: Exception) { return Result.retry() }
            lat = place?.lat; lon = place?.lon
            db.setMerchantLocation(merchant, lat, lon)
        }
        if (lat == null || lon == null) return Result.success()
        val d = Geo.distanceKm(hLat, hLon, lat, lon)
        var updated = t.copy(distanceKm = d)
        if (d > p.farKm && Analyzer.FAR !in t.reasons) {
            updated = updated.copy(reasons = t.reasons + Analyzer.FAR, alert = if (t.alert == AlertState.NONE) AlertState.OPEN else t.alert)
            db.updateTxn(updated)
            Notifier.renotify(ctx, updated)
        } else {
            db.updateTxn(updated)
        }
        return Result.success()
    }
}
