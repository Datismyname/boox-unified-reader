package com.unified.ereader

import android.Manifest
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.*
import android.os.*
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.Html
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.zip.ZipFile

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var libraryContainer: LinearLayout
    private lateinit var readerContainer: LinearLayout

    private lateinit var tvHeaderTitle: TextView
    private lateinit var btnVoiceCommand: Button
    private lateinit var gridBooksOrApps: GridView
    private lateinit var libraryControlBar: LinearLayout
    private lateinit var searchBar: EditText
    private lateinit var btnCloudSync: Button
    private lateinit var btnApiKeySetup: Button
    private lateinit var btnToggleAppsView: Button
    private lateinit var btnSystemSettings: Button
    private lateinit var btnWifi: Button
    private lateinit var btnLibraryLock: ImageButton

    private lateinit var epubWebView: WebView
    private lateinit var pdfImageView: ImageView
    private lateinit var touchZoneRight: View
    private lateinit var touchZoneLeft: View
    private lateinit var tvRewindBanner: TextView
    private lateinit var tvPageStatus: TextView
    private lateinit var tvBatteryStatus: TextView
    private lateinit var btnHomeLauncher: ImageButton
    private lateinit var btnReaderLock: ImageButton
    private lateinit var btnReaderVoice: Button
    private lateinit var btnRewindHistory: Button
    private lateinit var btnZoomIn: Button
    private lateinit var btnZoomOut: Button
    private lateinit var btnSaveBookmark: Button
    private lateinit var btnGotoBookmark: Button
    private lateinit var btnDarkMode: Button
    private lateinit var btnLineSpacing: Button
    private lateinit var btnSpeak: Button

    private var isInReaderMode = false
    private var isLibraryUnlocked = false
    private var isReaderUnlocked = false
    private var showingAppsMode = false

    private var allBooks: List<File> = emptyList()
    private var allApps: List<ResolveInfo> = emptyList()
    private val coverCache = mutableMapOf<String, Bitmap>()
    private val stateProps = Properties()
    private var metadataJson = JSONObject()

    private var fontSize = 26
    private var pdfZoomScale = 1.0f
    private var isDark = false
    private var lineSpacing = 1.4f
    private var currentHtmlContent = ""
    private var currentBookType = "TEXT"
    private var currentBookFile: File? = null

    private var pdfRenderer: PdfRenderer? = null
    private var currentPdfPageIndex = 0
    private var pdfFileDescriptor: ParcelFileDescriptor? = null

    private var epubChapters: List<String> = emptyList()
    private var currentChapterIndex = 0
    private var currentTextPage = 0
    private var totalTextPages = 1

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var syncedMediaPlayer: MediaPlayer? = null
    private var isPlayingSyncedAudio = false
    private var mediaRecorder: MediaRecorder? = null
    private var recordingDialog: AlertDialog? = null

    private var lastAutoSyncTime = 0L
    private var connectivityManager: ConnectivityManager? = null
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { triggerSilentBackgroundSync() }
        }
    }

    private val autoLockHandler = Handler(Looper.getMainLooper())
    private val autoLockRunnable = Runnable {
        if (isLibraryUnlocked || isReaderUnlocked) {
            isLibraryUnlocked = false
            isReaderUnlocked = false
            showingAppsMode = false
            applyLibraryLockState()
            applyReaderToolbarVisibility()
            Toast.makeText(this, "تم القفل التلقائي", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_main)

        PDFBoxResourceLoader.init(applicationContext)
        requestPermissionsIfNeeded()
        setupKioskPolicyIfOwner()

        prefs = getSharedPreferences("UnifiedReaderPrefs", Context.MODE_PRIVATE)
        tts = TextToSpeech(this, this)

        bindViews()
        setupLibraryEvents()
        setupReaderEvents()

        loadBooks()
        loadStateProps()
        loadLocalMetadataJson()
        loadInstalledApps()
        applyLibraryLockState()
        applyReaderToolbarVisibility()

        registerWifiAutoSync()
        hideSystemBars()
        promptSetDefaultHomeIfFirstRun()
        showLibraryView()
    }

    private fun bindViews() {
        libraryContainer = findViewById(R.id.libraryContainer)
        readerContainer = findViewById(R.id.readerContainer)
        tvHeaderTitle = findViewById(R.id.tvHeaderTitle)
        btnVoiceCommand = findViewById(R.id.btnVoiceCommand)
        gridBooksOrApps = findViewById(R.id.gridBooksOrApps)
        libraryControlBar = findViewById(R.id.libraryControlBar)
        searchBar = findViewById(R.id.searchBar)
        btnCloudSync = findViewById(R.id.btnCloudSync)
        btnApiKeySetup = findViewById(R.id.btnApiKeySetup)
        btnToggleAppsView = findViewById(R.id.btnToggleAppsView)
        btnSystemSettings = findViewById(R.id.btnSystemSettings)
        btnWifi = findViewById(R.id.btnWifi)
        btnLibraryLock = findViewById(R.id.btnLibraryLock)

        epubWebView = findViewById(R.id.epubWebView)
        pdfImageView = findViewById(R.id.pdfImageView)
        touchZoneRight = findViewById(R.id.touchZoneRight)
        touchZoneLeft = findViewById(R.id.touchZoneLeft)
        tvRewindBanner = findViewById(R.id.tvRewindBanner)
        tvPageStatus = findViewById(R.id.tvPageStatus)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        btnHomeLauncher = findViewById(R.id.btnHomeLauncher)
        btnReaderLock = findViewById(R.id.btnReaderLock)
        btnReaderVoice = findViewById(R.id.btnReaderVoice)
        btnRewindHistory = findViewById(R.id.btnRewindHistory)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        btnSaveBookmark = findViewById(R.id.btnSaveBookmark)
        btnGotoBookmark = findViewById(R.id.btnGotoBookmark)
        btnDarkMode = findViewById(R.id.btnDarkMode)
        btnLineSpacing = findViewById(R.id.btnLineSpacing)
        btnSpeak = findViewById(R.id.btnSpeak)

        epubWebView.settings.javaScriptEnabled = false
        epubWebView.isVerticalScrollBarEnabled = false
        fontSize = prefs.getInt("FONT_SIZE", 26)
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }

    private fun isDefaultHomeApp(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        val res = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return res?.activityInfo?.packageName == packageName
    }

    private fun promptSetDefaultHomeIfFirstRun() {
        if (!isDefaultHomeApp() && !prefs.getBoolean("SKIP_HOME_PROMPT", false)) {
            AlertDialog.Builder(this)
                .setTitle("تعيين كواجهة رئيسية مبسطة")
                .setMessage("هل تريد جعل المكتبة الموحدة هي الشاشة الرئيسية للجهاز بضغطة واحدة؟")
                .setPositiveButton("نعم، تعيين الآن") { _, _ -> requestDefaultHomeRole() }
                .setNegativeButton("لاحقاً") { _, _ -> prefs.edit().putBoolean("SKIP_HOME_PROMPT", true).apply() }
                .show()
        }
    }

    private fun requestDefaultHomeRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME) && !roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                @Suppress("DEPRECATION")
                startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME), 707)
                return
            }
        }
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun showLibraryView() {
        saveCurrentReadingPosition()
        stopAllAudio()
        tvRewindBanner.visibility = View.GONE
        isInReaderMode = false
        readerContainer.visibility = View.GONE
        libraryContainer.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loadBooks()
        loadStateProps()
        loadLocalMetadataJson()
        refreshGrid(searchBar.text.toString())
    }

    private fun showReaderView(bookFile: File, autoStartAudio: Boolean = false) {
        isInReaderMode = true
        tvRewindBanner.visibility = View.GONE
        libraryContainer.visibility = View.GONE
        readerContainer.visibility = View.VISIBLE
        prefs.edit().putString("LAST_OPENED_BOOK", bookFile.name).apply()
        if (prefs.getBoolean("KEEP_SCREEN_ON", true)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        updateBatteryDisplay()
        openBookFile(bookFile)
        if (autoStartAudio) {
            Handler(Looper.getMainLooper()).postDelayed({ speakCurrentPageContent() }, 600)
        }
    }

    private fun startDirectVoiceAssistant() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }
        val apiKey = prefs.getString("GEMINI_API_KEY", "") ?: ""
        if (apiKey.isBlank()) {
            Toast.makeText(this, "يرجى إدخال مفتاح Gemini API من إعدادات القفل أو مزامنته من الجوال", Toast.LENGTH_LONG).show()
            return
        }

        val audioFile = File(cacheDir, "voice_cmd.m4a")
        if (audioFile.exists()) audioFile.delete()

        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
            else { @Suppress("DEPRECATION") MediaRecorder() }
            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(32000)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر تشغيل المايكروفون: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 20)
            gravity = Gravity.CENTER
            addView(TextView(this@MainActivity).apply {
                text = "🎙️ تحدث الآن بطلبك...\n(مثلاً: افتح كافكا، أو أضعت الصفحة ارجع 5 دقائق)"
                textSize = 20f
                setTextColor(Color.BLACK)
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            })
        }

        recordingDialog = AlertDialog.Builder(this)
            .setTitle("المساعد الصوتي الذكي")
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("⏹ إرسال الأمر") { _, _ -> stopAndProcessVoiceAudio(audioFile, apiKey) }
            .setNegativeButton("إلغاء") { _, _ -> cancelVoiceRecording() }
            .show()

        Handler(Looper.getMainLooper()).postDelayed({
            if (recordingDialog?.isShowing == true) {
                recordingDialog?.dismiss()
                stopAndProcessVoiceAudio(audioFile, apiKey)
            }
        }, 7000)
    }

    private fun cancelVoiceRecording() {
        try { mediaRecorder?.stop(); mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
    }

    private fun stopAndProcessVoiceAudio(audioFile: File, apiKey: String) {
        cancelVoiceRecording()
        if (!audioFile.exists() || audioFile.length() < 500) return
        Toast.makeText(this, "⏳ جاري فهم الأمر الصوتي...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val base64Audio = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
                val booksCatalog = StringBuilder()
                for (b in allBooks) {
                    val meta = metadataJson.optJSONObject(b.name)
                    val clean = meta?.optString("cleanTitle", b.nameWithoutExtension) ?: b.nameWithoutExtension
                    val aliases = meta?.optJSONArray("aliases")?.toString() ?: "[]"
                    val cat = meta?.optString("category", "") ?: ""
                    booksCatalog.append("- File: \"${b.name}\" | Title: \"$clean\" | Aliases: $aliases | Category: \"$cat\"\n")
                }

                val currentContext = if (isInReaderMode && currentBookFile != null) "المستخدم داخل كتاب: \"${currentBookFile!!.name}\"."
                else "المستخدم في شاشة المكتبة الرئيسية."

                val systemPrompt = "أنت مساعد صوتي ذكي لتطبيق قراءة كتب لكبار السن. استمع للملف الصوتي وحدد نية المستخدم.\n" +
                    currentContext + "\nقائمة الكتب المتوفرة:\n" + booksCatalog.toString() + "\n" +
                    "أجب بصيغة JSON فقط:\n" +
                    "{\"intent\":\"OPEN_BOOK أو OPEN_AND_LISTEN أو RESUME_LAST أو REWIND_TIME أو NEXT_PAGE أو PREV_PAGE أو SAVE_BOOKMARK أو GOTO_BOOKMARK أو GO_HOME أو UNKNOWN\",\"targetBookFile\":\"اسم الملف أو null\",\"rewindMinutes\":5,\"replyText\":\"عبارة عربية قصيرة ومطمئنة\"}"

                val partsArr = JSONArray().apply {
                    put(JSONObject().put("text", systemPrompt))
                    put(JSONObject().put("inline_data", JSONObject().apply {
                        put("mime_type", "audio/mp4")
                        put("data", base64Audio)
                    }))
                }

                val reqBody = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().put("parts", partsArr)))
                    put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
                }

                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                conn.outputStream.use { it.write(reqBody.toString().toByteArray(Charsets.UTF_8)) }

                val respText = conn.inputStream.bufferedReader().use { it.readText() }
                val rawJson = JSONObject(respText).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")

                val cmd = JSONObject(rawJson)
                runOnUiThread { executeVoiceCommandJson(cmd) }
            } catch (_: Exception) {
                runOnUiThread { Toast.makeText(this, "تعذر معالجة الصوت، تأكد من الإنترنت والمفتاح", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun executeVoiceCommandJson(cmd: JSONObject) {
        val intent = cmd.optString("intent", "UNKNOWN")
        val targetFileName = cmd.optString("targetBookFile", "")
        val rewindMins = cmd.optInt("rewindMinutes", 5)
        val reply = cmd.optString("replyText", "")

        if (reply.isNotBlank() && intent != "REWIND_TIME") speakFeedback(reply)

        when (intent) {
            "OPEN_BOOK", "OPEN_AND_LISTEN" -> {
                val book = allBooks.firstOrNull { it.name.equals(targetFileName, ignoreCase = true) }
                    ?: allBooks.firstOrNull { it.name.contains(targetFileName, ignoreCase = true) }
                if (book != null) showReaderView(book, autoStartAudio = (intent == "OPEN_AND_LISTEN"))
                else speakFeedback("عذراً، لم أجد الكتاب المطلوب في المكتبة")
            }
            "RESUME_LAST" -> {
                val lastName = prefs.getString("LAST_OPENED_BOOK", "")
                val book = allBooks.firstOrNull { it.name == lastName } ?: allBooks.firstOrNull()
                if (book != null) showReaderView(book, autoStartAudio = false)
            }
            "REWIND_TIME" -> {
                if (isInReaderMode) rewindToMinutesAgo(rewindMins)
                else {
                    val lastName = prefs.getString("LAST_OPENED_BOOK", "")
                    val book = allBooks.firstOrNull { it.name == lastName }
                    if (book != null) {
                        showReaderView(book, autoStartAudio = false)
                        Handler(Looper.getMainLooper()).postDelayed({ rewindToMinutesAgo(rewindMins) }, 400)
                    }
                }
            }
            "NEXT_PAGE" -> if (isInReaderMode) handleNextPage()
            "PREV_PAGE" -> if (isInReaderMode) handlePrevPage()
            "SAVE_BOOKMARK" -> if (isInReaderMode) saveBookmarkForCurrentBook()
            "GOTO_BOOKMARK" -> if (isInReaderMode) gotoBookmarkForCurrentBook()
            "GO_HOME" -> if (isInReaderMode) showLibraryView()
        }
    }

    private fun speakFeedback(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        if (isTtsReady) tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "SYS_FEEDBACK")
    }

    private fun recordReadingTimestamp() {
        val file = currentBookFile ?: return
        val key = "HIST_${file.name}"
        val raw = prefs.getString(key, "") ?: ""
        val entries = if (raw.isBlank()) mutableListOf() else raw.split(";").toMutableList()

        val now = System.currentTimeMillis()
        val pageVal = if (currentBookType == "PDF") currentPdfPageIndex else currentTextPage
        val chapVal = if (currentBookType == "EPUB") currentChapterIndex else 0

        if (entries.isNotEmpty()) {
            val lastParts = entries.last().split("|")
            if (lastParts.size == 3) {
                val lastTime = lastParts[0].toLongOrNull() ?: 0L
                val lastChap = lastParts[1].toIntOrNull() ?: 0
                val lastPage = lastParts[2].toIntOrNull() ?: 0
                if (lastChap == chapVal && lastPage == pageVal && (now - lastTime) < 10_000L) return
            }
        }

        entries.add("$now|$chapVal|$pageVal")
        if (entries.size > 250) entries.removeAt(0)
        prefs.edit().putString(key, entries.joinToString(";")).apply()
    }

    private fun findHistorySnapshot(minutesAgo: Int): Triple<Long, Int, Int>? {
        val file = currentBookFile ?: return null
        val raw = prefs.getString("HIST_${file.name}", "") ?: ""
        if (raw.isBlank()) return null

        val targetTime = System.currentTimeMillis() - (minutesAgo * 60_000L)
        val parsed = raw.split(";").mapNotNull { item ->
            val p = item.split("|")
            if (p.size == 3) Triple(p[0].toLongOrNull() ?: 0L, p[1].toIntOrNull() ?: 0, p[2].toIntOrNull() ?: 0)
            else null
        }
        if (parsed.isEmpty()) return null
        return parsed.lastOrNull { it.first <= targetTime } ?: parsed.first()
    }

    private fun showRewindOptionsDialog() {
        val options = arrayOf("⏪ العودة إلى قبل دقيقتين", "⏪ العودة إلى قبل 5 دقائق", "⏪ العودة إلى قبل 10 دقائق")
        AlertDialog.Builder(this)
            .setTitle("أين كنت؟ (استرجاع الصفحة السابقة)")
            .setItems(options) { _, which -> rewindToMinutesAgo(when (which) { 0 -> 2; 1 -> 5; else -> 10 }) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun rewindToMinutesAgo(minutes: Int) {
        val snapshot = findHistorySnapshot(minutes)
        if (snapshot == null) {
            speakFeedback("لا يوجد سجل قراءة سابق لهذا الكتاب بعد")
            return
        }

        val (_, savedChap, savedPage) = snapshot
        if (currentBookType == "PDF") {
            renderPdfPage(savedPage.coerceIn(0, (pdfRenderer?.pageCount ?: 1) - 1))
        } else {
            currentChapterIndex = savedChap.coerceIn(0, (epubChapters.size - 1).coerceAtLeast(0))
            currentTextPage = savedPage
            if (currentBookType == "EPUB" && epubChapters.isNotEmpty()) currentHtmlContent = epubChapters[currentChapterIndex]
            refreshWebViewContent()
        }

        val displayPageNum = savedPage + 1
        val guideMsg = "تم إرجاعك إلى مكانك قبل $minutes دقائق (صفحة $displayPageNum). كنت تقرأ هنا قبل قليل، يمكنك التقدم قليلاً للوصول إلى المكان الذي انتهيت عنده."
        tvRewindBanner.text = guideMsg
        tvRewindBanner.visibility = View.VISIBLE
        speakFeedback(guideMsg)
        Handler(Looper.getMainLooper()).postDelayed({ tvRewindBanner.visibility = View.GONE }, 12_000)
    }

    private fun registerWifiAutoSync() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            connectivityManager?.registerNetworkCallback(request, wifiCallback)
        } catch (_: Exception) {}
    }

    private fun triggerSilentBackgroundSync() {
        val now = System.currentTimeMillis()
        if (now - lastAutoSyncTime < 60_000L) return
        val account = GoogleSignIn.getLastSignedInAccount(this) ?: return
        val email = account.email ?: return
        lastAutoSyncTime = now
        syncFilesFromDriveAccount(email, silent = true)
    }

    private fun startGoogleDriveSync() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope("https://www.googleapis.com/auth/drive.file"))
            .build()
        val client = GoogleSignIn.getClient(this, gso)
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null) syncFilesFromDriveAccount(account.email ?: "", silent = false)
        else { @Suppress("DEPRECATION") startActivityForResult(client.signInIntent, 909) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 909) {
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(data).result
                if (account?.email != null) syncFilesFromDriveAccount(account.email!!, silent = false)
            } catch (_: Exception) {
                Toast.makeText(this, "تعذر تسجيل الدخول لحساب Google", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun syncFilesFromDriveAccount(email: String, silent: Boolean = false) {
        if (!silent) Toast.makeText(this, "☁️ جاري مزامنة الكتب والصوتيات من السحابة...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val token = GoogleAuthUtil.getToken(this, email, "oauth2:https://www.googleapis.com/auth/drive.file")
                val qFolder = Uri.encode("name='MyBooks_Cloud' and mimeType='application/vnd.google-apps.folder' and trashed=false")
                val fJson = JSONObject(httpGetDrive("https://www.googleapis.com/drive/v3/files?q=$qFolder&fields=files(id)", token))
                val folders = fJson.getJSONArray("files")
                if (folders.length() == 0) {
                    if (!silent) runOnUiThread { Toast.makeText(this, "مجلد MyBooks_Cloud غير موجود في الحساب", Toast.LENGTH_LONG).show() }
                    return@Thread
                }
                val folderId = folders.getJSONObject(0).getString("id")

                val qFiles = Uri.encode("'$folderId' in parents and trashed=false")
                val filesJson = JSONObject(httpGetDrive("https://www.googleapis.com/drive/v3/files?q=$qFiles&pageSize=500&fields=files(id,name,size)", token))
                val filesArr = filesJson.getJSONArray("files")

                val booksDir = File(Environment.getExternalStorageDirectory(), "MyBooks")
                if (!booksDir.exists()) booksDir.mkdirs()

                var downloadedCount = 0
                for (i in 0 until filesArr.length()) {
                    val item = filesArr.getJSONObject(i)
                    val fId = item.getString("id")
                    val fName = item.getString("name")
                    val localFile = File(booksDir, fName)

                    if (fName == "app_config.json" || fName == "books_metadata.json" || fName.endsWith(".sync.json") || !localFile.exists()) {
                        downloadDriveFile(fId, localFile, token)
                        if (fName == "app_config.json") {
                            try {
                                val remoteKey = JSONObject(localFile.readText()).optString("geminiApiKey", "")
                                if (remoteKey.isNotBlank()) prefs.edit().putString("GEMINI_API_KEY", remoteKey).apply()
                            } catch (_: Exception) {}
                        } else {
                            downloadedCount++
                        }
                    }
                }

                runOnUiThread {
                    loadBooks()
                    loadLocalMetadataJson()
                    if (!isInReaderMode) refreshGrid(searchBar.text.toString())
                    if (!silent || downloadedCount > 0) {
                        Toast.makeText(this, "☁️ تم تحديث المكتبة ($downloadedCount ملفات جديدة)", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (!silent) runOnUiThread { Toast.makeText(this, "خطأ في المزامنة: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun httpGetDrive(urlStr: String, token: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply { setRequestProperty("Authorization", "Bearer $token") }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun downloadDriveFile(fileId: String, destFile: File, token: String) {
        val conn = (URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer $token")
        }
        conn.inputStream.use { input -> FileOutputStream(destFile).use { output -> input.copyTo(output) } }
    }

    private fun setupLibraryEvents() {
        btnVoiceCommand.setOnClickListener { startDirectVoiceAssistant() }
        btnCloudSync.setOnClickListener { resetAutoLockTimer(); startGoogleDriveSync() }
        btnApiKeySetup.setOnClickListener { resetAutoLockTimer(); promptGeminiApiKey() }

        btnLibraryLock.setOnClickListener {
            resetAutoLockTimer()
            if (isLibraryUnlocked) {
                isLibraryUnlocked = false
                showingAppsMode = false
                applyLibraryLockState()
                Toast.makeText(this, "تم قفل المكتبة", Toast.LENGTH_SHORT).show()
            } else promptForPin(isForReader = false)
        }

        btnToggleAppsView.setOnClickListener {
            resetAutoLockTimer()
            showingAppsMode = !showingAppsMode
            btnToggleAppsView.text = if (showingAppsMode) "عرض الكتب" else "التطبيقات"
            tvHeaderTitle.text = if (showingAppsMode) "التطبيقات" else "المكتبة"
            gridBooksOrApps.numColumns = if (showingAppsMode) 2 else 3
            refreshGrid("")
        }

        btnSystemSettings.setOnClickListener { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        btnWifi.setOnClickListener { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }

        searchBar.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { refreshGrid(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun setupReaderEvents() {
        btnHomeLauncher.setOnClickListener { showLibraryView() }
        btnReaderVoice.setOnClickListener { startDirectVoiceAssistant() }
        btnRewindHistory.setOnClickListener { showRewindOptionsDialog() }
        touchZoneLeft.setOnClickListener { handleNextPage() }
        touchZoneRight.setOnClickListener { handlePrevPage() }

        btnZoomIn.setOnClickListener {
            if (currentBookType == "PDF") {
                if (pdfZoomScale < 2.2f) { pdfZoomScale += 0.25f; renderPdfPage(currentPdfPageIndex) }
            } else if (fontSize < 60) {
                fontSize += 3; prefs.edit().putInt("FONT_SIZE", fontSize).apply(); refreshWebViewContent()
            }
        }

        btnZoomOut.setOnClickListener {
            if (currentBookType == "PDF") {
                if (pdfZoomScale > 0.75f) { pdfZoomScale -= 0.25f; renderPdfPage(currentPdfPageIndex) }
            } else if (fontSize > 16) {
                fontSize -= 3; prefs.edit().putInt("FONT_SIZE", fontSize).apply(); refreshWebViewContent()
            }
        }

        btnSaveBookmark.setOnClickListener { saveBookmarkForCurrentBook() }
        btnGotoBookmark.setOnClickListener { gotoBookmarkForCurrentBook() }

        btnDarkMode.setOnClickListener {
            isDark = !isDark
            if (currentBookType == "PDF") renderPdfPage(currentPdfPageIndex) else refreshWebViewContent()
        }

        btnLineSpacing.setOnClickListener {
            lineSpacing = if (lineSpacing == 1.4f) 2.0f else 1.4f
            refreshWebViewContent()
        }

        btnSpeak.setOnClickListener {
            if (isPlayingSyncedAudio || tts?.isSpeaking == true) stopAllAudio()
            else speakCurrentPageContent()
        }

        btnReaderLock.setOnClickListener {
            resetAutoLockTimer()
            if (isReaderUnlocked) {
                isReaderUnlocked = false
                applyReaderToolbarVisibility()
                Toast.makeText(this, "تم قفل إعدادات القارئ", Toast.LENGTH_SHORT).show()
            } else promptForPin(isForReader = true)
        }
    }

    private fun stopAllAudio() {
        isPlayingSyncedAudio = false
        try { syncedMediaPlayer?.stop(); syncedMediaPlayer?.release() } catch (_: Exception) {}
        syncedMediaPlayer = null
        tts?.stop()
        btnSpeak.text = "🔊 استماع"
    }

    private fun speakCurrentPageContent() {
        val file = currentBookFile ?: return
        val baseName = file.nameWithoutExtension
        val pageNum = (if (currentBookType == "PDF") currentPdfPageIndex else currentTextPage) + 1

        val syncedWav = File(Environment.getExternalStorageDirectory(), "MyBooks/${baseName}_p${pageNum}.wav")
        if (syncedWav.exists()) {
            try {
                stopAllAudio()
                isPlayingSyncedAudio = true
                btnSpeak.text = "⏹ إيقاف"
                syncedMediaPlayer = MediaPlayer().apply {
                    setDataSource(syncedWav.absolutePath)
                    prepare()
                    setOnCompletionListener {
                        if (isPlayingSyncedAudio) {
                            val hasMore = if (currentBookType == "PDF") {
                                pdfRenderer != null && currentPdfPageIndex < pdfRenderer!!.pageCount - 1
                            } else {
                                currentTextPage < totalTextPages - 1 || (currentBookType == "EPUB" && currentChapterIndex < epubChapters.size - 1)
                            }
                            if (hasMore) {
                                handleNextPage()
                                Handler(Looper.getMainLooper()).postDelayed({ speakCurrentPageContent() }, 400)
                            } else stopAllAudio()
                        }
                    }
                    start()
                }
                return
            } catch (_: Exception) { isPlayingSyncedAudio = false }
        }

        val syncJsonFile = File(Environment.getExternalStorageDirectory(), "MyBooks/${baseName}.sync.json")
        if (syncJsonFile.exists()) {
            try {
                val pagesArr = JSONObject(syncJsonFile.readText()).getJSONArray("pages")
                if (pageNum - 1 in 0 until pagesArr.length()) {
                    val pageText = pagesArr.getJSONObject(pageNum - 1).optString("text", "")
                    if (pageText.isNotBlank() && isTtsReady) {
                        btnSpeak.text = "⏹ إيقاف"
                        tts?.speak(pageText.take(3800), TextToSpeech.QUEUE_FLUSH, null, "SYNC_TXT_TTS")
                        return
                    }
                }
            } catch (_: Exception) {}
        }

        if (currentBookType != "PDF") {
            val plainText = Html.fromHtml(currentHtmlContent, Html.FROM_HTML_MODE_LEGACY).toString().take(3800)
            if (plainText.isNotBlank() && isTtsReady) {
                btnSpeak.text = "⏹ إيقاف"
                tts?.speak(plainText, TextToSpeech.QUEUE_FLUSH, null, "BOOK_TTS")
            }
            return
        }

        btnSpeak.text = "⏳ تفريغ..."
        Thread {
            var extracted = ""
            try {
                PDDocument.load(file).use { doc ->
                    extracted = PDFTextStripper().apply { startPage = pageNum; endPage = pageNum }.getText(doc).trim()
                }
            } catch (_: Exception) {}

            runOnUiThread {
                if (extracted.length > 10 && isTtsReady) {
                    btnSpeak.text = "⏹ إيقاف"
                    tts?.speak(extracted.take(3800), TextToSpeech.QUEUE_FLUSH, null, "PDF_LOCAL_TTS")
                } else {
                    btnSpeak.text = "🔊 استماع"
                    Toast.makeText(this, "يرجى تفريغ الكتاب صوتياً من تطبيق الإدارة السحابي أولاً", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun applyLibraryLockState() {
        val allowSearch = prefs.getBoolean("ALLOW_SEARCH", false)
        val allowWifi = prefs.getBoolean("ALLOW_WIFI", false)
        val allowVoice = prefs.getBoolean("ALLOW_VOICE", true)
        gridBooksOrApps.numColumns = 3

        if (isLibraryUnlocked) {
            btnLibraryLock.setImageResource(android.R.drawable.ic_partial_secure)
            libraryControlBar.visibility = View.VISIBLE
            btnVoiceCommand.visibility = View.VISIBLE
            searchBar.visibility = View.VISIBLE
            btnCloudSync.visibility = View.VISIBLE
            btnApiKeySetup.visibility = View.VISIBLE
            btnToggleAppsView.visibility = View.VISIBLE
            btnSystemSettings.visibility = View.VISIBLE
            btnWifi.visibility = View.VISIBLE
            resetAutoLockTimer()
        } else {
            btnLibraryLock.setImageResource(android.R.drawable.ic_lock_lock)
            tvHeaderTitle.text = "المكتبة"
            btnVoiceCommand.visibility = if (allowVoice) View.VISIBLE else View.GONE
            searchBar.visibility = if (allowSearch) View.VISIBLE else View.GONE
            btnWifi.visibility = if (allowWifi) View.VISIBLE else View.GONE
            btnCloudSync.visibility = View.GONE
            btnApiKeySetup.visibility = View.GONE
            btnToggleAppsView.visibility = View.GONE
            btnSystemSettings.visibility = View.GONE
            libraryControlBar.visibility = if (allowSearch || allowWifi) View.VISIBLE else View.GONE
        }
        updateKioskLockState()
        refreshGrid("")
    }

    private fun applyReaderToolbarVisibility() {
        btnHomeLauncher.visibility = View.VISIBLE
        btnZoomIn.visibility = View.VISIBLE
        btnZoomOut.visibility = View.VISIBLE

        if (isReaderUnlocked) {
            btnReaderLock.setImageResource(android.R.drawable.ic_partial_secure)
            btnReaderVoice.visibility = View.VISIBLE
            btnRewindHistory.visibility = View.VISIBLE
            btnSaveBookmark.visibility = View.VISIBLE
            btnGotoBookmark.visibility = View.VISIBLE
            btnDarkMode.visibility = View.VISIBLE
            btnLineSpacing.visibility = View.VISIBLE
            btnSpeak.visibility = View.VISIBLE
            resetAutoLockTimer()
        } else {
            btnReaderLock.setImageResource(android.R.drawable.ic_lock_lock)
            btnReaderVoice.visibility = if (prefs.getBoolean("SHOW_READER_VOICE", true)) View.VISIBLE else View.GONE
            btnRewindHistory.visibility = if (prefs.getBoolean("SHOW_REWIND_BTN", true)) View.VISIBLE else View.GONE
            val showBm = prefs.getBoolean("SHOW_BOOKMARKS", true)
            btnSaveBookmark.visibility = if (showBm) View.VISIBLE else View.GONE
            btnGotoBookmark.visibility = if (showBm) View.VISIBLE else View.GONE
            btnDarkMode.visibility = if (prefs.getBoolean("SHOW_DARK", false)) View.VISIBLE else View.GONE
            btnLineSpacing.visibility = if (prefs.getBoolean("SHOW_SPACING", false)) View.VISIBLE else View.GONE
            btnSpeak.visibility = if (prefs.getBoolean("SHOW_TTS", true)) View.VISIBLE else View.GONE
        }
        updateBookmarkButtonStyle()
    }

    private fun refreshGrid(query: String) {
        if (showingAppsMode && isLibraryUnlocked) {
            val appNames = allApps.map { it.loadLabel(packageManager).toString() }
            gridBooksOrApps.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, appNames)
            gridBooksOrApps.setOnItemClickListener { _, _, pos, _ ->
                packageManager.getLaunchIntentForPackage(allApps[pos].activityInfo.packageName)?.let { startActivity(it) }
            }
            gridBooksOrApps.setOnItemLongClickListener(null)
            return
        }

        val hiddenBooks = prefs.getStringSet("HIDDEN_BOOKS", emptySet()) ?: emptySet()
        val visibleBooks = if (isLibraryUnlocked) allBooks else allBooks.filter { it.name !in hiddenBooks }
        val filteredBooks = if (query.isBlank()) visibleBooks
        else visibleBooks.filter {
            val clean = metadataJson.optJSONObject(it.name)?.optString("cleanTitle", "") ?: ""
            it.nameWithoutExtension.contains(query, ignoreCase = true) || clean.contains(query, ignoreCase = true)
        }

        gridBooksOrApps.adapter = BookCoverAdapter(filteredBooks, hiddenBooks)
        gridBooksOrApps.setOnItemClickListener { _, _, position, _ ->
            if (filteredBooks.isNotEmpty()) showReaderView(filteredBooks[position])
        }
        gridBooksOrApps.setOnItemLongClickListener { _, _, position, _ ->
            if (isLibraryUnlocked && filteredBooks.isNotEmpty()) {
                resetAutoLockTimer()
                val bookName = filteredBooks[position].name
                val currentHidden = hiddenBooks.toMutableSet()
                if (bookName in currentHidden) {
                    currentHidden.remove(bookName)
                    Toast.makeText(this, "تم إظهار الكتاب", Toast.LENGTH_SHORT).show()
                } else {
                    currentHidden.add(bookName)
                    Toast.makeText(this, "تم إخفاء الكتاب في وضع القفل", Toast.LENGTH_SHORT).show()
                }
                prefs.edit().putStringSet("HIDDEN_BOOKS", currentHidden).apply()
                refreshGrid(searchBar.text.toString())
            }
            true
        }
    }

    inner class BookCoverAdapter(private val books: List<File>, private val hiddenBooks: Set<String>) : BaseAdapter() {
        override fun getCount(): Int = books.size
        override fun getItem(position: Int): Any = books[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_book_grid, parent, false)
            val book = books[position]
            val ivCover = view.findViewById<ImageView>(R.id.ivBookCover)
            val tvFormat = view.findViewById<TextView>(R.id.tvBookFormat)
            val tvProgress = view.findViewById<TextView>(R.id.tvBookProgress)
            val tvTitle = view.findViewById<TextView>(R.id.tvBookTitle)

            (ivCover.parent as View).background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(3, Color.BLACK) }
            val badgeBorder = GradientDrawable().apply { setColor(Color.WHITE); setStroke(2, Color.BLACK) }
            tvFormat.background = badgeBorder
            tvFormat.text = book.extension.lowercase()
            tvProgress.background = badgeBorder
            tvProgress.text = "${stateProps.getProperty("pct_${book.name}", "0")}%"

            val cleanTitle = metadataJson.optJSONObject(book.name)?.optString("cleanTitle", "")?.takeIf { it.isNotBlank() }
                ?: book.nameWithoutExtension
            val prefix = if (isLibraryUnlocked && book.name in hiddenBooks) "[مخفي] " else ""
            tvTitle.text = "$prefix$cleanTitle"
            ivCover.setImageBitmap(getBookCoverBitmap(book))
            return view
        }
    }

    private fun updateBookmarkButtonStyle() {
        val file = currentBookFile ?: return
        val isCurrentBookmarked = if (currentBookType == "PDF") {
            val bmPage = stateProps.getProperty("bm_page_${file.name}")?.toIntOrNull()
            bmPage != null && bmPage == currentPdfPageIndex
        } else {
            val bmChap = stateProps.getProperty("bm_chap_${file.name}")?.toIntOrNull() ?: 0
            val bmTpage = stateProps.getProperty("bm_tpage_${file.name}")?.toIntOrNull()
            bmTpage != null && bmChap == currentChapterIndex && bmTpage == currentTextPage
        }

        if (isCurrentBookmarked) {
            btnSaveBookmark.setBackgroundColor(Color.BLACK)
            btnSaveBookmark.setTextColor(Color.WHITE)
            btnSaveBookmark.text = "✓ محفوظة"
        } else {
            btnSaveBookmark.setBackgroundColor(Color.parseColor("#E0E0E0"))
            btnSaveBookmark.setTextColor(Color.BLACK)
            btnSaveBookmark.text = "حفظ علامة"
        }
    }

    private fun openBookFile(file: File) {
        closePdfRenderer()
        currentBookFile = file
        loadStateProps()

        when (file.extension.lowercase()) {
            "pdf" -> {
                currentBookType = "PDF"
                epubWebView.visibility = View.GONE
                pdfImageView.visibility = View.VISIBLE
                pdfZoomScale = 1.0f
                pdfFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pdfRenderer = PdfRenderer(pdfFileDescriptor!!)
                val savedPage = stateProps.getProperty("page_${file.name}", "0").toIntOrNull() ?: 0
                renderPdfPage(savedPage.coerceIn(0, (pdfRenderer!!.pageCount - 1).coerceAtLeast(0)))
            }
            "epub" -> {
                currentBookType = "EPUB"
                pdfImageView.visibility = View.GONE
                epubWebView.visibility = View.VISIBLE
                epubChapters = extractEpubChapters(file)
                val savedChap = stateProps.getProperty("chap_${file.name}", "0").toIntOrNull() ?: 0
                currentChapterIndex = savedChap.coerceIn(0, (epubChapters.size - 1).coerceAtLeast(0))
                currentTextPage = stateProps.getProperty("tpage_${file.name}", "0").toIntOrNull() ?: 0
                currentHtmlContent = epubChapters.getOrNull(currentChapterIndex) ?: "<p>تعذر قراءة الكتاب</p>"
                refreshWebViewContent()
            }
            else -> {
                currentBookType = "TEXT"
                pdfImageView.visibility = View.GONE
                epubWebView.visibility = View.VISIBLE
                currentTextPage = stateProps.getProperty("tpage_${file.name}", "0").toIntOrNull() ?: 0
                currentHtmlContent = "<p>${file.readText().replace("\n", "<br/>")}</p>"
                refreshWebViewContent()
            }
        }
    }

    private fun renderPdfPage(index: Int) {
        val renderer = pdfRenderer ?: return
        if (index < 0 || index >= renderer.pageCount) return
        currentPdfPageIndex = index

        val page = renderer.openPage(index)
        val bitmap = Bitmap.createBitmap((page.width * 2 * pdfZoomScale).toInt(), (page.height * 2 * pdfZoomScale).toInt(), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()

        pdfImageView.setImageBitmap(bitmap)
        tvPageStatus.text = "صفحة ${index + 1} من ${renderer.pageCount}"
        saveCurrentReadingPosition()
        recordReadingTimestamp()
        updateBookmarkButtonStyle()
    }

    private fun refreshWebViewContent() {
        val bg = if (isDark) "#000000" else "#FFFFFF"
        val fg = if (isDark) "#FFFFFF" else "#000000"
        val styledHtml = "<html dir=\"rtl\" lang=\"ar\"><head><meta charset=\"UTF-8\">" +
            "<style>body{background-color:$bg;color:$fg;font-size:${fontSize}px;line-height:$lineSpacing;padding:20px 28px;font-family:sans-serif;word-wrap:break-word;}img{max-width:100%;height:auto;}</style>" +
            "</head><body>$currentHtmlContent</body></html>"

        epubWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                epubWebView.postDelayed({
                    val pageH = epubWebView.height.coerceAtLeast(1)
                    val totalH = (epubWebView.contentHeight * epubWebView.scale).toInt().coerceAtLeast(pageH)
                    totalTextPages = ((totalH + pageH - 1) / pageH).coerceAtLeast(1)
                    if (currentTextPage >= totalTextPages) currentTextPage = totalTextPages - 1
                    epubWebView.scrollTo(0, currentTextPage * pageH)
                    updateWebPageStatus()
                }, 150)
            }
        }
        epubWebView.loadDataWithBaseURL(null, styledHtml, "text/html", "UTF-8", null)
    }

    private fun updateWebPageStatus() {
        val chapInfo = if (currentBookType == "EPUB" && epubChapters.size > 1) " (فصل ${currentChapterIndex + 1}/${epubChapters.size})" else ""
        tvPageStatus.text = "صفحة ${currentTextPage + 1} من $totalTextPages$chapInfo"
        saveCurrentReadingPosition()
        recordReadingTimestamp()
        updateBookmarkButtonStyle()
    }

    private fun handleNextPage() {
        if (currentBookType == "PDF") {
            if (pdfRenderer != null && currentPdfPageIndex < pdfRenderer!!.pageCount - 1) renderPdfPage(currentPdfPageIndex + 1)
        } else {
            val pageH = epubWebView.height
            if (currentTextPage < totalTextPages - 1) {
                currentTextPage++; epubWebView.scrollTo(0, currentTextPage * pageH); updateWebPageStatus()
            } else if (currentBookType == "EPUB" && currentChapterIndex < epubChapters.size - 1) {
                currentChapterIndex++; currentTextPage = 0; currentHtmlContent = epubChapters[currentChapterIndex]; refreshWebViewContent()
            }
        }
    }

    private fun handlePrevPage() {
        if (currentBookType == "PDF") {
            if (currentPdfPageIndex > 0) renderPdfPage(currentPdfPageIndex - 1)
        } else {
            val pageH = epubWebView.height
            if (currentTextPage > 0) {
                currentTextPage--; epubWebView.scrollTo(0, currentTextPage * pageH); updateWebPageStatus()
            } else if (currentBookType == "EPUB" && currentChapterIndex > 0) {
                currentChapterIndex--; currentTextPage = 0; currentHtmlContent = epubChapters[currentChapterIndex]; refreshWebViewContent()
            }
        }
    }

    private fun saveCurrentReadingPosition() {
        val file = currentBookFile ?: return
        val pct = if (currentBookType == "PDF" && pdfRenderer != null && pdfRenderer!!.pageCount > 0) {
            stateProps.setProperty("page_${file.name}", currentPdfPageIndex.toString())
            (((currentPdfPageIndex + 1) * 100) / pdfRenderer!!.pageCount).coerceIn(0, 100)
        } else {
            stateProps.setProperty("chap_${file.name}", currentChapterIndex.toString())
            stateProps.setProperty("tpage_${file.name}", currentTextPage.toString())
            if (currentBookType == "EPUB" && epubChapters.isNotEmpty()) (((currentChapterIndex + 1) * 100) / epubChapters.size).coerceIn(0, 100)
            else (((currentTextPage + 1) * 100) / totalTextPages.coerceAtLeast(1)).coerceIn(0, 100)
        }
        stateProps.setProperty("pct_${file.name}", pct.toString())
        persistStateProps()
    }

    private fun saveBookmarkForCurrentBook() {
        val file = currentBookFile ?: return
        if (currentBookType == "PDF") stateProps.setProperty("bm_page_${file.name}", currentPdfPageIndex.toString())
        else {
            stateProps.setProperty("bm_chap_${file.name}", currentChapterIndex.toString())
            stateProps.setProperty("bm_tpage_${file.name}", currentTextPage.toString())
        }
        persistStateProps()
        updateBookmarkButtonStyle()
        Toast.makeText(this, "تم حفظ علامة هذه الصفحة", Toast.LENGTH_SHORT).show()
    }

    private fun gotoBookmarkForCurrentBook() {
        val file = currentBookFile ?: return
        if (currentBookType == "PDF") {
            val bmPage = stateProps.getProperty("bm_page_${file.name}")?.toIntOrNull()
            if (bmPage != null) { renderPdfPage(bmPage); Toast.makeText(this, "تم الانتقال للعلامة", Toast.LENGTH_SHORT).show() }
            else Toast.makeText(this, "لا توجد علامة محفوظة", Toast.LENGTH_SHORT).show()
        } else {
            val bmTpage = stateProps.getProperty("bm_tpage_${file.name}")?.toIntOrNull()
            if (bmTpage != null) {
                currentChapterIndex = stateProps.getProperty("bm_chap_${file.name}")?.toIntOrNull() ?: 0
                currentTextPage = bmTpage
                if (currentBookType == "EPUB" && epubChapters.isNotEmpty()) currentHtmlContent = epubChapters[currentChapterIndex.coerceIn(0, epubChapters.size - 1)]
                refreshWebViewContent()
                Toast.makeText(this, "تم الانتقال للعلامة", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this, "لا توجد علامة محفوظة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun promptGeminiApiKey() {
        val input = EditText(this).apply { hint = "AIzaSy..."; setText(prefs.getString("GEMINI_API_KEY", "")) }
        AlertDialog.Builder(this)
            .setTitle("إدخال مفتاح Gemini API")
            .setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                prefs.edit().putString("GEMINI_API_KEY", input.text.toString().trim()).apply()
                Toast.makeText(this, "تم حفظ المفتاح", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun promptForPin(isForReader: Boolean) {
        val savedPin = prefs.getString("PIN_CODE", "1234")
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "الرمز السري (1234)"
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            textSize = 22f
        }
        AlertDialog.Builder(this)
            .setTitle(if (isForReader) "فتح إعدادات القارئ" else "فتح قفل المكتبة")
            .setView(input)
            .setPositiveButton("دخول") { _, _ ->
                if (input.text.toString() == savedPin) {
                    if (isForReader) {
                        isReaderUnlocked = true
                        applyReaderToolbarVisibility()
                        showReaderControlsDialog()
                    } else {
                        isLibraryUnlocked = true
                        applyLibraryLockState()
                        showLibraryControlsDialog()
                    }
                } else Toast.makeText(this, "رمز خاطئ", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showLibraryControlsDialog() {
        val features = arrayOf(
            "إظهار المساعد الصوتي 🎤 في المكتبة",
            "إظهار بحث الكتب أثناء القفل",
            "إظهار الواي فاي أثناء القفل",
            "تفعيل تثبيت الشاشة (Screen Pinning بدون ADB)"
        )
        val checked = booleanArrayOf(
            prefs.getBoolean("ALLOW_VOICE", true),
            prefs.getBoolean("ALLOW_SEARCH", false),
            prefs.getBoolean("ALLOW_WIFI", false),
            prefs.getBoolean("USE_SCREEN_PINNING", false)
        )
        AlertDialog.Builder(this)
            .setTitle("تخصيص المكتبة (اضغط مطولاً على كتاب لإخفائه)")
            .setMultiChoiceItems(features, checked) { _, which, isChecked ->
                resetAutoLockTimer()
                when (which) {
                    0 -> prefs.edit().putBoolean("ALLOW_VOICE", isChecked).apply()
                    1 -> prefs.edit().putBoolean("ALLOW_SEARCH", isChecked).apply()
                    2 -> prefs.edit().putBoolean("ALLOW_WIFI", isChecked).apply()
                    3 -> prefs.edit().putBoolean("USE_SCREEN_PINNING", isChecked).apply()
                }
            }
            .setNeutralButton("🏠 تعيين كواجهة رئيسية") { _, _ -> requestDefaultHomeRole() }
            .setPositiveButton("حفظ") { _, _ -> applyLibraryLockState() }
            .show()
    }

    private fun showReaderControlsDialog() {
        val options = arrayOf(
            "إظهار زر المساعد الصوتي 🎤 داخل القارئ",
            "إظهار زر أين كنت؟ ⏪",
            "إظهار أزرار الإشارة المرجعية",
            "إظهار زر الاستماع الصوتي 🔊",
            "إظهار الوضع الليلي",
            "إظهار تباعد الأسطر",
            "منع انطفاء الشاشة أثناء القراءة"
        )
        val states = booleanArrayOf(
            prefs.getBoolean("SHOW_READER_VOICE", true),
            prefs.getBoolean("SHOW_REWIND_BTN", true),
            prefs.getBoolean("SHOW_BOOKMARKS", true),
            prefs.getBoolean("SHOW_TTS", true),
            prefs.getBoolean("SHOW_DARK", false),
            prefs.getBoolean("SHOW_SPACING", false),
            prefs.getBoolean("KEEP_SCREEN_ON", true)
        )
        AlertDialog.Builder(this)
            .setTitle("تخصيص القارئ")
            .setMultiChoiceItems(options, states) { _, which, isChecked ->
                resetAutoLockTimer()
                when (which) {
                    0 -> prefs.edit().putBoolean("SHOW_READER_VOICE", isChecked).apply()
                    1 -> prefs.edit().putBoolean("SHOW_REWIND_BTN", isChecked).apply()
                    2 -> prefs.edit().putBoolean("SHOW_BOOKMARKS", isChecked).apply()
                    3 -> prefs.edit().putBoolean("SHOW_TTS", isChecked).apply()
                    4 -> prefs.edit().putBoolean("SHOW_DARK", isChecked).apply()
                    5 -> prefs.edit().putBoolean("SHOW_SPACING", isChecked).apply()
                    6 -> prefs.edit().putBoolean("KEEP_SCREEN_ON", isChecked).apply()
                }
            }
            .setNeutralButton("تغيير الرمز") { _, _ -> changePinDialog() }
            .setPositiveButton("تم", null)
            .show()
    }

    private fun changePinDialog() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "الرمز الجديد" }
        AlertDialog.Builder(this).setTitle("تغيير الرمز السري").setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                if (input.text.isNotBlank()) prefs.edit().putString("PIN_CODE", input.text.toString()).apply()
            }.show()
    }

    private fun loadBooks() {
        val booksDir = File(Environment.getExternalStorageDirectory(), "MyBooks")
        if (!booksDir.exists()) booksDir.mkdirs()
        allBooks = booksDir.listFiles { f -> f.extension.lowercase() in listOf("pdf", "epub", "txt") }?.sortedBy { it.name } ?: emptyList()
    }

    private fun loadLocalMetadataJson() {
        val metaFile = File(Environment.getExternalStorageDirectory(), "MyBooks/books_metadata.json")
        metadataJson = if (metaFile.exists()) try { JSONObject(metaFile.readText()) } catch (_: Exception) { JSONObject() } else JSONObject()
    }

    private fun loadInstalledApps() {
        val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        allApps = packageManager.queryIntentActivities(intent, 0).filter { it.activityInfo.packageName != packageName }
    }

    private fun getBookCoverBitmap(file: File): Bitmap {
        coverCache[file.absolutePath]?.let { return it }
        val bmp: Bitmap? = when (file.extension.lowercase()) {
            "pdf" -> extractPdfCover(file)
            "epub" -> extractEpubCover(file)
            else -> null
        }
        val finalBmp = bmp ?: createFallbackCover(file.nameWithoutExtension)
        coverCache[file.absolutePath] = finalBmp
        return finalBmp
    }

    private fun extractPdfCover(file: File): Bitmap? = try {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        if (renderer.pageCount > 0) {
            val page = renderer.openPage(0)
            val bitmap = Bitmap.createBitmap(300, 420, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close(); renderer.close(); pfd.close(); bitmap
        } else { renderer.close(); pfd.close(); null }
    } catch (_: Exception) { null }

    private fun extractEpubCover(file: File): Bitmap? = try {
        ZipFile(file).use { zip ->
            val entries = zip.entries().toList()
            val coverEntry = entries.firstOrNull {
                !it.isDirectory && it.name.contains("cover", true) && (it.name.endsWith(".jpg", true) || it.name.endsWith(".jpeg", true) || it.name.endsWith(".png", true))
            } ?: entries.firstOrNull { !it.isDirectory && (it.name.endsWith(".jpg", true) || it.name.endsWith(".jpeg", true) || it.name.endsWith(".png", true)) }
            if (coverEntry != null) zip.getInputStream(coverEntry).use { BitmapFactory.decodeStream(it) } else null
        }
    } catch (_: Exception) { null }

    private fun createFallbackCover(title: String): Bitmap {
        val bmp = Bitmap.createBitmap(300, 420, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp); canvas.drawColor(Color.parseColor("#F4F4F4"))
        val paint = Paint().apply { color = Color.BLACK; textSize = 28f; textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText(title.take(16), 150f, 200f, paint)
        return bmp
    }

    private fun extractEpubChapters(file: File): List<String> {
        val chapters = mutableListOf<String>()
        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries().toList()
                    .filter { !it.isDirectory && (it.name.endsWith(".html", true) || it.name.endsWith(".xhtml", true) || it.name.endsWith(".htm", true)) }
                    .sortedBy { it.name }
                for (entry in entries) chapters.add(zip.getInputStream(entry).bufferedReader().use { it.readText() })
            }
        } catch (e: Exception) { chapters.add("<p>خطأ في فتح الكتاب: ${e.message}</p>") }
        return chapters
    }

    private fun loadStateProps() {
        val stateFile = File(Environment.getExternalStorageDirectory(), "MyBooks/.reader_state.properties")
        if (stateFile.exists()) try { FileInputStream(stateFile).use { stateProps.load(it) } } catch (_: Exception) {}
    }

    private fun persistStateProps() {
        try {
            val booksDir = File(Environment.getExternalStorageDirectory(), "MyBooks")
            if (!booksDir.exists()) booksDir.mkdirs()
            FileOutputStream(File(booksDir, ".reader_state.properties")).use { stateProps.store(it, "Reader State") }
        } catch (_: Exception) {}
    }

    private fun closePdfRenderer() {
        try { pdfRenderer?.close(); pdfFileDescriptor?.close() } catch (_: Exception) {}
        pdfRenderer = null; pdfFileDescriptor = null
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
    }

    private fun updateBatteryDisplay() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level in 0..100) tvBatteryStatus.text = "البطارية: $level%"
    }

    private fun setupKioskPolicyIfOwner() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, AdminReceiver::class.java)
            if (dpm.isDeviceOwnerApp(packageName)) dpm.setLockTaskPackages(admin, arrayOf(packageName))
        } catch (_: Exception) {}
    }

    private fun updateKioskLockState() {
        hideSystemBars()
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isDeviceOwnerApp(packageName)) {
                if (isLibraryUnlocked) stopLockTask() else startLockTask()
            } else if (prefs.getBoolean("USE_SCREEN_PINNING", false)) {
                if (isLibraryUnlocked) stopLockTask() else startLockTask()
            }
        } catch (_: Exception) {}
    }

    private fun resetAutoLockTimer() {
        autoLockHandler.removeCallbacks(autoLockRunnable)
        if (isLibraryUnlocked || isReaderUnlocked) autoLockHandler.postDelayed(autoLockRunnable, 120_000L)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (isInReaderMode) updateBatteryDisplay()
        if (isLibraryUnlocked || isReaderUnlocked) resetAutoLockTimer()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (isInReaderMode) handleNextPage() else gridBooksOrApps.smoothScrollBy(450, 0)
                true
            }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (isInReaderMode) handlePrevPage() else gridBooksOrApps.smoothScrollBy(-450, 0)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onBackPressed() {
        if (isInReaderMode) showLibraryView()
    }

    override fun onResume() {
        super.onResume()
        if (!isInReaderMode) { loadBooks(); loadStateProps(); loadLocalMetadataJson(); refreshGrid(searchBar.text.toString()) }
        updateKioskLockState()
    }

    override fun onPause() { saveCurrentReadingPosition(); super.onPause() }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("ar")
            isTtsReady = true
        }
    }
    override fun onDestroy() {
        try { connectivityManager?.unregisterNetworkCallback(wifiCallback) } catch (_: Exception) {}
        stopAllAudio(); cancelVoiceRecording(); closePdfRenderer(); tts?.shutdown(); super.onDestroy()
    }
}
