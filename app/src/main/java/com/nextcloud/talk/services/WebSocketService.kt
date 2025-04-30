/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import autodagger.AutoInjector
import com.nextcloud.talk.R
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.activities.MainActivity
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.users.UserManager
import com.nextcloud.talk.utils.NotificationUtils
import com.nextcloud.talk.utils.bundle.BundleKeys
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class WebSocketService : Service() {

    companion object {
        private const val TAG = "WebSocketService"
        private const val NOTIFICATION_ID = 2000
        private const val NOTIFICATION_CHANNEL_ID = "websocket_service_channel"
        private const val WAKE_LOCK_TAG = "WebSocketService:WakeLock"

        // Hash and block keys
        private const val HASH_KEY = "15e3f8f2e89a9f2dd4a5e7cb2ef8bb12f56dbd28e4027b6df9126d46c1bb91f7"
        private const val BLOCK_KEY = "994ce40d771b52136ddf8fd51e86b41c"
        
        // Backend configuration
        private const val BACKEND_URL = "https://nextcloud.wztechno.com"
        private const val BACKEND_SECRET = "changeme123"
        
        // Actions for message listeners
        private const val ACTION_REGISTER_MESSAGE_LISTENER = "com.nextcloud.talk.REGISTER_MESSAGE_LISTENER"
        private const val ACTION_UNREGISTER_MESSAGE_LISTENER = "com.nextcloud.talk.UNREGISTER_MESSAGE_LISTENER"
    }

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var okHttpClient: OkHttpClient

    private var webSocketClient: WebSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isConnecting = false
    private var currentUser: User? = null
    private var reconnectAttempts = 0
    private val messageListeners = mutableSetOf<String>()
    private lateinit var listenerReceiver: BroadcastReceiver

    private val webSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connection opened")
            isConnecting = false
            reconnectAttempts = 0
            
            // Here we would send authentication info
            webSocket.send(buildAuthenticationMessage())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "WebSocket message received: $text")
            // Parse notification messages and dispatch to notification handler
            handleWebSocketMessage(text)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket connection closed. Code: $code, Reason: $reason")
            isConnecting = false
            safeReconnectWithBackoff()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket connection failure", t)
            isConnecting = false
            
            // Check if this is a 404 error - server doesn't support WebSockets
            if (response?.code == 404) {
                Log.w(TAG, "WebSocket endpoint not found (404). Your server might not support WebSockets. Falling back to polling.")
                // Don't try to reconnect WebSocket but make sure polling service is running
                ensurePollingServiceRunning()
                return
            }
            
            // For other errors, try to reconnect
            safeReconnectWithBackoff()
        }
    }

    override fun onCreate() {
        super.onCreate()
        NextcloudTalkApplication.sharedApplication?.componentApplication?.inject(this)
        
        createNotificationChannel()
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply {
            setReferenceCounted(false)
        }
        
        // Set up listener for message listener registration
        listenerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_REGISTER_MESSAGE_LISTENER -> {
                        val serviceId = intent.getStringExtra("serviceId") ?: return
                        registerMessageListener(serviceId)
                    }
                    ACTION_UNREGISTER_MESSAGE_LISTENER -> {
                        val serviceId = intent.getStringExtra("serviceId") ?: return
                        unregisterMessageListener(serviceId)
                    }
                }
            }
        }
        
        val intentFilter = IntentFilter().apply {
            addAction(ACTION_REGISTER_MESSAGE_LISTENER)
            addAction(ACTION_UNREGISTER_MESSAGE_LISTENER)
        }
        
        LocalBroadcastManager.getInstance(this).registerReceiver(listenerReceiver, intentFilter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        
        if (intent?.hasExtra(BundleKeys.KEY_INTERNAL_USER_ID) == true) {
            val userId = intent.getLongExtra(BundleKeys.KEY_INTERNAL_USER_ID, -1)
            if (userId != -1L) {
                currentUser = userManager.getUserWithId(userId)?.blockingGet()
                currentUser?.let {
                    connectWebSocket(it)
                }
            }
        } else {
            // If no specific user provided, use the current active user
            currentUser = userManager.currentUser?.blockingGet()
            currentUser?.let {
                connectWebSocket(it)
            }
        }
        
        // Make sure the notification service is also started
        startMessageNotificationService()
        
        // Using START_STICKY to ensure service restarts if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        webSocketClient?.close(1000, "Service destroyed")
        wakeLock?.release()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(listenerReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        super.onDestroy()
    }

    private fun connectWebSocket(user: User) {
        if (isConnecting) return
        
        isConnecting = true
        acquireWakeLock()
        
        val wsUrl = getWebSocketUrl(user)
        val request = Request.Builder()
            .url(wsUrl)
            .build()
            
        webSocketClient = okHttpClient.newWebSocket(request, webSocketListener)
    }
    
    private fun safeReconnectWithBackoff() {
        try {
            reconnectAttempts++
            val delaySeconds = minOf(30, reconnectAttempts * 5) // Max 30 seconds backoff
            
            Log.d(TAG, "Reconnecting in $delaySeconds seconds (attempt $reconnectAttempts)")
            
            android.os.Handler().postDelayed({
                try {
                    currentUser?.let {
                        connectWebSocket(it)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in delayed reconnect", e)
                    // If reconnect fails, make sure polling service is running
                    ensurePollingServiceRunning()
                }
            }, delaySeconds * 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling reconnect", e)
            // If scheduling reconnect fails, make sure polling service is running
            ensurePollingServiceRunning()
        }
    }
    
    private fun acquireWakeLock() {
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire(10 * 60 * 1000L) // 10 minutes timeout
            }
        }
    }
    
    private fun getWebSocketUrl(user: User): String {
        // Construct WebSocket URL based on user and server info
        val baseUrl = user.baseUrl?.trim('/') ?: ""
        return "$baseUrl/apps/spreed/ws"
    }
    
    private fun buildAuthenticationMessage(): String {
        // Build authentication message for the current user
        return """
            {
                "type": "hello",
                "hello": {
                    "version": "1.0"
                }
            }
        """.trimIndent()
    }
    
    private fun handleWebSocketMessage(message: String) {
        // Process the WebSocket message and create notifications if needed
        try {
            val jsonObject = org.json.JSONObject(message)
            val type = jsonObject.optString("type", "")
            
            // Forward to NCWebSocketNotificationService for notification processing
            val intent = Intent(this, NCWebSocketNotificationService::class.java).apply {
                putExtra("websocket_message", message)
                putExtra(BundleKeys.KEY_INTERNAL_USER_ID, currentUser?.id)
            }
            startService(intent)
            
            // If this is a message, we need to also broadcast directly to refresh conversation list immediately
            if (type == "message" || type == "event") {
                var roomToken = ""
                var timestamp = System.currentTimeMillis()
                
                // Extract room token based on message type
                if (type == "message") {
                    val messageObj = jsonObject.optJSONObject("message")
                    roomToken = messageObj?.optString("roomId", "") ?: ""
                } else if (type == "event") {
                    val eventObj = jsonObject.optJSONObject("event")
                    if (eventObj?.optString("target", "") == "room") {
                        roomToken = eventObj.optString("roomid", "")
                    }
                }
                
                if (roomToken.isNotEmpty()) {
                    // Get NotificationCoordinator
                    val notificationCoordinator = com.nextcloud.talk.utils.NotificationCoordinator.getInstance(this)
                    // Broadcast directly using the coordinator
                    notificationCoordinator.broadcastMessageUpdate(roomToken, timestamp)
                    Log.d(TAG, "WebSocket directly broadcasted message update for room: $roomToken")
                }
            }
            
            // Start the MessageNotificationDetectionService if we don't have any active listeners
            if (messageListeners.isEmpty()) {
                startMessageNotificationService()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling WebSocket message", e)
        }
    }
    
    private fun registerMessageListener(serviceId: String) {
        messageListeners.add(serviceId)
        Log.d(TAG, "Registered message listener: $serviceId, total listeners: ${messageListeners.size}")
    }
    
    private fun unregisterMessageListener(serviceId: String) {
        messageListeners.remove(serviceId)
        Log.d(TAG, "Unregistered message listener: $serviceId, remaining listeners: ${messageListeners.size}")
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.app_name)
            val description = "Keeps WebSocket connection active for notifications"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                this.description = description
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val pendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
        }
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Nextcloud Talk")
            .setContentText("Listening for notifications")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun startMessageNotificationService() {
        try {
            val notificationServiceIntent = Intent(this, MessageNotificationDetectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(notificationServiceIntent)
            } else {
                startService(notificationServiceIntent)
            }
            Log.d(TAG, "Started MessageNotificationDetectionService")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MessageNotificationDetectionService", e)
        }
        
        // Also ensure polling service is running as a more reliable option
        ensurePollingServiceRunning()
    }
    
    private fun ensurePollingServiceRunning() {
        try {
            // Make sure polling service is running as fallback
            val pollingIntent = Intent(this, NotificationPollingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(pollingIntent)
            } else {
                startService(pollingIntent)
            }
            Log.d(TAG, "Ensuring NotificationPollingService is running as fallback")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NotificationPollingService", e)
        }
    }
} 