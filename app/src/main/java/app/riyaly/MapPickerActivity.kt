package app.riyaly

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.CustomZoomButtonsController
import java.io.File

/**
 * Choose a point on an OpenStreetMap map (no Google services needed).
 * Move the map until the pin sits on your home, then tap "Use this location".
 */
class MapPickerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        private val RIYADH = GeoPoint(24.7136, 46.6753)

        fun intent(ctx: Context, lat: Double?, lon: Double?) = Intent(ctx, MapPickerActivity::class.java).apply {
            if (lat != null && lon != null) { putExtra(EXTRA_LAT, lat); putExtra(EXTRA_LON, lon) }
        }
    }

    private lateinit var map: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = "Riyaly/${BuildConfig.VERSION_NAME} (personal Android app)"
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid-tiles")
        }

        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            isTilesScaledToDpi = true
        }
        val start = if (intent.hasExtra(EXTRA_LAT)) GeoPoint(intent.getDoubleExtra(EXTRA_LAT, 0.0), intent.getDoubleExtra(EXTRA_LON, 0.0)) else RIYADH
        map.controller.setZoom(if (intent.hasExtra(EXTRA_LAT)) 17.0 else 11.0)
        map.controller.setCenter(start)

        val frame = FrameLayout(this)
        frame.addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(Pin(this), FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))

        val hint = muted(getString(R.string.map_hint)).apply {
            setBackgroundColor(attrColor(com.google.android.material.R.attr.colorSurface, Color.WHITE))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        frame.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val bottom = vbox(12).apply { setBackgroundColor(attrColor(com.google.android.material.R.attr.colorSurface, Color.WHITE)) }
        bottom.addView(muted(getString(R.string.map_attribution), 11f))
        bottom.addView(button(getString(R.string.use_this_location)) {
            val c = map.mapCenter
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_LAT, c.latitude).putExtra(EXTRA_LON, c.longitude))
            finish()
        })
        frame.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        val toolbar = MaterialToolbar(this).apply {
            setTitle(R.string.choose_on_map)
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
        }
        val root = vbox()
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { map.onPause(); super.onPause() }

    /** A pin whose tip marks the exact centre of the map. */
    @SuppressLint("ViewConstructor")
    private class Pin(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(198, 40, 40) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val tipY = height / 2f
            val r = width / 5f
            val headY = tipY - r * 1.8f
            val path = android.graphics.Path().apply {
                moveTo(cx, tipY)
                lineTo(cx - r * 0.75f, headY + r * 0.4f)
                lineTo(cx + r * 0.75f, headY + r * 0.4f)
                close()
            }
            c.drawPath(path, fill)
            c.drawCircle(cx, headY, r, fill)
            c.drawCircle(cx, headY, r * 0.4f, ring)
            c.drawCircle(cx, tipY, r * 0.18f, fill)
        }
    }
}
