package com.example.s25_lab_listener

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log

/**
 * Headless Foreground Service for 24/7 audio transcription.
 *
 * This service:
 * - Runs as a foreground service with FOREGROUND_SERVICE_MICROPHONE type
 * - Captures audio using AudioRecord in a continuous loop
 * - Feeds audio chunks to AudioEngine (TFLite Whisper model)
 * - Streams transcription results to server via NetworkClient (WebSocket)
 * - Shows persistent notification with BOOKMARK action button
 */
class TranscriptionService : LifecycleService() {

    companion object {
        private const val TAG = "TranscriptionService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "transcription_service_channel"
        private const val CHANNEL_NAME = "Transcription Service"

        // Audio configuration
        private const val SAMPLE_RATE = 16000 // Whisper expects 16kHz
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 2

        // Actions
        const val ACTION_START_SERVICE = "ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val ACTION_BOOKMARK = "ACTION_BOOKMARK"

        // SharedPreferences keys (must match MainActivity)
        private const val PREFS_NAME = "S25LabListenerPrefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val DEFAULT_SERVER_URL = "ws://10.0.10.40:5000/ws/transcribe"
    }

    private var audioRecord: AudioRecord? = null
    private var audioEngine: AudioEngine? = null
    private var networkClient: NetworkClient? = null

    private var recordingJob: Job? = null
    private var isRecording = false

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate()")

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        // Initialize components
        audioEngine = AudioEngine(this)
        networkClient = NetworkClient(lifecycleScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_SERVICE -> {
                Log.d(TAG, "Starting transcription service")
                startForegroundService()
                startRecording()
            }
            ACTION_STOP_SERVICE -> {
                Log.d(TAG, "Stopping transcription service")
                stopRecording()
                stopSelf()
            }
            ACTION_BOOKMARK -> {
                Log.d(TAG, "Bookmark action triggered")
                handleBookmark()
            }
        }

        return START_STICKY // Service will be restarted if killed by system
    }

    private fun startForegroundService() {
        val notification = createNotification()
        
        // Android 14+ requires specifying foreground service type if defined in manifest
        // SDK 35 (Android 15) is our minSdk, so this API is available.
        startForeground(
            NOTIFICATION_ID, 
            notification, 
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification for audio transcription service"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        // Intent to open MainActivity
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        // Intent for BOOKMARK action
        val bookmarkIntent = Intent(this, TranscriptionService::class.java).apply {
            action = ACTION_BOOKMARK
        }
        val bookmarkPendingIntent = PendingIntent.getService(
            this,
            1,
            bookmarkIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        // Intent for STOP action (fixes zombie service bug)
        val stopIntent = Intent(this, TranscriptionService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("S25 Lab Listener")
            .setContentText("Transcription service running")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_input_add,
                "BOOKMARK",
                bookmarkPendingIntent
            )
            .addAction(
                android.R.drawable.ic_delete,
                "STOP",
                stopPendingIntent
            )
            .build()
    }

    private fun startRecording() {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )

            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid buffer size: $minBufferSize")
                return
            }

            val bufferSize = minBufferSize * BUFFER_SIZE_MULTIPLIER

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            ).apply {
                if (state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord not initialized properly")
                    return
                }
            }

            audioRecord?.startRecording()
            isRecording = true

            Log.d(TAG, "AudioRecord started successfully")

            // Start audio processing loop in background
            recordingJob = lifecycleScope.launch(Dispatchers.IO) {
                processAudioLoop(bufferSize)
            }

            // Connect to WebSocket server with URL from preferences
            val serverUrl = getServerUrl()
            Log.d(TAG, "Connecting to server: $serverUrl")
            networkClient?.connect(serverUrl)

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for audio recording", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
        }
    }

    private suspend fun processAudioLoop(bufferSize: Int) {
        val audioBuffer = ShortArray(bufferSize)
        var loopCount = 0

        Log.d(TAG, "Audio processing loop started")

        while (currentCoroutineContext().isActive && isRecording) {
            try {
                val readSize = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0

                if (readSize > 0) {
                    // Process audio chunk through TFLite model
                    val transcription = audioEngine?.processAudioChunk(audioBuffer, readSize)

                    // Send transcription to server via WebSocket
                    transcription?.let { text ->
                        if (text.isNotEmpty()) {
                            networkClient?.sendTranscription(text, isBookmark = false)

                            // Broadcast transcription to MainActivity for live display
                            val transcriptionIntent = Intent("com.example.s25_lab_listener.TRANSCRIPTION")
                            transcriptionIntent.putExtra("transcription", text)
                            sendBroadcast(transcriptionIntent)
                        }
                    }

                    // Broadcast buffer updates periodically (every 10 loop iterations to avoid spam)
                    loopCount++
                    if (loopCount % 10 == 0) {
                        val bufferSize = audioEngine?.getBufferSize() ?: 0
                        val bufferIntent = Intent("com.example.s25_lab_listener.BUFFER_UPDATE")
                        bufferIntent.putExtra("buffer_size", bufferSize)
                        sendBroadcast(bufferIntent)
                    }
                } else {
                    Log.w(TAG, "Read error: $readSize")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio processing loop", e)
            }
        }

        Log.d(TAG, "Audio processing loop ended")
    }

    private fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()

        audioRecord?.apply {
            if (state == AudioRecord.STATE_INITIALIZED) {
                stop()
            }
            release()
        }
        audioRecord = null

        networkClient?.disconnect()

        Log.d(TAG, "Recording stopped")
    }

    private fun handleBookmark() {
        // Send bookmark flag to server
        networkClient?.sendTranscription("", isBookmark = true)

        // Update notification to show bookmark was triggered
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("S25 Lab Listener")
            .setContentText("Bookmark sent!")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)

        // Restore original notification after delay
        lifecycleScope.launch {
            kotlinx.coroutines.delay(2000)
            notificationManager.notify(NOTIFICATION_ID, createNotification())
        }
    }

    private fun getServerUrl(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy()")
        stopRecording()
        audioEngine?.release()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null // Not a bound service
    }
}
