package com.hikofan.mobil

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // ---- tema (PC uygulamasiyla ayni mor/siyah) ----
    private val cBg = Color.parseColor("#07060c")
    private val cCard = Color.parseColor("#171326")
    private val cBorder = Color.parseColor("#2b2247")
    private val cAccent = Color.parseColor("#8b5cf6")
    private val cAccentL = Color.parseColor("#c4b5fd")
    private val cText = Color.parseColor("#f1eefb")
    private val cMuted = Color.parseColor("#a39cc0")

    private val worker = Executors.newSingleThreadExecutor()
    private val pid = "hikofan_dl"

    @Volatile private var ready = false
    @Volatile private var busy = false
    @Volatile private var cancelled = false

    private lateinit var urlBox: EditText
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var btnMp4: Button
    private lateinit var btnMp3: Button
    private lateinit var btnCancel: Button
    private lateinit var btnUpdate: Button
    private lateinit var compat: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
        handleIntent(intent)
        initEngine()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // Baska uygulamadan "Paylas" ile link gelirse kutuya yaz
    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT)
            if (!t.isNullOrBlank()) urlBox.setText(extractUrl(t) ?: t)
        }
    }

    // ------------------------------------------------------------ arayuz
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int = 14, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun mkButton(label: String, color: Int) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(color)
        stateListAnimator = null
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f, top: Int = 0, left: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top); leftMargin = dp(left) }

    private fun buildUi() {
        val root = ScrollView(this).apply { setBackgroundColor(cBg); isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(48), dp(22), dp(28))
        }
        root.addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        col.addView(TextView(this).apply {
            text = "HIKOFAN"; textSize = 32f; typeface = Typeface.DEFAULT_BOLD; setTextColor(cAccentL)
        })
        col.addView(TextView(this).apply {
            text = "Linki yapıştır, en yüksek kalitede indir."; textSize = 14f; setTextColor(cMuted)
        }, lp(MATCH_PARENT, WRAP_CONTENT, top = 2))

        urlBox = EditText(this).apply {
            hint = "Video linki (YouTube, Instagram, X, TikTok…)"
            setHintTextColor(Color.parseColor("#665f86"))
            setTextColor(cText); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2; maxLines = 4
            gravity = Gravity.TOP
            background = rounded(cCard, 14, cBorder)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        col.addView(urlBox, lp(MATCH_PARENT, WRAP_CONTENT, top = 26))

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val paste = mkButton("Yapıştır", cCard).apply { background = rounded(cCard, 12, cBorder); textSize = 14f }
        val clear = mkButton("Temizle", cCard).apply { background = rounded(cCard, 12, cBorder); textSize = 14f }
        paste.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (t.isNullOrBlank()) toast("Panoda link yok") else urlBox.setText(extractUrl(t) ?: t)
        }
        clear.setOnClickListener { urlBox.setText("") }
        row1.addView(paste, lp(0, dp(44), 1f))
        row1.addView(clear, lp(0, dp(44), 1f, left = 10))
        col.addView(row1, lp(MATCH_PARENT, WRAP_CONTENT, top = 10))

        compat = CheckBox(this).apply {
            text = "Uyumlu mod (H.264, en fazla 1080p – her telefonda açılır)"
            setTextColor(cMuted); textSize = 13f
            buttonTintList = ColorStateList.valueOf(cAccent)
        }
        col.addView(compat, lp(MATCH_PARENT, WRAP_CONTENT, top = 14))

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnMp4 = mkButton("MP4 Video", cAccent)
        btnMp3 = mkButton("MP3 Ses", Color.parseColor("#2a1d55"))
        btnMp4.setOnClickListener { start(audio = false) }
        btnMp3.setOnClickListener { start(audio = true) }
        row2.addView(btnMp4, lp(0, dp(56), 1f))
        row2.addView(btnMp3, lp(0, dp(56), 1f, left = 10))
        col.addView(row2, lp(MATCH_PARENT, WRAP_CONTENT, top = 14))

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(cAccent)
            progressBackgroundTintList = ColorStateList.valueOf(cBorder)
        }
        col.addView(bar, lp(MATCH_PARENT, dp(10), top = 26))

        status = TextView(this).apply { text = "Motor hazırlanıyor…"; textSize = 14f; setTextColor(cMuted) }
        col.addView(status, lp(MATCH_PARENT, WRAP_CONTENT, top = 10))

        btnCancel = mkButton("İptal", Color.parseColor("#3a1220")).apply { visibility = View.GONE; textSize = 14f }
        btnCancel.setOnClickListener {
            cancelled = true
            Thread { try { YoutubeDL.getInstance().destroyProcessById(pid) } catch (_: Exception) {} }.start()
        }
        col.addView(btnCancel, lp(MATCH_PARENT, dp(44), top = 12))

        btnUpdate = mkButton("yt-dlp güncelle (indirme hata verirse bas)", cBg).apply {
            background = rounded(cBg, 12, cBorder); textSize = 12f; setTextColor(cMuted)
        }
        btnUpdate.setOnClickListener { updateEngine() }
        col.addView(btnUpdate, lp(MATCH_PARENT, dp(40), top = 30))

        setContentView(root)
    }

    private fun onUi(block: () -> Unit) = runOnUiThread { block() }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun setIdle(idle: Boolean) {
        btnMp4.isEnabled = idle; btnMp3.isEnabled = idle; btnUpdate.isEnabled = idle
        btnMp4.alpha = if (idle) 1f else 0.5f; btnMp3.alpha = if (idle) 1f else 0.5f
        btnCancel.visibility = if (idle) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------ motor
    private fun initEngine() {
        worker.execute {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                ready = true
                onUi { status.text = "Hazır. Bir link yapıştır." }
            } catch (e: Exception) {
                onUi { status.text = "Başlatma hatası: ${e.message}" }
            }
        }
    }

    private fun updateEngine() {
        if (!ready || busy) { toast("Biraz bekle"); return }
        busy = true; setIdle(false); btnCancel.visibility = View.GONE
        onUi { status.text = "yt-dlp güncelleniyor…" }
        worker.execute {
            val msg = try {
                val s = YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
                if (s.toString().contains("ALREADY")) "yt-dlp zaten güncel." else "yt-dlp güncellendi ✓"
            } catch (e: Exception) {
                "Güncelleme başarısız: " + errLine(e)
            }
            busy = false
            onUi { status.text = msg; setIdle(true) }
        }
    }

    private fun extractUrl(t: String): String? =
        Regex("https?://\\S+").find(t)?.value?.trimEnd(')', ']', '.', ',')

    private fun errLine(e: Exception): String {
        val lines = (e.message ?: e.toString()).lines().map { it.trim() }.filter { it.isNotEmpty() }
        val l = lines.firstOrNull { it.startsWith("ERROR") } ?: lines.lastOrNull() ?: "bilinmeyen hata"
        return if (l.length > 220) l.take(220) + "…" else l
    }

    private fun start(audio: Boolean) {
        val url = extractUrl(urlBox.text.toString())
        if (url == null) { toast("Önce geçerli bir link yapıştır"); return }
        if (!ready) { toast("Motor hazırlanıyor, birkaç saniye bekle"); return }
        if (busy) return
        busy = true; cancelled = false
        setIdle(false)
        bar.progress = 0
        status.text = "Başlıyor…"
        val useCompat = compat.isChecked

        worker.execute {
            val tmp = File(getExternalFilesDir(null) ?: cacheDir, "tmp")
            tmp.deleteRecursively(); tmp.mkdirs()
            try {
                val r = YoutubeDLRequest(url)
                r.addOption("--no-playlist")
                r.addOption("--windows-filenames")
                r.addOption("--no-mtime")
                r.addOption("-o", tmp.absolutePath + "/%(title).100B.%(ext)s")
                if (audio) {
                    r.addOption("-f", "bestaudio/best")
                    r.addOption("-x")
                    r.addOption("--audio-format", "mp3")
                    r.addOption("--audio-quality", "320K")
                    r.addOption("--embed-metadata")
                } else {
                    // En yuksek cozunurluk -> en yuksek fps -> H.264 tercihi. Uyumlu modda 1080p tavan.
                    r.addOption("-f", "bv*+ba/b")
                    r.addOption("-S", if (useCompat) "res:1080,fps,vcodec:h264,acodec:aac" else "res,fps,vcodec:h264,acodec:aac")
                    r.addOption("--merge-output-format", "mp4")
                }

                YoutubeDL.getInstance().execute(r, pid) { p, eta, line ->
                    val pr = p.toInt().coerceIn(0, 100)
                    val txt = when {
                        line.contains("[Merger]") -> "Video ve ses birleştiriliyor…"
                        line.contains("[ExtractAudio]") -> "MP3'e çevriliyor…"
                        pr < 100 -> "İndiriliyor %" + pr + (if (eta > 0) "  •  kalan " + eta + " sn" else "")
                        else -> "İşleniyor…"
                    }
                    onUi { bar.progress = pr; status.text = txt }
                }

                val exts = setOf("mp4", "mkv", "webm", "mp3", "m4a", "opus")
                val out = tmp.listFiles()?.filter { it.isFile && it.extension.lowercase() in exts }
                    ?.maxByOrNull { it.lastModified() }
                if (out == null) {
                    onUi { status.text = "Dosya bulunamadı, tekrar dene." }
                } else {
                    onUi { status.text = "Kaydediliyor…" }
                    val name = saveToDownloads(out)
                    onUi { bar.progress = 100; status.text = "✓ Bitti!\nİndirilenler/HIKOFAN/" + name }
                }
            } catch (e: Exception) {
                val m = if (cancelled) "İptal edildi." else "Hata: " + errLine(e) + "\n\n(Hata sürerse alttaki 'yt-dlp güncelle' butonuna bas.)"
                onUi { status.text = m }
            } finally {
                tmp.deleteRecursively()
                busy = false
                onUi { setIdle(true) }
            }
        }
    }

    // Dosyayi telefonun Indirilenler/HIKOFAN klasorune kopyalar
    private fun saveToDownloads(f: File): String {
        val mime = when (f.extension.lowercase()) {
            "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; else -> "application/octet-stream"
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/HIKOFAN")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IllegalStateException("Kayıt oluşturulamadı")
            contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            cv.clear(); cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, cv, null, null)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HIKOFAN")
            dir.mkdirs()
            val dest = File(dir, f.name)
            f.copyTo(dest, true)
            MediaScannerConnection.scanFile(this, arrayOf(dest.absolutePath), arrayOf(mime), null)
        }
        return f.name
    }
}
