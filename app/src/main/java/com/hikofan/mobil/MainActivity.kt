package com.hikofan.mobil

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // ---- tema (PC uygulamasiyla ayni mor/siyah) ----
    private val cBg = Color.parseColor("#07060c")
    private val cCard = Color.parseColor("#171326")
    private val cBorder = Color.parseColor("#2b2247")
    private val cAccent = Color.parseColor("#8b5cf6")
    private val cAccentD = Color.parseColor("#6d46e8")
    private val cAccentL = Color.parseColor("#c4b5fd")
    private val cText = Color.parseColor("#f1eefb")
    private val cMuted = Color.parseColor("#a39cc0")
    private val cOk = Color.parseColor("#4ade80")
    private val cErr = Color.parseColor("#f87171")

    private val worker = Executors.newSingleThreadExecutor()
    private val infoWorker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val pid = "hikofan_dl"
    private val infoPid = "hikofan_info"

    @Volatile private var ready = false
    @Volatile private var busy = false
    @Volatile private var cancelled = false
    @Volatile private var infoToken = 0

    private var lastInfoUrl: String? = null
    private var audioMode = false
    private var maxRes = 0                       // 0 = en yuksek
    private var lastSavedUri: Uri? = null
    private var lastSavedMime = "video/mp4"

    // arayuz parcalari
    private lateinit var urlBox: EditText
    private lateinit var previewCard: LinearLayout
    private lateinit var thumb: ImageView
    private lateinit var thumbSpinner: ProgressBar
    private lateinit var durBadge: TextView
    private lateinit var titleTv: TextView
    private lateinit var metaTv: TextView
    private lateinit var segVideo: TextView
    private lateinit var segAudio: TextView
    private lateinit var chipsRow: LinearLayout
    private val chips = mutableListOf<Pair<Int, TextView>>()
    private lateinit var compat: CheckBox
    private lateinit var btnDownload: Button
    private lateinit var progressCard: LinearLayout
    private lateinit var bar: ProgressBar
    private lateinit var status: TextView
    private lateinit var btnCancel: Button
    private lateinit var resultRow: LinearLayout
    private lateinit var btnUpdate: Button

    private val infoRunnable = Runnable { onUrlChanged() }

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

    private fun mkSeg(label: String) = TextView(this).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }

    private fun buildUi() {
        val root = ScrollView(this).apply { setBackgroundColor(cBg); isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(44), dp(22), dp(28))
        }
        root.addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ---- baslik
        col.addView(TextView(this).apply {
            text = "HIKOFAN"; textSize = 30f; typeface = Typeface.DEFAULT_BOLD; setTextColor(cAccentL)
        })
        col.addView(TextView(this).apply {
            text = "Linki yapıştır, önizlemeyi gör, en yüksek kalitede indir."; textSize = 14f; setTextColor(cMuted)
        }, lp(MATCH_PARENT, WRAP_CONTENT, top = 2))

        // ---- link kutusu
        urlBox = EditText(this).apply {
            hint = "Video linki (YouTube, Instagram, X, TikTok…)"
            setHintTextColor(Color.parseColor("#665f86"))
            setTextColor(cText); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1; maxLines = 3
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(cCard, 14, cBorder)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        urlBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(infoRunnable)
                handler.postDelayed(infoRunnable, 700)
                refreshDownloadButton()
            }
        })
        col.addView(urlBox, lp(MATCH_PARENT, WRAP_CONTENT, top = 22))

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val paste = mkButton("Yapıştır", cCard).apply { background = rounded(cCard, 12, cBorder); textSize = 14f }
        val clear = mkButton("Temizle", cCard).apply { background = rounded(cCard, 12, cBorder); textSize = 14f }
        paste.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (t.isNullOrBlank()) toast("Panoda link yok") else urlBox.setText(extractUrl(t) ?: t)
        }
        clear.setOnClickListener { urlBox.setText("") }
        row1.addView(paste, lp(0, dp(42), 1f))
        row1.addView(clear, lp(0, dp(42), 1f, left = 10))
        col.addView(row1, lp(MATCH_PARENT, WRAP_CONTENT, top = 10))

        // ---- onizleme karti
        previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCard, 16, cBorder)
            setPadding(dp(12), dp(12), dp(12), dp(14))
            visibility = View.GONE
        }
        val thumbW = resources.displayMetrics.widthPixels - dp(22 * 2) - dp(12 * 2)
        val thumbFrame = FrameLayout(this).apply {
            background = rounded(Color.parseColor("#0e0b1a"), 12)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
            clipToOutline = true
        }
        thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        thumbFrame.addView(thumb, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        thumbSpinner = ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(cAccentL)
        }
        thumbFrame.addView(thumbSpinner, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        durBadge = TextView(this).apply {
            textSize = 12f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            background = rounded(Color.parseColor("#cc000000"), 6)
            setPadding(dp(7), dp(3), dp(7), dp(3))
            visibility = View.GONE
        }
        thumbFrame.addView(durBadge, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        })
        previewCard.addView(thumbFrame, LinearLayout.LayoutParams(MATCH_PARENT, thumbW * 9 / 16))

        titleTv = TextView(this).apply {
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(cText)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        previewCard.addView(titleTv, lp(MATCH_PARENT, WRAP_CONTENT, top = 10))
        metaTv = TextView(this).apply { textSize = 13f; setTextColor(cMuted); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        previewCard.addView(metaTv, lp(MATCH_PARENT, WRAP_CONTENT, top = 3))
        col.addView(previewCard, lp(MATCH_PARENT, WRAP_CONTENT, top = 16))

        // ---- format secici (Video / Ses)
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(cCard, 14, cBorder)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        segVideo = mkSeg("Video  MP4")
        segAudio = mkSeg("Ses  MP3")
        segVideo.setOnClickListener { selectMode(false) }
        segAudio.setOnClickListener { selectMode(true) }
        seg.addView(segVideo, lp(0, dp(44), 1f))
        seg.addView(segAudio, lp(0, dp(44), 1f))
        col.addView(seg, lp(MATCH_PARENT, WRAP_CONTENT, top = 18))

        // ---- kalite secici
        chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val opts = listOf(0 to "Maks", 1080 to "1080p", 720 to "720p", 480 to "480p")
        opts.forEachIndexed { idx, (res, label) ->
            val tv = TextView(this).apply {
                text = label; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            }
            tv.setOnClickListener { selectRes(res) }
            chips.add(res to tv)
            chipsRow.addView(tv, lp(0, dp(38), 1f, left = if (idx == 0) 0 else 8))
        }
        col.addView(chipsRow, lp(MATCH_PARENT, WRAP_CONTENT, top = 10))

        compat = CheckBox(this).apply {
            text = "Uyumlu mod (H.264, en fazla 1080p – her telefonda açılır)"
            setTextColor(cMuted); textSize = 13f
            buttonTintList = ColorStateList.valueOf(cAccent)
        }
        col.addView(compat, lp(MATCH_PARENT, WRAP_CONTENT, top = 8))

        // ---- ana indirme butonu
        btnDownload = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            stateListAnimator = null
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(cAccent, cAccentD)).apply {
                cornerRadius = dp(16).toFloat()
            }
        }
        btnDownload.setOnClickListener { start() }
        col.addView(btnDownload, lp(MATCH_PARENT, dp(58), top = 12))

        // ---- ilerleme karti
        progressCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCard, 16, cBorder)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            visibility = View.GONE
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(cAccent)
            progressBackgroundTintList = ColorStateList.valueOf(cBorder)
        }
        progressCard.addView(bar, lp(MATCH_PARENT, dp(10)))
        status = TextView(this).apply { text = "Motor hazırlanıyor…"; textSize = 14f; setTextColor(cMuted) }
        progressCard.addView(status, lp(MATCH_PARENT, WRAP_CONTENT, top = 10))

        btnCancel = mkButton("İptal", Color.parseColor("#3a1220")).apply { visibility = View.GONE; textSize = 14f }
        btnCancel.setOnClickListener {
            cancelled = true
            Thread { try { YoutubeDL.getInstance().destroyProcessById(pid) } catch (_: Exception) {} }.start()
        }
        progressCard.addView(btnCancel, lp(MATCH_PARENT, dp(42), top = 12))

        resultRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        val btnOpen = mkButton("Aç", cAccent).apply { textSize = 14f }
        val btnShare = mkButton("Paylaş", Color.parseColor("#2a1d55")).apply { textSize = 14f }
        btnOpen.setOnClickListener { openSaved() }
        btnShare.setOnClickListener { shareSaved() }
        resultRow.addView(btnOpen, lp(0, dp(44), 1f))
        resultRow.addView(btnShare, lp(0, dp(44), 1f, left = 10))
        progressCard.addView(resultRow, lp(MATCH_PARENT, WRAP_CONTENT, top = 12))
        col.addView(progressCard, lp(MATCH_PARENT, WRAP_CONTENT, top = 14))

        btnUpdate = mkButton("yt-dlp güncelle (indirme hata verirse bas)", cBg).apply {
            background = rounded(cBg, 12, cBorder); textSize = 12f; setTextColor(cMuted)
        }
        btnUpdate.setOnClickListener { updateEngine() }
        col.addView(btnUpdate, lp(MATCH_PARENT, dp(40), top = 28))

        setContentView(root)
        selectMode(false)
        selectRes(0)
        setStatus("Motor hazırlanıyor…", cMuted)
        refreshDownloadButton()
    }

    private fun onUi(block: () -> Unit) = runOnUiThread { block() }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun setStatus(s: String, color: Int = cMuted) {
        status.text = s
        status.setTextColor(color)
    }

    private fun styleSeg(v: TextView, on: Boolean) {
        v.background = if (on) rounded(cAccent, 11) else null
        v.setTextColor(if (on) Color.WHITE else cMuted)
    }

    private fun selectMode(audio: Boolean) {
        audioMode = audio
        styleSeg(segVideo, !audio)
        styleSeg(segAudio, audio)
        val vis = if (audio) View.GONE else View.VISIBLE
        chipsRow.visibility = vis
        compat.visibility = vis
        refreshDownloadButton()
    }

    private fun selectRes(res: Int) {
        maxRes = res
        chips.forEach { (r, tv) ->
            val on = r == res
            tv.background = if (on) rounded(cAccent, 10) else rounded(cCard, 10, cBorder)
            tv.setTextColor(if (on) Color.WHITE else cMuted)
        }
        refreshDownloadButton()
    }

    // Videoda olmayan (daha yuksek) kalite secenekleri gizlenir
    private fun updateChipsForHeight(h: Int) {
        chips.forEach { (r, tv) ->
            if (r != 0) tv.visibility = if (h > 0 && r >= h) View.GONE else View.VISIBLE
        }
        val cur = chips.firstOrNull { it.first == maxRes }
        if (cur != null && cur.second.visibility == View.GONE) selectRes(0)
    }

    private fun refreshDownloadButton() {
        if (!::btnDownload.isInitialized) return
        val hasUrl = extractUrl(urlBox.text.toString()) != null
        val ok = hasUrl && ready && !busy
        btnDownload.isEnabled = ok
        btnDownload.alpha = if (ok) 1f else 0.4f
        btnDownload.text = when {
            !ready -> "Motor hazırlanıyor…"
            busy -> "İndiriliyor…"
            !hasUrl -> "Önce bir link yapıştır"
            audioMode -> "↓  MP3 olarak indir"
            maxRes > 0 -> "↓  MP4 indir  ·  en fazla ${maxRes}p"
            else -> "↓  MP4 indir  ·  en yüksek kalite"
        }
    }

    private fun setIdle(idle: Boolean) {
        busy = !idle
        btnUpdate.isEnabled = idle
        btnCancel.visibility = if (idle) View.GONE else View.VISIBLE
        refreshDownloadButton()
    }

    // ------------------------------------------------------------ onizleme (video bilgisi)
    private data class Info(
        val title: String, val uploader: String, val platform: String,
        val duration: Double, val maxH: Int, val bmp: Bitmap?
    )

    private fun onUrlChanged() {
        val url = extractUrl(urlBox.text.toString())
        if (url == null) {
            infoToken++
            lastInfoUrl = null
            previewCard.visibility = View.GONE
            updateChipsForHeight(0)
            return
        }
        if (!ready) return                       // motor hazir olunca tekrar denenir
        if (url == lastInfoUrl) return
        fetchInfo(url)
    }

    private fun fetchInfo(url: String) {
        val token = ++infoToken
        lastInfoUrl = url
        // onizleme yukleniyor durumu
        previewCard.visibility = View.VISIBLE
        thumb.setImageDrawable(null)
        thumbSpinner.visibility = View.VISIBLE
        durBadge.visibility = View.GONE
        titleTv.text = "Video bilgisi alınıyor…"
        metaTv.text = ""

        val myPid = infoPid + "_" + token
        val prevPid = infoPid + "_" + (token - 1)
        Thread { try { YoutubeDL.getInstance().destroyProcessById(prevPid) } catch (_: Exception) {} }.start()
        infoWorker.execute {
            try {
                val r = YoutubeDLRequest(url)
                r.addOption("--dump-single-json")
                r.addOption("--no-playlist")
                r.addOption("--no-warnings")
                val resp = YoutubeDL.getInstance().execute(r, myPid) { _, _, _ -> }
                val raw = resp.out
                val js = raw.lines().lastOrNull { it.trim().startsWith("{") } ?: raw
                val o = JSONObject(js)

                var thumbUrl = o.optString("thumbnail", "")
                if (thumbUrl.isBlank()) {
                    val arr = o.optJSONArray("thumbnails")
                    if (arr != null && arr.length() > 0) thumbUrl = arr.getJSONObject(arr.length() - 1).optString("url", "")
                }
                var maxH = 0
                val fm = o.optJSONArray("formats")
                if (fm != null) {
                    for (i in 0 until fm.length()) {
                        val f = fm.optJSONObject(i) ?: continue
                        if (f.optString("vcodec", "none") == "none") continue
                        val h = f.optInt("height", 0)
                        if (h > maxH) maxH = h
                    }
                }
                val uploader = o.optString("uploader", "").ifBlank { o.optString("channel", "") }
                val info = Info(
                    title = o.optString("title", "").ifBlank { "Başlıksız video" },
                    uploader = if (uploader == "null") "" else uploader,
                    platform = o.optString("extractor_key", ""),
                    duration = o.optDouble("duration", 0.0).let { if (it.isNaN()) 0.0 else it },
                    maxH = maxH,
                    bmp = if (thumbUrl.isNotBlank()) loadBitmap(thumbUrl) else null
                )
                onUi { if (token == infoToken) showPreview(info) }
            } catch (e: Exception) {
                onUi {
                    if (token == infoToken) {
                        thumbSpinner.visibility = View.GONE
                        titleTv.text = "Önizleme alınamadı"
                        metaTv.text = "Yine de aşağıdaki butonla indirmeyi deneyebilirsin."
                    }
                }
            }
        }
    }

    private fun loadBitmap(u: String): Bitmap? = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        c.inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }

    private fun showPreview(i: Info) {
        thumbSpinner.visibility = View.GONE
        if (i.bmp != null) thumb.setImageBitmap(i.bmp)
        if (i.duration > 0) {
            durBadge.text = fmtDuration(i.duration)
            durBadge.visibility = View.VISIBLE
        } else durBadge.visibility = View.GONE
        titleTv.text = i.title
        val parts = mutableListOf<String>()
        if (i.uploader.isNotBlank()) parts.add(i.uploader)
        if (i.platform.isNotBlank()) parts.add(i.platform)
        if (i.maxH > 0) parts.add("en fazla ${i.maxH}p")
        metaTv.text = parts.joinToString("  •  ")
        updateChipsForHeight(i.maxH)
    }

    private fun fmtDuration(sec: Double): String {
        val s = sec.toLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, ss)
        else String.format(Locale.US, "%d:%02d", m, ss)
    }

    // ------------------------------------------------------------ motor
    private fun initEngine() {
        worker.execute {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                ready = true
                onUi {
                    setStatus("Hazır.", cMuted)
                    refreshDownloadButton()
                    onUrlChanged()
                }
            } catch (e: Exception) {
                onUi {
                    progressCard.visibility = View.VISIBLE
                    setStatus("Başlatma hatası: ${e.message}", cErr)
                }
            }
        }
    }

    private fun updateEngine() {
        if (!ready || busy) { toast("Biraz bekle"); return }
        setIdle(false); btnCancel.visibility = View.GONE
        progressCard.visibility = View.VISIBLE
        resultRow.visibility = View.GONE
        setStatus("yt-dlp güncelleniyor…", cMuted)
        worker.execute {
            val msg = try {
                val s = YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
                if (s.toString().contains("ALREADY")) "yt-dlp zaten güncel." else "yt-dlp güncellendi ✓"
            } catch (e: Exception) {
                "Güncelleme başarısız: " + errLine(e)
            }
            onUi { setStatus(msg, cMuted); setIdle(true) }
        }
    }

    private fun extractUrl(t: String): String? =
        Regex("https?://\\S+").find(t)?.value?.trimEnd(')', ']', '.', ',')

    private fun errLine(e: Exception): String {
        val lines = (e.message ?: e.toString()).lines().map { it.trim() }.filter { it.isNotEmpty() }
        val l = lines.firstOrNull { it.startsWith("ERROR") } ?: lines.lastOrNull() ?: "bilinmeyen hata"
        return if (l.length > 220) l.take(220) + "…" else l
    }

    private fun start() {
        val url = extractUrl(urlBox.text.toString())
        if (url == null) { toast("Önce geçerli bir link yapıştır"); return }
        if (!ready) { toast("Motor hazırlanıyor, birkaç saniye bekle"); return }
        if (busy) return
        cancelled = false
        val audio = audioMode
        val useCompat = compat.isChecked
        val wantRes = maxRes

        setIdle(false)
        progressCard.visibility = View.VISIBLE
        resultRow.visibility = View.GONE
        bar.progress = 0
        setStatus("Başlıyor…", cMuted)

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
                    // Secilen kaliteye kadar en yuksek cozunurluk -> en yuksek fps -> H.264 tercihi.
                    // Uyumlu modda 1080p tavan.
                    val cap = when {
                        useCompat && wantRes == 0 -> 1080
                        useCompat -> minOf(wantRes, 1080)
                        else -> wantRes
                    }
                    r.addOption("-f", "bv*+ba/b")
                    r.addOption("-S", (if (cap > 0) "res:$cap" else "res") + ",fps,vcodec:h264,acodec:aac")
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
                    onUi { bar.progress = pr; setStatus(txt, cMuted) }
                }

                val exts = setOf("mp4", "mkv", "webm", "mp3", "m4a", "opus")
                val out = tmp.listFiles()?.filter { it.isFile && it.extension.lowercase() in exts }
                    ?.maxByOrNull { it.lastModified() }
                if (out == null) {
                    onUi { setStatus("Dosya bulunamadı, tekrar dene.", cErr) }
                } else {
                    onUi { setStatus("Kaydediliyor…", cMuted) }
                    val (name, uri, mime) = saveToDownloads(out)
                    lastSavedUri = uri
                    lastSavedMime = mime
                    onUi {
                        bar.progress = 100
                        setStatus("✓ Bitti!\nİndirilenler/HIKOFAN/" + name, cOk)
                        resultRow.visibility = if (uri != null) View.VISIBLE else View.GONE
                    }
                }
            } catch (e: Exception) {
                val m = if (cancelled) "İptal edildi." else "Hata: " + errLine(e) + "\n\n(Hata sürerse alttaki 'yt-dlp güncelle' butonuna bas.)"
                onUi { setStatus(m, if (cancelled) cMuted else cErr) }
            } finally {
                tmp.deleteRecursively()
                onUi { setIdle(true) }
            }
        }
    }

    private fun openSaved() {
        val uri = lastSavedUri ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, lastSavedMime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            toast("Bu dosyayı açacak uygulama bulunamadı")
        }
    }

    private fun shareSaved() {
        val uri = lastSavedUri ?: return
        try {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = lastSavedMime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "Paylaş"))
        } catch (_: Exception) {
            toast("Paylaşılamadı")
        }
    }

    // Dosyayi telefonun Indirilenler/HIKOFAN klasorune kopyalar. (ad, uri, mime) dondurur.
    private fun saveToDownloads(f: File): Triple<String, Uri?, String> {
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
            return Triple(f.name, uri, mime)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HIKOFAN")
            dir.mkdirs()
            val dest = File(dir, f.name)
            f.copyTo(dest, true)
            MediaScannerConnection.scanFile(this, arrayOf(dest.absolutePath), arrayOf(mime), null)
            return Triple(f.name, null, mime)   // eski Android: Aç/Paylaş butonu gösterilmez
        }
    }
}
