package com.example.s25_lab_listener

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * NetworkClient handles WebSocket communication with the server.
 *
 * This class:
 * - Establishes WebSocket connection using OkHttp
 * - Sends JSON packets with transcription data
 * - Handles BOOKMARK flag as a special JSON field
 * - Manages reconnection on connection loss
 *
 * @param scope CoroutineScope for managing async operations
 */
class NetworkClient(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "NetworkClient"
        private const val RECONNECT_DELAY_MS = 5000L
        private const val RECONNECT_DELAY_403_MS = 30000L  // Wait 30s for 403 Forbidden errors
        private const val CONNECTION_TIMEOUT_SECONDS = 30L
        private const val PING_INTERVAL_SECONDS = 30L
    }

    private var webSocket: WebSocket? = null
    private var serverUrl: String? = null
    private var isConnected = false
    private var shouldReconnect = true
    private var reconnectJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // No timeout for reading (long-lived connection)
        .writeTimeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS) // Keep connection alive
        .build()

    private val webSocketListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            isConnected = true
            Log.d(TAG, "WebSocket connected to ${serverUrl}")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Received message: $text")
            // Handle server messages if needed
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code / $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isConnected = false
            Log.e(TAG, "WebSocket closed - Code: $code, Reason: $reason, Server: $serverUrl")
            Log.e(TAG, "Connection state before close: isConnected=false, shouldReconnect=$shouldReconnect")

            if (shouldReconnect) {
                Log.d(TAG, "Attempting to reconnect after close...")
                scheduleReconnect()
            } else {
                Log.w(TAG, "Auto-reconnect disabled, will not reconnect")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isConnected = false
            Log.e(TAG, "WebSocket FAILURE - Server: $serverUrl")
            Log.e(TAG, "Failure type: ${t.javaClass.simpleName}")
            Log.e(TAG, "Failure message: ${t.message}")
            Log.e(TAG, "Response code: ${response?.code ?: "N/A"}")
            Log.e(TAG, "Response message: ${response?.message ?: "N/A"}")
            Log.e(TAG, "Stack trace:", t)

            if (shouldReconnect) {
                // Use longer delay for 403 Forbidden errors
                val delay = if (response?.code == 403) {
                    Log.w(TAG, "403 Forbidden - waiting longer before reconnect (${RECONNECT_DELAY_403_MS}ms)")
                    RECONNECT_DELAY_403_MS
                } else {
                    RECONNECT_DELAY_MS
                }
                Log.d(TAG, "Scheduling reconnect after failure in ${delay}ms...")
                scheduleReconnect(delay)
            } else {
                Log.w(TAG, "Auto-reconnect disabled after failure")
            }
        }
    }

    /**
     * Connect to WebSocket server.
     *
     * @param url WebSocket server URL (e.g., "ws://192.168.1.100:8080")
     */
    fun connect(url: String) {
        serverUrl = url
        shouldReconnect = true

        try {
            val request = Request.Builder()
                .url(url)
                .build()

            webSocket = client.newWebSocket(request, webSocketListener)

            Log.d(TAG, "Connecting to WebSocket: $url")

        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to WebSocket", e)
        }
    }

    /**
     * Send transcription data to server as JSON.
     *
     * JSON format:
     * {
     *   "transcription": "text content",
     *   "timestamp": 1234567890,
     *   "bookmark": false
     * }
     *
     * @param text Transcription text (empty if bookmark-only)
     * @param isBookmark True if this is a bookmark event
     */
    fun sendTranscription(text: String, isBookmark: Boolean) {
        if (!isConnected || webSocket == null) {
            Log.w(TAG, "Cannot send data: WebSocket not connected")
            return
        }

        try {
            val json = JSONObject().apply {
                put("transcription", text)
                put("timestamp", System.currentTimeMillis())
                put("bookmark", isBookmark)
            }

            val success = webSocket?.send(json.toString()) ?: false

            if (success) {
                if (isBookmark) {
                    Log.d(TAG, "Bookmark sent to server")
                } else {
                    Log.d(TAG, "Transcription sent: ${text.take(50)}...")
                }
            } else {
                Log.w(TAG, "Failed to send message")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error sending transcription", e)
        }
    }

    /**
     * Send custom JSON message to server.
     *
     * @param json JSON object to send
     */
    fun sendJson(json: JSONObject) {
        if (!isConnected || webSocket == null) {
            Log.w(TAG, "Cannot send data: WebSocket not connected")
            return
        }

        try {
            val success = webSocket?.send(json.toString()) ?: false

            if (success) {
                Log.d(TAG, "JSON sent to server")
            } else {
                Log.w(TAG, "Failed to send JSON")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error sending JSON", e)
        }
    }

    /**
     * Schedule reconnection attempt after delay using Coroutines.
     *
     * @param delayMs Delay in milliseconds before reconnecting (defaults to RECONNECT_DELAY_MS)
     */
    private fun scheduleReconnect(delayMs: Long = RECONNECT_DELAY_MS) {
        Log.d(TAG, "Scheduling reconnect in ${delayMs}ms")

        // Cancel any existing reconnect job
        reconnectJob?.cancel()

        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(delayMs)

            if (shouldReconnect && !isConnected) {
                serverUrl?.let { url ->
                    Log.d(TAG, "Attempting to reconnect...")
                    connect(url)
                }
            }
        }
    }

    /**
     * Disconnect from WebSocket server.
     *
     * Disables automatic reconnection.
     */
    fun disconnect() {
        shouldReconnect = false
        isConnected = false

        // Cancel any pending reconnect attempts
        reconnectJob?.cancel()
        reconnectJob = null

        webSocket?.close(1000, "Client disconnecting")
        webSocket = null

        Log.d(TAG, "WebSocket disconnected")
    }

    /**
     * Check if currently connected to server.
     */
    fun isConnected(): Boolean {
        return isConnected
    }

    /**
     * Get current server URL.
     */
    fun getServerUrl(): String? {
        return serverUrl
    }

    /**
     * Clean up resources.
     */
    fun shutdown() {
        disconnect()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
