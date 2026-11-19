package app.riyaly

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted backups (AES-256-GCM, key derived from your password with PBKDF2).
 * A backup file can be restored on any phone with the same password.
 */
object Backup {
    private val MAGIC = "RIYALY1".toByteArray()
    private const val ITER = 210_000
    const val MIME = "application/octet-stream"

    // ---------- data <-> JSON ----------
    fun toJson(ctx: Context): JSONObject {
        val db = Db.get(ctx).readableDatabase
        val root = JSONObject().put("app", "Riyaly").put("version", 1).put("created", System.currentTimeMillis())
        for (table in Db.TABLES) {
            val arr = JSONArray()
            db.rawQuery("SELECT * FROM $table", null).use { c ->
                while (c.moveToNext()) {
                    val o = JSONObject()
                    for (i in 0 until c.columnCount) {
                        when (c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> o.put(c.getColumnName(i), JSONObject.NULL)
                            Cursor.FIELD_TYPE_INTEGER -> o.put(c.getColumnName(i), c.getLong(i))
                            Cursor.FIELD_TYPE_FLOAT -> o.put(c.getColumnName(i), c.getDouble(i))
                            else -> o.put(c.getColumnName(i), c.getString(i))
                        }
                    }
                    arr.put(o)
                }
            }
            root.put(table, arr)
        }
        val prefs = JSONObject()
        Prefs(ctx).exportable().forEach { (k, v) -> prefs.put(k, v) }
        root.put("prefs", prefs)
        return root
    }

    fun restoreJson(ctx: Context, root: JSONObject) {
        require(root.optString("app") == "Riyaly") { "Not a Riyaly backup" }
        val helper = Db.get(ctx)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            Db.TABLES.forEach { db.delete(it, null, null) }
            for (table in Db.TABLES) {
                val arr = root.optJSONArray(table) ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val cv = ContentValues()
                    for (k in o.keys()) {
                        when (val v = o.get(k)) {
                            JSONObject.NULL -> cv.putNull(k)
                            is Int -> cv.put(k, v.toLong())
                            is Long -> cv.put(k, v)
                            is Double -> cv.put(k, v)
                            is Boolean -> cv.put(k, if (v) 1 else 0)
                            else -> cv.put(k, v.toString())
                        }
                    }
                    db.insert(table, null, cv)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        root.optJSONObject("prefs")?.let { p ->
            val map = HashMap<String, Any?>()
            for (k in p.keys()) map[k] = p.get(k).let { if (it is Long && it in Int.MIN_VALUE..Int.MAX_VALUE && k != "last_backup") it.toInt() else it }
            Prefs(ctx).importFrom(map)
        }
    }

    // ---------- encryption ----------
    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKey {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = f.generateSecret(PBEKeySpec(password, salt, ITER, 256)).encoded
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(plain: ByteArray, password: String): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, deriveKey(password.toCharArray(), salt), GCMParameterSpec(128, iv))
        val gz = ByteArrayOutputStream().also { bo -> GZIPOutputStream(bo).use { it.write(plain) } }.toByteArray()
        return MAGIC + salt + iv + c.doFinal(gz)
    }

    fun decrypt(data: ByteArray, password: String): ByteArray {
        require(data.size > MAGIC.size + 28 && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Not a Riyaly backup" }
        var p = MAGIC.size
        val salt = data.copyOfRange(p, p + 16); p += 16
        val iv = data.copyOfRange(p, p + 12); p += 12
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, deriveKey(password.toCharArray(), salt), GCMParameterSpec(128, iv))
        val gz = c.doFinal(data.copyOfRange(p, data.size))
        return GZIPInputStream(gz.inputStream()).use { it.readBytes() }
    }

    fun makeFile(ctx: Context, password: String): ByteArray = encrypt(toJson(ctx).toString().toByteArray(), password)

    fun restoreFile(ctx: Context, data: ByteArray, password: String) {
        restoreJson(ctx, JSONObject(String(decrypt(data, password))))
    }

    fun fileName(): String = "Riyaly-backup-" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date()) + ".riyaly"

    // ---------- the stored password for automatic backups (protected by the Android Keystore) ----------
    private const val KEY_ALIAS = "riyaly_backup_pw"

    private fun deviceKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return g.generateKey()
    }

    fun storePassword(ctx: Context, pw: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, deviceKey())
        val out = c.iv + c.doFinal(pw.toByteArray())
        Prefs(ctx).backupPasswordEnc = Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun storedPassword(ctx: Context): String? = try {
        Prefs(ctx).backupPasswordEnc?.let {
            val b = Base64.decode(it, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, deviceKey(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
            String(c.doFinal(b.copyOfRange(12, b.size)))
        }
    } catch (e: Exception) { null }

    // ---------- writing into the chosen folder ----------
    fun writeToFolder(ctx: Context): Boolean {
        val p = Prefs(ctx)
        val tree = p.backupTreeUri?.let(Uri::parse) ?: return false
        val pw = storedPassword(ctx) ?: return false
        val bytes = makeFile(ctx, pw)
        val cr = ctx.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(cr, parent, MIME, fileName()) ?: return false
        cr.openOutputStream(doc, "w")?.use { it.write(bytes) } ?: return false
        p.lastBackup = System.currentTimeMillis()
        prune(ctx, tree)
        return true
    }

    /** Keep the newest 10 automatic backups. */
    private fun prune(ctx: Context, tree: Uri) {
        val cr = ctx.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val files = mutableListOf<Pair<String, String>>()
        cr.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.startsWith("Riyaly-backup-") && name.endsWith(".riyaly")) files += c.getString(0) to name
            }
        }
        files.sortedByDescending { it.second }.drop(10).forEach { (id, _) ->
            try { DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id)) } catch (_: Exception) {}
        }
    }

    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (Prefs(ctx).autoBackup) {
            val req = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build()
            wm.enqueueUniquePeriodicWork("daily_backup", ExistingPeriodicWorkPolicy.KEEP, req)
        } else {
            wm.cancelUniqueWork("daily_backup")
        }
    }

    /** After new transactions arrive, back up within the hour (several SMS are grouped into one backup). */
    fun scheduleSoon(ctx: Context) {
        if (!Prefs(ctx).autoBackup) return
        val req = OneTimeWorkRequestBuilder<BackupWorker>().setInitialDelay(45, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("soon_backup", ExistingWorkPolicy.KEEP, req)
    }
}

class BackupWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = try {
        Backup.writeToFolder(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
