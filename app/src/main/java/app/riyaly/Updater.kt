package app.riyaly

import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * In-app updates from the GitHub repository's releases. The repository is public, so no token is
 * needed; a read-only token can still be saved (encrypted) for a private fork.
 */
object Updater {
    private const val REPO = "swaidi/Riyaly"
    private const val KEY_ALIAS = "riyaly_update_token"

    data class Release(val versionCode: Int, val tag: String, val assetId: Long, val assetName: String, val notes: String)

    // ---------- token storage ----------
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()
        )
        return g.generateKey()
    }

    fun saveToken(ctx: Context, token: String?) {
        if (token.isNullOrBlank()) { Prefs(ctx).updateTokenEnc = null; return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        Prefs(ctx).updateTokenEnc = Base64.encodeToString(c.iv + c.doFinal(token.trim().toByteArray()), Base64.NO_WRAP)
    }

    fun token(ctx: Context): String? = try {
        Prefs(ctx).updateTokenEnc?.let {
            val b = Base64.decode(it, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
            String(c.doFinal(b.copyOfRange(12, b.size)))
        }
    } catch (e: Exception) { null }

    // ---------- GitHub ----------
    class HttpError(val code: Int) : Exception("HTTP $code")

    private fun open(url: String, token: String?, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "Riyaly/${BuildConfig.VERSION_NAME}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 20000; readTimeout = 60000
        }

    /** Latest release, or null when there is none. Throws [HttpError] for a bad token or no access. */
    fun latest(token: String?): Release? {
        val conn = open("https://api.github.com/repos/$REPO/releases/latest", token, "application/vnd.github+json")
        try {
            if (conn.responseCode == 404) return null
            if (conn.responseCode != 200) throw HttpError(conn.responseCode)
            val o = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = o.getString("tag_name")
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
            val assets = o.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
            return Release(code, tag, apk.getLong("id"), apk.getString("name"), o.optString("body"))
        } finally { conn.disconnect() }
    }

    /** Downloads the APK into the app's cache (GitHub redirects to a storage link without the token). */
    fun download(ctx: Context, token: String?, r: Release, progress: (Int) -> Unit): File {
        var conn = open("https://api.github.com/repos/$REPO/releases/assets/${r.assetId}", token, "application/octet-stream")
        var hops = 0
        while (conn.responseCode in 300..399 && hops < 5) {
            val next = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = open(next, null, "application/octet-stream")
            hops++
        }
        if (conn.responseCode != 200) throw HttpError(conn.responseCode)
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, r.assetName)
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) progress((done * 100 / total).toInt())
                }
            }
        }
        conn.disconnect()
        return file
    }

    /** Opens Android's installer for the downloaded APK. Data is kept because the signing key is the same. */
    fun install(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
