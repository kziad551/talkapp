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
            reconnectWithBackoff()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket connection failure", t)
            isConnecting = false
            reconnectWithBackoff()
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
    
    private fun reconnectWithBackoff() {
        reconnectAttempts++
        val delaySeconds = minOf(30, reconnectAttempts * 5) // Max 30 seconds backoff
        
        Log.d(TAG, "Reconnecting in $delaySeconds seconds (attempt $reconnectAttempts)")
        
        val runnable = Runnable {
            currentUser?.let {
                connectWebSocket(it)
            }
        }
        
        android.os.Handler().postDelayed(runnable, delaySeconds * 1000L)
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
        return "wss://nextcloud.wztechno.com/apps/spreed/ws"
    }
    
    private fun buildAuthenticationMessage(): String {
        // Build authentication message with the hardcoded keys
        return """
            {
                "type": "hello",
                "hello": {
                    "version": "1.0",
                    "auth": {
                        "hashKey": "$HASH_KEY",
                        "blockKey": "$BLOCK_KEY",
                        "backend": "$BACKEND_URL",
                        "secret": "$BACKEND_SECRET"
                    }
                }
            }
        """.trimIndent()
    }
    
    private fun handleWebSocketMessage(message: String) {
        // Process the WebSocket message and create notifications if needed
        // This would dispatch to NCWebSocketNotificationService for handling
        val intent = Intent(this, NCWebSocketNotificationService::class.java).apply {
            putExtra("websocket_message", message)
            putExtra(BundleKeys.KEY_INTERNAL_USER_ID, currentUser?.id)
        }
        startService(intent)
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
} 