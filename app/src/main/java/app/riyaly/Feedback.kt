package app.riyaly

import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.widget.CheckBox
import androidx.core.content.FileProvider
import java.io.File

/**
 * Feedback in the easiest way for the user: type it, speak it (speech to text), or record a voice note.
 * It is sent by email with the app version and phone model. No transactions, amounts or messages are
 * included; the issue log (message shapes with numbers removed) is attached only when ticked.
 */
class Feedback(private val a: MainActivity) {
    companion object {
        const val EMAIL = "dev.oasis006@passmail.net"
    }

    private var recorder: MediaRecorder? = null
    private var voice: File? = null
    private var screenshot: Uri? = null
    private var textField: Field? = null
    private var status: android.widget.TextView? = null
    private var recordButton: com.google.android.material.button.MaterialButton? = null

    private fun dir() = File(a.cacheDir, "feedback").apply { mkdirs() }

    fun show() {
        val c = a
        voice = null; screenshot = null
        dir().listFiles()?.forEach { it.delete() }
        val box = c.vbox(20)
        box.addView(c.muted(c.getString(R.string.fb_intro)))
        val f = c.field(c.getString(R.string.fb_hint), "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        f.edit.minLines = 3
        textField = f
        box.addView(f.layout)
        val speechAvailable = SpeechRecognizer.isRecognitionAvailable(c) ||
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(c.packageManager) != null
        val row = c.hbox()
        if (speechAvailable) row.add(c.button(c.getString(R.string.fb_speak), outlined = true) { speak() }, weight = 1f)
        if (speechAvailable) row.add(c.vbox(), width = c.dp(8))
        val rec = c.button(c.getString(R.string.fb_record), outlined = true) { toggleRecording() }
        recordButton = rec
        row.add(rec, weight = 1f)
        box.addView(row)
        status = c.muted("", 12f).also { box.addView(it) }
        box.addView(c.textButton(c.getString(R.string.fb_screenshot)) { a.pickImage.launch("image/*") })
        val log = CheckBox(c).apply { text = c.getString(R.string.fb_include_log); isChecked = true }
        box.addView(log)
        box.addView(c.muted(c.getString(R.string.fb_privacy), 12f))
        c.dialog(c.getString(R.string.fb_title)).setView(c.scroll(box))
            .setPositiveButton(R.string.fb_send) { _, _ -> stopRecording(); send(f.value, log.isChecked) }
            .setNegativeButton(R.string.cancel) { _, _ -> stopRecording() }
            .show()
    }

    // ---------- speech to text ----------
    private fun speak() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, a.getString(R.string.fb_speak_prompt))
        try { a.speechLauncher.launch(i) } catch (e: Exception) { AppLog.e("speech", e); a.toast(a.getString(R.string.fb_speech_unavailable)) }
    }

    fun onSpeech(text: String?) {
        if (text.isNullOrBlank()) return
        val e = textField?.edit ?: return
        val cur = e.text?.toString().orEmpty()
        e.setText(if (cur.isBlank()) text else "$cur $text")
        e.setSelection(e.text?.length ?: 0)
    }

    // ---------- voice note ----------
    private fun toggleRecording() {
        if (recorder != null) { stopRecording(); return }
        if (!a.hasPermission(android.Manifest.permission.RECORD_AUDIO)) { a.audioPermission.launch(android.Manifest.permission.RECORD_AUDIO); return }
        startRecording()
    }

    fun onAudioPermission(granted: Boolean) {
        if (granted) startRecording() else a.toast(a.getString(R.string.fb_mic_denied))
    }

    private fun startRecording() {
        try {
            val file = File(dir(), "voice-note.m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(a) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(64000)
            r.setMaxDuration(5 * 60_000)
            r.setOutputFile(file.absolutePath)
            r.prepare(); r.start()
            recorder = r; voice = file
            recordButton?.text = a.getString(R.string.fb_stop)
            status?.text = a.getString(R.string.fb_recording)
        } catch (e: Exception) {
            AppLog.e("voice note", e); recorder = null; a.toast(a.getString(R.string.fb_record_failed))
        }
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        try { r.stop() } catch (e: Exception) { AppLog.w("voice note stop: ${e.javaClass.simpleName}"); voice = null }
        r.release()
        recordButton?.text = a.getString(R.string.fb_record_again)
        status?.text = if (voice != null) a.getString(R.string.fb_recorded) else ""
    }

    fun onScreenshot(uri: Uri?) {
        screenshot = uri
        if (uri != null) status?.text = listOfNotNull(status?.text?.toString()?.takeIf { it.isNotBlank() }, a.getString(R.string.fb_screenshot_added)).joinToString(" • ")
    }

    // ---------- send ----------
    fun deviceInfo(): String = "Riyaly ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"

    private fun send(text: String, includeLog: Boolean) {
        val c = a
        if (text.isBlank() && voice == null && screenshot == null) { c.toast(c.getString(R.string.fb_empty)); return }
        val auth = "${c.packageName}.files"
        val uris = ArrayList<Uri>()
        voice?.takeIf { it.exists() && it.length() > 0 }?.let { uris += FileProvider.getUriForFile(c, auth, it) }
        if (includeLog) AppLog.safe("attach log", Unit) {
            val src = AppLog.file(c)
            if (src.exists()) {
                val copy = File(dir(), "riyaly-log.txt")
                copy.writeText(deviceInfo() + "\n\n" + src.readText())
                uris += FileProvider.getUriForFile(c, auth, copy)
            }
        }
        screenshot?.let { uris += it }
        val body = text + "\n\n— " + deviceInfo()
        val subject = c.getString(R.string.fb_subject, BuildConfig.VERSION_NAME)
        val i = if (uris.isEmpty()) Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        else Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).setType("*/*").apply {
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        i.putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL)).putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body)
        AppLog.i("feedback: sending (voice=${voice != null}, log=$includeLog, screenshot=${screenshot != null})")
        try { c.startActivity(Intent.createChooser(i, c.getString(R.string.fb_send_with))) }
        catch (e: Exception) { AppLog.e("feedback send", e); c.toast(c.getString(R.string.fb_no_email, EMAIL)) }
    }
}
