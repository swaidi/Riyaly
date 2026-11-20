package app.riyaly

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Market exchange rates, to show how much a bank's rate costs on purchases abroad. Rates come from the
 * free Frankfurter service (European Central Bank data), with the US dollar as base; the riyal is
 * pegged at 3.75 per dollar. Only currency codes are sent — never amounts or anything personal.
 */
object Fx {
    const val SAR_PER_USD = 3.75
    private const val URL_LATEST = "https://api.frankfurter.app/latest?from=USD"

    /** Units of each currency per one US dollar. */
    fun rates(ctx: Context): Map<String, Double> = AppLog.safe("fx rates", emptyMap()) {
        val s = Prefs(ctx).sp.getString("fx_rates", null) ?: return@safe emptyMap()
        val o = JSONObject(s)
        o.keys().asSequence().associateWith { o.getDouble(it) } + ("USD" to 1.0) + ("SAR" to SAR_PER_USD)
    }

    fun refresh(ctx: Context): Boolean = AppLog.safe("fx refresh", false) {
        val c = URL(URL_LATEST).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 15000
        try {
            if (c.responseCode != 200) { AppLog.w("fx: HTTP ${c.responseCode}"); return@safe false }
            val r = JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("rates")
            Prefs(ctx).sp.edit().putString("fx_rates", r.toString()).putLong("fx_ts", System.currentTimeMillis()).apply()
            true
        } finally { c.disconnect() }
    }

    /** Market value in riyals of an amount in another currency, or null when the rate is unknown. */
    fun marketSar(amount: Double, currency: String, rates: Map<String, Double>): Double? {
        if (currency == "SAR") return amount
        val perUsd = rates[currency.uppercase()] ?: return null
        if (perUsd <= 0) return null
        return amount / perUsd * SAR_PER_USD
    }

    /**
     * Extra cost of the bank's rate: what was charged (without listed fees) minus the market value.
     * Under 0.5% is treated as rounding.
     */
    fun extraCost(t: Txn, rates: Map<String, Double>): Double? {
        val orig = t.origAmount ?: return null
        val cur = t.origCurrency ?: return null
        if (t.currency != "SAR" || cur == "SAR") return null
        val market = marketSar(orig, cur, rates) ?: return null
        val charged = t.amount - (t.fees ?: 0.0)
        val extra = charged - market
        return if (extra > market * 0.005) extra else 0.0
    }

    /** Fills in the exchange-rate cost of foreign purchases that do not have it yet. */
    fun fillMissing(ctx: Context) {
        if (!Prefs(ctx).fxEnabled) return
        val db = Db.get(ctx)
        val pending = db.txns(System.currentTimeMillis() - 400 * Insights.DAY).filter { it.isForeign && it.fxExtra == null && it.origAmount != null && !it.isDeclined }
        if (pending.isEmpty()) return
        if (rates(ctx).size <= 2) refresh(ctx)
        val r = rates(ctx)
        var n = 0
        for (t in pending) extraCost(t, r)?.let { db.updateTxn(t.copy(fxExtra = it)); n++ }
        AppLog.i("fx: priced $n foreign purchases")
    }
}
