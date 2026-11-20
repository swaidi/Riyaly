package app.riyaly

import android.content.Intent
import android.net.Uri

/** The Update button: check GitHub releases, download, and open the installer. */
class UpdateFlow(private val a: MainActivity) {
    // ---------- updates ----------
    fun checkUpdates() {
        val c = a
        val token = Updater.token(c)
        c.toast(c.getString(R.string.checking_updates))
        Thread {
            val result = try { Result.success(Updater.latest(token)) } catch (e: Exception) { AppLog.w("update check failed: ${e.javaClass.simpleName} ${e.message}"); Result.failure(e) }
            a.runOnUiThread {
                val err = result.exceptionOrNull()
                val r = result.getOrNull()
                when {
                    err is Updater.HttpError && (err.code == 401 || err.code == 403) -> c.toast(c.getString(R.string.update_bad_token))
                    err != null -> c.toast(c.getString(R.string.no_internet))
                    r == null || r.versionCode <= BuildConfig.VERSION_CODE -> { a.prefs.updateAvailableTag = null; a.updateMenu(); c.toast(c.getString(R.string.up_to_date)) }
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
            val file = try { Updater.download(c, token, r) { } } catch (e: Exception) { AppLog.e("update download", e); null }
            a.runOnUiThread {
                if (file == null) c.toast(c.getString(R.string.update_failed)) else Updater.install(c, file)
            }
        }.start()
    }


    /** Daily background check; the toolbar shows a dot when a newer version exists. */
    fun backgroundCheck() {
        if (System.currentTimeMillis() - a.prefs.lastUpdateCheck < 20L * 3600 * 1000) return
        Thread {
            AppLog.safe("background update check", Unit) { Plan.checkUpdate(a) }
            a.runOnUiThread { a.updateMenu() }
        }.start()
    }
}
