package app.riyaly

import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.chip.Chip
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SettingsScreen(a: MainActivity) : Screen(a) {
    override val titleRes = R.string.tab_settings
    private var reportMonth: Pair<Int, Int>? = null

    override fun build(): View {
        val c = a
        val p = a.prefs
        val root = c.vbox(16)

        // ---------- Banks ----------
        root.addView(c.section(c.getString(R.string.s_banks)))
        root.addView(c.muted(c.getString(R.string.s_banks_intro)))
        for (b in a.db.banks()) {
            root.addView(c.card(
                c.text(b.name, 16f, true),
                c.muted(c.getString(R.string.senders_line, b.senders.joinToString(", "))),
                onClick = { editBank(b) },
            ))
        }
        root.addView(c.button(c.getString(R.string.add_bank), outlined = true) { editBank(null) })

        // ---------- History ----------
        root.addView(c.section(c.getString(R.string.history_title)))
        root.addView(c.card(c.muted(c.getString(R.string.history_body)), c.button(c.getString(R.string.import_inbox), outlined = true) { importInbox() }))

        // ---------- Notifications ----------
        root.addView(c.section(c.getString(R.string.s_notifications)))
        root.addView(c.switchRow(c.getString(R.string.notify_every), p.notifyEvery) { p.notifyEvery = it })

        // ---------- Alert rules ----------
        root.addView(c.section(c.getString(R.string.s_rules)))
        root.addView(c.muted(c.getString(R.string.s_rules_intro)))
        val rules = c.vbox()
        Analyzer.ALL.forEach { code -> rules.addView(c.switchRow(Analyzer.label(c, code), p.rule(code)) { p.setRule(code, it) }) }
        root.addView(c.card(rules))
        val large = c.field(c.getString(R.string.large_amount), "${p.largeAmount}", InputType.TYPE_CLASS_NUMBER)
        val qs = c.field(c.getString(R.string.quiet_start), "${p.quietStart}", InputType.TYPE_CLASS_NUMBER)
        val qe = c.field(c.getString(R.string.quiet_end), "${p.quietEnd}", InputType.TYPE_CLASS_NUMBER)
        val far = c.field(c.getString(R.string.far_km), "${p.farKm}", InputType.TYPE_CLASS_NUMBER)
        val transfer = c.field(c.getString(R.string.transfer_limit), "${p.transferLimit}", InputType.TYPE_CLASS_NUMBER)
        val quiet = c.hbox().apply { add(qs.layout, weight = 1f); add(c.vbox(), width = c.dp(10)); add(qe.layout, weight = 1f) }
        root.addView(c.card(c.muted(c.getString(R.string.general_limits_hint), 12f), large.layout, transfer.layout, quiet, far.layout, c.button(c.getString(R.string.save)) {
            large.value.toIntOrNull()?.let { p.largeAmount = it }
            transfer.value.toIntOrNull()?.let { p.transferLimit = it }
            qs.value.toIntOrNull()?.takeIf { it in 0..23 }?.let { p.quietStart = it }
            qe.value.toIntOrNull()?.takeIf { it in 0..23 }?.let { p.quietEnd = it }
            far.value.toIntOrNull()?.takeIf { it > 0 }?.let { p.farKm = it }
            c.toast(c.getString(R.string.saved))
        }))

        // ---------- Watch list ----------
        root.addView(c.section(c.getString(R.string.s_watch)))
        root.addView(c.muted(c.getString(R.string.s_watch_intro)))
        val chips = c.chips(singleSelection = false)
        a.db.watch().forEach { k ->
            chips.addView(Chip(c).apply {
                text = k; isCloseIconVisible = true
                setOnCloseIconClickListener { a.db.deleteWatch(k); a.refresh() }
            })
        }
        val kw = c.field(c.getString(R.string.watch_hint))
        root.addView(c.card(chips, kw.layout, c.button(c.getString(R.string.add)) {
            if (kw.value.isNotEmpty()) { a.db.addWatch(kw.value); a.refresh() }
        }))

        // ---------- Home & location ----------
        root.addView(c.section(c.getString(R.string.s_location)))
        val homeLine = if (p.homeLat != null) p.homeLabel.ifEmpty { "%.4f, %.4f".format(p.homeLat, p.homeLon) } else c.getString(R.string.home_not_set)
        root.addView(c.card(
            c.text(c.getString(R.string.home_city_line, p.homeCity)),
            c.textButton(c.getString(R.string.change_city)) { chooseCity() },
            c.text(c.getString(R.string.home_location_line, homeLine)),
            c.button(c.getString(R.string.use_my_location)) { useMyLocation() },
            c.button(c.getString(R.string.choose_on_map), outlined = true) { a.mapPicker.launch(MapPickerActivity.intent(c, p.homeLat, p.homeLon)) },
            c.textButton(c.getString(R.string.search_name_coords)) { searchHome() },
            c.switchRow(c.getString(R.string.lookup_switch), p.lookupEnabled) { p.lookupEnabled = it },
            c.muted(c.getString(R.string.lookup_note), 12f),
        ))

        // ---------- Backup ----------
        root.addView(c.section(c.getString(R.string.s_backup)))
        val folder = p.backupTreeUri?.let { Uri.parse(it).lastPathSegment } ?: c.getString(R.string.not_chosen)
        val last = if (p.lastBackup > 0) SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(p.lastBackup)) else c.getString(R.string.never)
        root.addView(c.card(
            c.muted(c.getString(R.string.backup_intro)),
            c.text(c.getString(R.string.backup_folder_line, folder)),
            c.textButton(c.getString(R.string.choose_folder)) { a.pickFolder.launch(null) },
            c.text(c.getString(R.string.backup_password_line, c.getString(if (Backup.storedPassword(c) != null) R.string.set else R.string.not_set))),
            c.textButton(c.getString(R.string.set_password)) { setPassword() },
            c.switchRow(c.getString(R.string.auto_backup), p.autoBackup) {
                if (it && (p.backupTreeUri == null || Backup.storedPassword(c) == null)) {
                    c.toast(c.getString(R.string.auto_backup_needs)); a.refresh(); return@switchRow
                }
                p.autoBackup = it; Backup.schedule(c)
            },
            c.muted(c.getString(R.string.last_backup, last)),
            c.button(c.getString(R.string.backup_now)) { backupNow() },
            c.button(c.getString(R.string.restore), outlined = true) { a.openBackup.launch(arrayOf("*/*")) },
        ))

        // ---------- Excel report ----------
        root.addView(c.section(c.getString(R.string.s_report)))
        root.addView(c.card(c.muted(c.getString(R.string.report_intro)), c.button(c.getString(R.string.export_excel)) { chooseMonth() }))

        // ---------- App updates ----------
        root.addView(c.section(c.getString(R.string.s_updates)))
        val tokenSet = Updater.token(c) != null
        val tokenField = c.field(c.getString(R.string.update_token), "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            c.getString(if (tokenSet) R.string.update_token_saved else R.string.update_token_helper))
        root.addView(c.card(
            c.text(c.getString(R.string.current_version, BuildConfig.VERSION_NAME)),
            c.muted(c.getString(R.string.update_public_note), 12f),
            tokenField.layout,
            c.textButton(c.getString(R.string.save_token)) {
                if (tokenField.value.isNotEmpty()) { Updater.saveToken(c, tokenField.value); c.toast(c.getString(R.string.saved)); a.refresh() }
            },
            c.button(c.getString(R.string.check_updates)) { checkUpdates() },
        ))

        // ---------- Language ----------
        root.addView(c.section(c.getString(R.string.s_language)))
        val lang = c.chips()
        val curTag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        listOf("" to R.string.lang_system, "en" to R.string.lang_en, "ar" to R.string.lang_ar).forEach { (tag, label) ->
            lang.addView(c.chip(c.getString(label), curTag == tag) {
                AppCompatDelegate.setApplicationLocales(if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
            })
        }
        root.addView(lang)

        root.addView(c.muted(c.getString(R.string.about, BuildConfig.VERSION_NAME), 12f).apply { setPadding(0, c.dp(24), 0, c.dp(24)) })
        return c.scroll(root)
    }

    // ---------- updates ----------
    private fun checkUpdates() {
        val c = a
        val token = Updater.token(c)
        c.toast(c.getString(R.string.checking_updates))
        Thread {
            val result = try { Result.success(Updater.latest(token)) } catch (e: Exception) { Result.failure(e) }
            a.runOnUiThread {
                val err = result.exceptionOrNull()
                val r = result.getOrNull()
                when {
                    err is Updater.HttpError && (err.code == 401 || err.code == 403) -> c.toast(c.getString(R.string.update_bad_token))
                    err != null -> c.toast(c.getString(R.string.no_internet))
                    r == null || r.versionCode <= BuildConfig.VERSION_CODE -> c.toast(c.getString(R.string.up_to_date))
                    else -> c.confirm(c.getString(R.string.update_available, r.tag), c.getString(R.string.update_install_q), c.getString(R.string.update_install)) {
                        downloadAndInstall(token, r)
                    }
                }
            }
        }.start()
    }

    private fun downloadAndInstall(token: String?, r: Updater.Release) {
        val c = a
        if (!c.packageManager.canRequestPackageInstalls()) {
            c.toast(c.getString(R.string.update_allow_installs))
            c.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")))
            return
        }
        c.toast(c.getString(R.string.downloading_update))
        Thread {
            val file = try { Updater.download(c, token, r) { } } catch (e: Exception) { null }
            a.runOnUiThread {
                if (file == null) c.toast(c.getString(R.string.update_failed)) else Updater.install(c, file)
            }
        }.start()
    }

    // ---------- banks ----------
    private fun editBank(b: Bank?) {
        val c = a
        val box = c.vbox(20)
        val name = c.field(c.getString(R.string.bank_name), b?.name ?: "")
        val senders = c.field(c.getString(R.string.bank_senders), b?.senders?.joinToString(", ") ?: "", helper = c.getString(R.string.bank_senders_helper))
        val bal = c.field(c.getString(R.string.bank_balance_words), (b?.balanceWords ?: Parser.DEFAULT_BALANCE_WORDS).joinToString(", "))
        val mer = c.field(c.getString(R.string.bank_merchant_words), (b?.merchantPrefixes ?: Parser.DEFAULT_MERCHANT_PREFIXES).joinToString(", "),
            helper = c.getString(R.string.bank_merchant_helper))
        listOf(name, senders, bal, mer).forEach { box.addView(it.layout) }
        val d = c.dialog(c.getString(if (b == null) R.string.add_bank else R.string.edit_bank)).setView(c.scroll(box))
            .setPositiveButton(R.string.save) { _, _ ->
                if (senders.value.isEmpty()) return@setPositiveButton
                a.db.saveBank(Bank(b?.id ?: 0, name.value.ifEmpty { senders.value }, split(senders.value), split(bal.value), split(mer.value)))
                a.refresh()
            }
            .setNegativeButton(R.string.cancel, null)
        if (b != null) d.setNeutralButton(R.string.delete) { _, _ -> a.db.deleteBank(b.id); a.refresh() }
        d.show()
    }

    // ---------- inbox ----------
    fun importInbox() {
        val c = a
        if (a.missingPermissions().contains(android.Manifest.permission.READ_SMS)) { a.askPermissions(); return }
        c.toast(c.getString(R.string.importing))
        Thread {
            val r = try { Inbox.import(c) } catch (e: Exception) { Inbox.Outcome(0, 0) }
            a.runOnUiThread { c.toast(c.getString(R.string.import_done, r.read, r.added)); a.refresh() }
        }.start()
    }

    // ---------- location ----------
    private fun chooseCity() {
        val cities = Parser.CITIES.values.distinct()
        a.dialog(a.getString(R.string.change_city)).setItems(cities.toTypedArray()) { _, i ->
            a.prefs.homeCity = cities[i]; a.refresh()
        }.show()
    }

    fun setHome(lat: Double, lon: Double, label: String) {
        a.prefs.homeLat = lat; a.prefs.homeLon = lon
        a.prefs.homeLabel = "$label (%.5f, %.5f)".format(Locale.US, lat, lon)
        a.toast(a.getString(R.string.home_saved))
        a.refresh()
    }

    private fun useMyLocation() {
        val perms = arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        val granted = perms.any { androidx.core.content.ContextCompat.checkSelfPermission(a, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (granted) onLocationPermission(true) else a.locationPermission.launch(perms)
    }

    fun onLocationPermission(granted: Boolean) {
        if (!granted) { a.toast(a.getString(R.string.location_denied)); return }
        a.toast(a.getString(R.string.getting_location))
        HomeLocation.current(a) { loc ->
            if (loc == null) a.toast(a.getString(R.string.location_failed))
            else setHome(loc.latitude, loc.longitude, a.getString(R.string.current_location))
        }
    }

    private fun searchHome() {
        val c = a
        val box = c.vbox(20)
        val q = c.field(c.getString(R.string.home_search_hint), "", helper = c.getString(R.string.home_search_helper))
        box.addView(q.layout)
        c.dialog(c.getString(R.string.set_home)).setView(box).setPositiveButton(R.string.search) { _, _ ->
            val query = q.value
            val coords = Regex("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*$").find(query)
            if (coords != null) {
                a.prefs.homeLat = coords.groupValues[1].toDouble(); a.prefs.homeLon = coords.groupValues[2].toDouble()
                a.prefs.homeLabel = query.trim(); a.refresh(); return@setPositiveButton
            }
            if (query.isEmpty()) return@setPositiveButton
            Thread {
                val results = try { Geo.search("$query, ${a.prefs.homeCity}", 6) } catch (e: Exception) { null }
                a.runOnUiThread {
                    if (results.isNullOrEmpty()) { c.toast(c.getString(if (results == null) R.string.no_internet else R.string.no_results)); return@runOnUiThread }
                    c.dialog(c.getString(R.string.pick_place)).setItems(results.map { it.display }.toTypedArray()) { _, i ->
                        val r = results[i]
                        a.prefs.homeLat = r.lat; a.prefs.homeLon = r.lon; a.prefs.homeLabel = r.display.split(",").take(3).joinToString(",")
                        a.refresh()
                    }.show()
                }
            }.start()
        }.setNegativeButton(R.string.cancel, null).show()
    }

    // ---------- backup ----------
    fun onFolder(uri: Uri) {
        a.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        a.prefs.backupTreeUri = uri.toString()
        a.refresh()
    }

    private fun setPassword() {
        val c = a
        val box = c.vbox(20)
        val p1 = c.field(c.getString(R.string.password), "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val p2 = c.field(c.getString(R.string.password_again), "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        box.addView(c.muted(c.getString(R.string.password_warning)))
        box.addView(p1.layout); box.addView(p2.layout)
        c.dialog(c.getString(R.string.set_password)).setView(box).setPositiveButton(R.string.save) { _, _ ->
            when {
                p1.value.length < 6 -> c.toast(c.getString(R.string.password_short))
                p1.value != p2.value -> c.toast(c.getString(R.string.password_mismatch))
                else -> { Backup.storePassword(c, p1.value); a.refresh() }
            }
        }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun backupNow() {
        val c = a
        if (Backup.storedPassword(c) == null) { setPassword(); return }
        if (a.prefs.backupTreeUri != null) {
            Thread {
                val ok = try { Backup.writeToFolder(c) } catch (e: Exception) { false }
                a.runOnUiThread { c.toast(c.getString(if (ok) R.string.backup_done else R.string.backup_failed)); a.refresh() }
            }.start()
        } else {
            a.createBackup.launch(Backup.fileName())
        }
    }

    fun onCreateBackup(uri: Uri) {
        val c = a
        val pw = Backup.storedPassword(c) ?: return
        Thread {
            val ok = try {
                c.contentResolver.openOutputStream(uri, "w")?.use { it.write(Backup.makeFile(c, pw)) }; a.prefs.lastBackup = System.currentTimeMillis(); true
            } catch (e: Exception) { false }
            a.runOnUiThread { c.toast(c.getString(if (ok) R.string.backup_done else R.string.backup_failed)); a.refresh() }
        }.start()
    }

    fun onOpenBackup(uri: Uri) {
        val c = a
        val box = c.vbox(20)
        val pw = c.field(c.getString(R.string.password), "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        box.addView(c.muted(c.getString(R.string.restore_warning)))
        box.addView(pw.layout)
        c.dialog(c.getString(R.string.restore)).setView(box).setPositiveButton(R.string.restore) { _, _ ->
            val password = pw.value
            Thread {
                val ok = try {
                    val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException()
                    Backup.restoreFile(c, bytes, password)
                    Backup.storePassword(c, password)
                    true
                } catch (e: Exception) { false }
                a.runOnUiThread { c.toast(c.getString(if (ok) R.string.restore_done else R.string.restore_failed)); a.refresh() }
            }.start()
        }.setNegativeButton(R.string.cancel, null).show()
    }

    // ---------- Excel ----------
    private fun chooseMonth() {
        val fmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        val months = (0 until 12).map { back ->
            val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, -back) }
            Triple(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), fmt.format(cal.time))
        }
        a.dialog(a.getString(R.string.export_excel)).setItems(months.map { it.third }.toTypedArray()) { _, i ->
            val (y, m, _) = months[i]
            reportMonth = y to m
            a.createXlsx.launch("Riyaly-report-%04d-%02d.xlsx".format(Locale.US, y, m + 1))
        }.show()
    }

    fun onCreateXlsx(uri: Uri) {
        val c = a
        val (y, m) = reportMonth ?: return
        Thread {
            val ok = try { c.contentResolver.openOutputStream(uri, "w")?.use { Xlsx.monthlyReport(c, y, m, it) }; true } catch (e: Exception) { false }
            a.runOnUiThread {
                if (ok) {
                    c.toast(c.getString(R.string.report_done))
                    val share = Intent(Intent.ACTION_SEND).setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    a.startActivity(Intent.createChooser(share, c.getString(R.string.share_report)))
                } else c.toast(c.getString(R.string.report_failed))
            }
        }.start()
    }
}
