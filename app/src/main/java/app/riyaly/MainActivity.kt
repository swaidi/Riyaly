package app.riyaly

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView

/** Which transactions the list shows; set by dashboard drill-downs. */
data class TxnFilter(
    val memberId: Long? = null,
    val unidentified: Boolean = false,
    val card: String? = null,
    val kind: String? = null,
    val counterparty: String? = null,
    val merchant: String? = null,
    val category: String? = null,
    val from: Long = 0,
    val to: Long = Long.MAX_VALUE,
    val day: Int? = null,
    val hour: Int? = null,
    val label: String? = null,
)

abstract class Screen(val a: MainActivity) {
    abstract val titleRes: Int
    abstract fun build(): View
    /** A title other than [titleRes] (e.g. a Settings group). */
    open fun titleText(): String? = null
}

/** Raise when the message reader changes, so stored messages are re-read once. */
const val READER_VERSION = 4

const val MENU_UPDATE = 101
const val MENU_FEEDBACK = 102
const val MENU_SETTINGS = 103

class MainActivity : AppCompatActivity() {
    lateinit var db: Db
    lateinit var prefs: Prefs
    private lateinit var content: FrameLayout
    private lateinit var toolbar: MaterialToolbar
    lateinit var nav: BottomNavigationView
    var filter = TxnFilter()
    private var current: Screen? = null

    val dashboard by lazy { DashboardScreen(this) }
    private val list by lazy { TxnListScreen(this) }
    private val alerts by lazy { AlertsScreen(this) }
    private val family by lazy { FamilyScreen(this) }
    private val plan by lazy { PlanScreen(this) }
    val settings by lazy { SettingsScreen(this) }
    val feedback by lazy { Feedback(this) }
    val updates by lazy { UpdateFlow(this) }
    /** The tab screen shown before Settings opened (Back returns to it). */
    private var tabScreen: Screen? = null

    val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(settings::onFolder) }
    val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument(Backup.MIME)) { it?.let(settings::onCreateBackup) }
    val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(settings::onOpenBackup) }
    val createXlsx = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { it?.let(settings::onCreateXlsx) }
    val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        settings.onLocationPermission(r.values.any { it })
    }
    val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        feedback.onSpeech(r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull())
    }
    val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { feedback.onAudioPermission(it) }
    val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { feedback.onScreenshot(it) }
    val mapPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        if (r.resultCode == RESULT_OK && d != null) {
            settings.setHome(d.getDoubleExtra(MapPickerActivity.EXTRA_LAT, 0.0), d.getDoubleExtra(MapPickerActivity.EXTRA_LON, 0.0), getString(R.string.chosen_on_map))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        db = Db.get(this)
        prefs = Prefs(this)
        Notifier.channels(this)

        val root = vbox()
        toolbar = MaterialToolbar(this).apply { setTitle(R.string.app_name) }
        toolbar.menu.add(0, MENU_UPDATE, 0, R.string.btn_update).setIcon(R.drawable.ic_update).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.menu.add(0, MENU_FEEDBACK, 1, R.string.btn_feedback).setIcon(R.drawable.ic_feedback).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.menu.add(0, MENU_SETTINGS, 2, R.string.tab_settings).setIcon(R.drawable.ic_settings).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_UPDATE -> updates.checkUpdates()
                MENU_FEEDBACK -> feedback.show()
                MENU_SETTINGS -> openSettings()
            }
            true
        }
        toolbar.setNavigationOnClickListener { goBack() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goBack()) { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        content = FrameLayout(this)
        nav = BottomNavigationView(this)
        val m = nav.menu
        m.add(0, 1, 0, R.string.tab_dashboard).setIcon(R.drawable.ic_dashboard)
        m.add(0, 2, 1, R.string.tab_transactions).setIcon(R.drawable.ic_list)
        m.add(0, 3, 2, R.string.tab_alerts).setIcon(R.drawable.ic_alert)
        m.add(0, 6, 3, R.string.tab_plan).setIcon(R.drawable.ic_plan)
        m.add(0, 4, 4, R.string.tab_family).setIcon(R.drawable.ic_family)
        nav.setOnItemSelectedListener { val sc = screenFor(it.itemId); tabScreen = sc; show(sc); true }
        nav.setOnItemReselectedListener { val sc = screenFor(it.itemId); if (current !== sc) { tabScreen = sc; show(sc) } }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            nav.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        Backup.schedule(this)
        Workers.schedule(this)
        AppLog.i("app opened ${BuildConfig.VERSION_NAME}")
        // Re-read stored messages once after an update that reads them better.
        if (prefs.readerVersion < READER_VERSION) {
            Thread {
                try { Processor.reparseAll(this) } catch (e: Exception) { AppLog.e("re-read messages", e) }
                prefs.readerVersion = READER_VERSION
                runOnUiThread { refresh() }
            }.start()
        }
        nav.selectedItemId = if (db.cards().isEmpty()) 4 else 1
        handleIntent(intent)
        if (!prefs.onboarded) askPermissions()
        updateMenu()
        updates.backgroundCheck()
    }

    /** Shows a dot on the Update button when a newer version is available. */
    fun updateMenu() {
        val item = toolbar.menu.findItem(MENU_UPDATE) ?: return
        val tag = prefs.updateAvailableTag
        val icon = ContextCompat.getDrawable(this, R.drawable.ic_update)!!.mutate()
        if (tag != null) {
            val dot = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()).apply {
                paint.color = Ui.ALERT; intrinsicWidth = dp(9); intrinsicHeight = dp(9)
            }
            item.icon = android.graphics.drawable.LayerDrawable(arrayOf(icon, dot)).apply {
                setLayerGravity(1, android.view.Gravity.TOP or android.view.Gravity.END)
                setLayerSize(1, dp(9), dp(9))
            }
            item.title = getString(R.string.update_available, tag)
        } else {
            item.icon = icon
            item.title = getString(R.string.btn_update)
        }
    }

    fun plan(): PlanScreen = plan

    fun openSettings() {
        settings.page = null
        show(settings)
    }

    /** Back: from a Settings group to the Settings list, from Settings to the tab. False when nothing to go back to. */
    fun goBack(): Boolean {
        if (current === settings) {
            if (settings.page != null) { settings.page = null; show(settings) }
            else show(tabScreen ?: dashboard)
            return true
        }
        if (current is PlanScreen && plan.sub != null) { plan.sub = null; show(plan); return true }
        if (current is DashboardScreen && dashboard.more) { dashboard.more = false; show(dashboard); return true }
        return false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun handleIntent(i: Intent?) {
        val id = i?.getLongExtra(Notifier.EXTRA_TXN, -1) ?: -1
        if (id <= 0) return
        val t = db.txn(id) ?: return
        i?.removeExtra(Notifier.EXTRA_TXN)
        Notifier.cancel(this, id)
        if (i?.getStringExtra(Notifier.EXTRA_ACTION) == Notifier.ACTION_CHECK) TxnDetail.checkWithMember(this, t)
        else TxnDetail.show(this, t)
    }

    private fun screenFor(id: Int): Screen = when (id) {
        1 -> dashboard
        2 -> list
        3 -> alerts
        4 -> family
        6 -> plan
        else -> settings
    }

    private val scrollPositions = HashMap<Screen, Int>()

    fun show(s: Screen) {
        // Remember where the current screen was scrolled to, so a refresh does not jump to the top.
        (content.getChildAt(0) as? android.widget.ScrollView)?.let { sv -> current?.let { scrollPositions[it] = sv.scrollY } }
        current = s
        toolbar.setTitle(s.titleRes)
        s.titleText()?.let { toolbar.title = it }
        val nested = s === settings || (s is PlanScreen && s.sub != null) || (s is DashboardScreen && s.more)
        if (nested) {
            toolbar.setNavigationIcon(R.drawable.ic_back)
            toolbar.setNavigationContentDescription(R.string.back)
        } else toolbar.navigationIcon = null
        content.removeAllViews()
        val v = s.build()
        content.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val y = scrollPositions[s] ?: 0
        if (y > 0 && v is android.widget.ScrollView) v.post { v.scrollTo(0, y) }
        updateAlertBadge()
    }

    fun refresh() { current?.let { show(it) } }

    fun openList(f: TxnFilter) {
        filter = f
        scrollPositions.remove(list)
        if (nav.selectedItemId == 2) refresh() else nav.selectedItemId = 2
    }

    private fun updateAlertBadge() {
        val n = db.openAlerts().size
        if (n > 0) nav.getOrCreateBadge(3).number = n else nav.removeBadge(3)
    }

    fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    fun missingPermissions(): List<String> {
        val need = listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.POST_NOTIFICATIONS)
        return need.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
    }

    fun askPermissions() {
        prefs.onboarded = true
        val missing = missingPermissions()
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}
