package app.riyaly

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper

/** One-time reading of the phone's current position (GPS / network), without Google services. */
object HomeLocation {

    @SuppressLint("MissingPermission")
    fun current(ctx: Context, done: (Location?) -> Unit) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
        if (providers.isEmpty()) { done(null); return }

        val main = Handler(Looper.getMainLooper())
        val signal = CancellationSignal()
        var finished = false
        fun finish(l: Location?) {
            if (finished) return
            finished = true
            signal.cancel()
            done(l)
        }
        // Give up after 45 seconds and fall back to the last known position (if it is recent).
        main.postDelayed({
            val last = providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            finish(last?.takeIf { System.currentTimeMillis() - it.time < 30 * 60_000 })
        }, 45_000)
        for (p in providers) {
            lm.getCurrentLocation(p, signal, ctx.mainExecutor) { loc -> if (loc != null) finish(loc) }
        }
    }
}
