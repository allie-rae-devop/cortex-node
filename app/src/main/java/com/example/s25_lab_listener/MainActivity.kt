package com.example.s25_lab_listener

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * MainActivity: S25 Cortex Node - Synthwave UI
 *
 * Pink/Purple gradient aesthetic with live transcription display.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        // Synthwave color palette
        private const val COLOR_PINK = 0xFFFF006E.toInt()
        private const val COLOR_PURPLE = 0xFF8338EC.toInt()
        private const val COLOR_CYAN = 0xFF00F5FF.toInt()
        private const val COLOR_DARK_BG = 0xFF0A0014.toInt()
        private const val COLOR_OVERLAY = 0xCC000000.toInt()  // 80% black overlay

        // Required permissions for Android 13+
        private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
        
        // Preferences constants
        private const val PREFS_NAME = "S25LabListenerPrefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val DEFAULT_SERVER_URL = "ws://10.0.10.40:5000/ws/transcribe"
    }

    private lateinit var statusTextView: TextView
    private lateinit var transcriptionLogTextView: TextView
    private lateinit var engageLinkButton: Button
    private lateinit var severLinkButton: Button
    private lateinit var bookmarkButton: Button
    private lateinit var serverUrlEditText: EditText
    private lateinit var applyServerButton: Button
    private lateinit var resourceDashboardTextView: TextView

    private var isServiceRunning = false
    private var dashboardUpdateJob: Job? = null
    private var currentBufferSize = 0

    // Modern permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }

        if (allGranted) {
            onPermissionsGranted()
            Toast.makeText(this, "Neural link authorized", Toast.LENGTH_SHORT).show()
        } else {
            onPermissionsDenied()
            Toast.makeText(
                this,
                "Neural link requires system permissions",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Broadcast receiver for live transcription updates
    private val transcriptionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val transcription = intent?.getStringExtra("transcription")
            if (!transcription.isNullOrEmpty()) {
                updateTranscriptionLog(transcription)
            }
        }
    }

    // Broadcast receiver for buffer size updates
    private val bufferUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val bufferSize = intent?.getIntExtra("buffer_size", 0) ?: 0
            currentBufferSize = bufferSize
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hide action bar to prevent double title
        supportActionBar?.hide()

        createSynthwaveUI()
        checkPermissions()

        // Load saved server URL from preferences
        loadServerUrl()

        // Initialize button visual states (service is stopped by default)
        updateUIForServiceState()
    }

    override fun onResume() {
        super.onResume()
        // Register broadcast receiver for transcription updates
        val transcriptionFilter = IntentFilter("com.example.s25_lab_listener.TRANSCRIPTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(transcriptionReceiver, transcriptionFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(transcriptionReceiver, transcriptionFilter)
        }

        // Register broadcast receiver for buffer updates
        val bufferFilter = IntentFilter("com.example.s25_lab_listener.BUFFER_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bufferUpdateReceiver, bufferFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bufferUpdateReceiver, bufferFilter)
        }

        // Start resource dashboard updates
        startDashboardUpdates()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(transcriptionReceiver)
        unregisterReceiver(bufferUpdateReceiver)

        // Stop resource dashboard updates
        dashboardUpdateJob?.cancel()
        dashboardUpdateJob = null
    }

    private fun createSynthwaveUI() {
        // Root container
        val rootLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(COLOR_DARK_BG)
        }

        // === BACKGROUND LAYER ===
        val backgroundImage = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP

            // Load background.jpg from assets
            try {
                val inputStream = assets.open("background.jpg")
                val bitmap = BitmapFactory.decodeStream(inputStream)
                setImageBitmap(bitmap)
                inputStream.close()

                // Add dark overlay
                setColorFilter(COLOR_OVERLAY)
            } catch (e: Exception) {
                // Fallback to solid dark background
                setBackgroundColor(COLOR_DARK_BG)
            }
        }
        rootLayout.addView(backgroundImage)

        // === CONTENT LAYER ===
        val contentLayout = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 48.dp, 24.dp, 24.dp)  // Top padding for S25 Ultra status bar
        }

        // === HEADER ===
        val headerLayout = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 32.dp)
        }

        // Logo icon (clickable for About dialog)
        val logoIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(48.dp, 48.dp).apply {
                marginEnd = 16.dp
            }
            setImageResource(R.drawable.ic_cortex_logo)
            isClickable = true
            isFocusable = true
            setOnClickListener { showAboutDialog() }
        }
        headerLayout.addView(logoIcon)

        // Title text
        val titleText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            text = "S25 CORTEX NODE"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }
        headerLayout.addView(titleText)

        contentLayout.addView(headerLayout)

        // === STATUS INDICATOR ===
        statusTextView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 24.dp
                bottomMargin = 24.dp
            }
            text = "SYSTEM OFFLINE"
            textSize = 28f
            setTextColor(0xFFFF0033.toInt())  // Red
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        contentLayout.addView(statusTextView)

        // === SERVER CONFIGURATION ===
        val serverConfigContainer = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16.dp
                bottomMargin = 8.dp
            }
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }

        val serverConfigLabel = TextView(this).apply {
            text = "SERVER ENDPOINT"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(4.dp, 0, 0, 4.dp)
        }
        serverConfigContainer.addView(serverConfigLabel)

        val serverInputLayout = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
        }

        serverUrlEditText = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                56.dp,
                1f  // Take remaining space
            )
            hint = DEFAULT_SERVER_URL
            setHintTextColor(0xFF555555.toInt())
            setTextColor(COLOR_CYAN)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            background = GradientDrawable().apply {
                setColor(0x33000000)  // Semi-transparent dark background
                setStroke(1.dp, 0xFF444444.toInt())  // Subtle border
                cornerRadius = 8.dp.toFloat()
            }
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            setSingleLine(true)
        }
        serverInputLayout.addView(serverUrlEditText)

        applyServerButton = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                88.dp,
                56.dp
            ).apply {
                marginStart = 12.dp
            }
            text = "APPLY"
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(COLOR_PINK, COLOR_PURPLE)
            ).apply {
                cornerRadius = 8.dp.toFloat()
            }
            setOnClickListener { saveServerUrl() }
        }
        serverInputLayout.addView(applyServerButton)

        serverConfigContainer.addView(serverInputLayout)
        contentLayout.addView(serverConfigContainer)

        // === LIVE TRANSCRIPTION LOG ===
        val transcriptionContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f  // Take remaining space
            ).apply {
                topMargin = 16.dp
                bottomMargin = 16.dp
            }

            // Pink border
            background = GradientDrawable().apply {
                setStroke(3.dp, COLOR_PINK)
                setColor(0x33000000)  // Semi-transparent dark background
                cornerRadius = 12.dp.toFloat()
            }
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
        }

        val transcriptionScroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        transcriptionLogTextView = TextView(this).apply {
            text = ">> Neural link inactive. Awaiting connection...\n"
            textSize = 12f
            setTextColor(COLOR_CYAN)
            typeface = android.graphics.Typeface.MONOSPACE
        }

        transcriptionScroll.addView(transcriptionLogTextView)
        transcriptionContainer.addView(transcriptionScroll)
        contentLayout.addView(transcriptionContainer)

        // === CONTROL DECK ===
        val controlLayout = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16.dp, 0, 0)
        }

        // Engage Link button
        engageLinkButton = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                64.dp
            ).apply {
                bottomMargin = 12.dp
            }
            text = "⚡ ENGAGE LINK"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)

            // Pink to purple gradient
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(COLOR_PINK, COLOR_PURPLE)
            ).apply {
                cornerRadius = 8.dp.toFloat()
            }

            setOnClickListener { startService() }
            isEnabled = false
        }
        controlLayout.addView(engageLinkButton)

        // Sever Link button
        severLinkButton = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                64.dp
            ).apply {
                bottomMargin = 12.dp
            }
            text = "✖ SEVER LINK"
            textSize = 16f
            setTextColor(0xFFCCCCCC.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)

            // Dark purple/grey background
            setBackgroundColor(0xFF2D1B3D.toInt())

            setOnClickListener { stopService() }
            isEnabled = false
        }
        controlLayout.addView(severLinkButton)

        // Bookmark button
        bookmarkButton = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                72.dp
            ).apply {
                bottomMargin = 32.dp
            }
            text = "★ CREATE BOOKMARK"
            textSize = 18f
            setTextColor(COLOR_PINK)
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)

            // Pink outline
            background = GradientDrawable().apply {
                setStroke(4.dp, COLOR_PINK)
                setColor(0x22FF006E)  // Semi-transparent pink
                cornerRadius = 8.dp.toFloat()
            }

            setOnClickListener { sendBookmark() }
            isEnabled = false
        }
        controlLayout.addView(bookmarkButton)

        contentLayout.addView(controlLayout)

        // === RESOURCE DASHBOARD ===
        val dashboardContainer = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16.dp
            }
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0x33000000)  // Semi-transparent dark background
                setStroke(1.dp, 0xFF444444.toInt())
                cornerRadius = 8.dp.toFloat()
            }
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
        }

        val dashboardTitle = TextView(this).apply {
            text = "SYSTEM RESOURCES"
            textSize = 10f
            setTextColor(0xFF888888.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 4.dp)
        }
        dashboardContainer.addView(dashboardTitle)

        resourceDashboardTextView = TextView(this).apply {
            text = "State: Loading\nBuffer: 0 / 480000\nRAM: -- MB"
            textSize = 10f
            setTextColor(COLOR_CYAN)
            typeface = android.graphics.Typeface.MONOSPACE
            lineHeight = (14 * resources.displayMetrics.density).toInt()
        }
        dashboardContainer.addView(resourceDashboardTextView)

        contentLayout.addView(dashboardContainer)

        rootLayout.addView(contentLayout)
        setContentView(rootLayout)

        // Handle bottom navigation bar insets
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            insets
        }
    }

    private fun checkPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            onPermissionsGranted()
        }
    }

    private fun onPermissionsGranted() {
        engageLinkButton.isEnabled = true
        statusTextView.text = "SYSTEM READY"
        statusTextView.setTextColor(0xFF00FF88.toInt())  // Green
    }

    private fun onPermissionsDenied() {
        engageLinkButton.isEnabled = false
        severLinkButton.isEnabled = false
        bookmarkButton.isEnabled = false
        statusTextView.text = "AUTHORIZATION REQUIRED"
        statusTextView.setTextColor(0xFFFF0033.toInt())  // Red
    }

    private fun startService() {
        try {
            val intent = Intent(this, TranscriptionService::class.java).apply {
                action = TranscriptionService.ACTION_START_SERVICE
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            isServiceRunning = true
            updateUIForServiceState()

            Toast.makeText(this, "Neural link established", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Link failure: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopService() {
        try {
            val intent = Intent(this, TranscriptionService::class.java).apply {
                action = TranscriptionService.ACTION_STOP_SERVICE
            }
            startService(intent)

            isServiceRunning = false
            updateUIForServiceState()

            Toast.makeText(this, "Neural link severed", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Disconnect error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendBookmark() {
        try {
            val intent = Intent(this, TranscriptionService::class.java).apply {
                action = TranscriptionService.ACTION_BOOKMARK
            }
            startService(intent)

            Toast.makeText(this, "★ Bookmark created", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Bookmark error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateUIForServiceState() {
        if (isServiceRunning) {
            // Service is RUNNING (ON)
            statusTextView.text = "NEURAL LINK ACTIVE"
            statusTextView.setTextColor(COLOR_CYAN)

            // ENGAGE LINK - Inactive/Pressed state
            engageLinkButton.isEnabled = false
            engageLinkButton.alpha = 0.5f
            engageLinkButton.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(COLOR_PINK, COLOR_PURPLE)
            ).apply {
                cornerRadius = 8.dp.toFloat()
                // No border when inactive
            }

            // SEVER LINK - Active/Danger state
            severLinkButton.isEnabled = true
            severLinkButton.alpha = 1.0f
            severLinkButton.background = GradientDrawable().apply {
                setColor(0xFF2D1B3D.toInt())
                cornerRadius = 8.dp.toFloat()
                setStroke(2.dp, 0xFFFF0033.toInt())  // Red border for danger
            }

            // BOOKMARK - Enabled
            bookmarkButton.isEnabled = true
            bookmarkButton.alpha = 1.0f

            // SERVER CONFIG - Disabled while running (prevent mid-stream changes)
            serverUrlEditText.isEnabled = false
            serverUrlEditText.alpha = 0.5f
            applyServerButton.isEnabled = false
            applyServerButton.alpha = 0.5f

        } else {
            // Service is STOPPED (OFF)
            statusTextView.text = "SYSTEM READY"
            statusTextView.setTextColor(0xFF00FF88.toInt())  // Green

            // ENGAGE LINK - Active/Ready state
            engageLinkButton.isEnabled = true
            engageLinkButton.alpha = 1.0f
            engageLinkButton.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(COLOR_PINK, COLOR_PURPLE)
            ).apply {
                cornerRadius = 8.dp.toFloat()
                setStroke(2.dp, COLOR_CYAN)  // Cyan border when ready
            }

            // SEVER LINK - Inactive state
            severLinkButton.isEnabled = false
            severLinkButton.alpha = 0.5f
            severLinkButton.background = GradientDrawable().apply {
                setColor(0xFF2D1B3D.toInt())
                cornerRadius = 8.dp.toFloat()
                // No border when inactive
            }

            // BOOKMARK - Disabled
            bookmarkButton.isEnabled = false
            bookmarkButton.alpha = 0.5f

            // SERVER CONFIG - Enabled when stopped
            serverUrlEditText.isEnabled = true
            serverUrlEditText.alpha = 1.0f
            applyServerButton.isEnabled = true
            applyServerButton.alpha = 1.0f

            transcriptionLogTextView.text = ">> Neural link inactive. Awaiting connection...\n"
        }
    }

    private fun updateTranscriptionLog(transcription: String) {
        runOnUiThread {
            val currentLog = transcriptionLogTextView.text.toString()
            val newLog = "$currentLog>> $transcription\n"

            // Keep only last 500 lines to prevent memory issues
            val lines = newLog.split("\n")
            if (lines.size > 500) {
                transcriptionLogTextView.text = lines.takeLast(500).joinToString("\n")
            } else {
                transcriptionLogTextView.text = newLog
            }

            // Auto-scroll to bottom
            transcriptionLogTextView.parent.requestChildFocus(transcriptionLogTextView, transcriptionLogTextView)
        }
    }

    private fun loadServerUrl() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        serverUrlEditText.setText(savedUrl)
    }

    private fun saveServerUrl() {
        val url = serverUrlEditText.text.toString().trim()

        if (url.isEmpty()) {
            Toast.makeText(this, "Server URL cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        // Basic validation: check if it starts with ws:// or wss://
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            Toast.makeText(this, "URL must start with ws:// or wss://", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SERVER_URL, url).apply()

        Toast.makeText(this, "Configuration Saved", Toast.LENGTH_SHORT).show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("About S25 Cortex Node")
            .setMessage(
                """
                Technical Architecture:

                • AI Model: OpenAI Whisper (TFLite)
                • Acceleration: Qualcomm QNN GPU Delegate
                • Audio: 16kHz PCM via AudioRecord
                • Network: WebSocket streaming (OkHttp)
                • Processing: Log-Mel Spectrogram → TFLite → Vocabulary Decoder

                Target Device:
                Samsung Galaxy S25 Ultra (Snapdragon 8 Elite)

                Architecture:
                Headless foreground service with 24/7 audio transcription.
                """.trimIndent()
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun startDashboardUpdates() {
        dashboardUpdateJob?.cancel()
        dashboardUpdateJob = lifecycleScope.launch {
            while (isActive) {
                updateDashboardStats()
                delay(1000) // Update every 1 second
            }
        }
    }

    private fun updateDashboardStats() {
        try {
            // Model state based on service running and buffer size
            val processingThreshold = 48000 // 3 seconds at 16kHz
            val modelState = when {
                !isServiceRunning -> "Loading"
                currentBufferSize >= processingThreshold -> "Processing"
                else -> "Ready"
            }
            val modelColor = when (modelState) {
                "Processing" -> 0xFFFFAA00.toInt() // Yellow when processing
                "Ready" -> 0xFF00FF88.toInt() // Green when ready
                else -> 0xFF888888.toInt() // Gray when loading
            }

            // App RAM usage (using Debug.getPss as specified)
            val appMemoryMB = Debug.getPss() / 1024 // Convert KB to MB

            // Buffer size (30 seconds at 16kHz = 480,000 samples)
            val maxBufferSamples = 480000

            runOnUiThread {
                // Build dashboard text with color coding
                val dashboardText = android.text.SpannableStringBuilder().apply {
                    append("State: ")
                    val stateStart = length
                    append(modelState)
                    setSpan(
                        android.text.style.ForegroundColorSpan(modelColor),
                        stateStart,
                        length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    append("\nBuffer: $currentBufferSize / $maxBufferSamples")
                    append("\nRAM: $appMemoryMB MB")
                }
                resourceDashboardTextView.text = dashboardText
            }

        } catch (e: Exception) {
            // Silently fail to avoid crashing the UI
            runOnUiThread {
                resourceDashboardTextView.text = "State: Error\nBuffer: 0 / 480000\nRAM: -- MB"
            }
        }
    }

    // Extension property for dp to px conversion
    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
