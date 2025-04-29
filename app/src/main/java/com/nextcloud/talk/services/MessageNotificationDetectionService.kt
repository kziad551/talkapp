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
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import autodagger.AutoInjector
import com.nextcloud.talk.R
import com.nextcloud.talk.activities.MainActivity
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.users.UserManager
import com.nextcloud.talk.utils.bundle.BundleKeys
import com.nextcloud.talk.chat.data.ChatMessageRepository
import com.nextcloud.talk.repositories.conversations.ConversationsRepository
import com.nextcloud.talk.utils.NotificationUtils
import com.nextcloud.talk.data.database.dao.ConversationsDao
import com.nextcloud.talk.data.database.dao.ChatMessagesDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import org.json.JSONObject
import java.util.zip.CRC32
import android.content.BroadcastReceiver
import java.util.HashMap
import com.nextcloud.talk.utils.NotificationCoordinator

/**
 * Service to detect and trigger notifications for new messages
 */
@AutoInjector(NextcloudTalkApplication::class)
class MessageNotificationDetectionService : Service() {
    
    companion object {
        private const val TAG = "MsgNotifDetectionSvc"
        private const val NOTIFICATION_CHANNEL_MESSAGES = "NOTIFICATION_CHANNEL_MESSAGES"
        private const val NOTIFICATION_CHANNEL_SERVICE = "NOTIFICATION_CHANNEL_SERVICE"
        private const val FOREGROUND_SERVICE_NOTIFICATION_ID = 4242
        private const val ACTION_CHAT_MESSAGE = "com.nextcloud.talk.CHAT_MESSAGE"
        
        // Polling constants
        private const val MESSAGE_POLLING_INTERVAL = 30000L // 30 seconds
    }
    
    @Inject
    lateinit var userManager: UserManager
    
    @Inject
    lateinit var chatMessageRepository: ChatMessageRepository

    @Inject
    lateinit var conversationsRepository: ConversationsRepository
    
    @Inject
    lateinit var conversationsDao: ConversationsDao
    
    @Inject
    lateinit var chatMessagesDao: ChatMessagesDao
    
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var monitoringJob: Job? = null
    private var pollingJob: Job? = null
    private lateinit var messageReceiver: BroadcastReceiver
    
    // Store last message timestamp for each conversation
    private val lastMessageTimestamp = HashMap<String, Long>()
    
    // Notification coordinator
    private lateinit var notificationCoordinator: NotificationCoordinator

    override fun onCreate() {
        super.onCreate()
        NextcloudTalkApplication.sharedApplication?.componentApplication?.inject(this)
        createNotificationChannels()
        
        // Initialize notification coordinator
        notificationCoordinator = NotificationCoordinator.getInstance(applicationContext)

        // Register the broadcast receiver for chat messages
        messageReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_CHAT_MESSAGE) {
                    val messageJson = intent.getStringExtra("message")
                    val roomToken = intent.getStringExtra("roomToken") ?: return
                    val roomName = intent.getStringExtra("roomName") ?: "Chat"
                    val senderId = intent.getStringExtra("senderId") ?: return
                    val senderName = intent.getStringExtra("senderName") ?: "Someone"
                    
                    if (!messageJson.isNullOrEmpty()) {
                        processChatMessage(roomToken, roomName, messageJson, senderId, senderName)
                    }
                }
            }
        }
        
        val intentFilter = IntentFilter(ACTION_CHAT_MESSAGE)
        LocalBroadcastManager.getInstance(this).registerReceiver(messageReceiver, intentFilter)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start as foreground service for Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // Check for notification permission on Android 13+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val permissionCheck = ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    )
                    
                    if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                        Log.e(TAG, "Notification permission not granted, stopping service")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }
                
                startForeground(FOREGROUND_SERVICE_NOTIFICATION_ID, createForegroundNotification())
            } catch (e: Exception) {
                Log.e(TAG, "Error starting foreground service", e)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        
        // If we're already monitoring, don't start another job
        if (monitoringJob?.isActive == true) {
            return START_STICKY
        }
        
        startMonitoring()
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        stopMonitoring()
        serviceScope.cancel()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(messageReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        super.onDestroy()
    }
    
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Chat messages notification channel
            val messageChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_MESSAGES,
                "Chat messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for chat messages"
            }
            notificationManager.createNotificationChannel(messageChannel)
            
            // Service notification channel (lower importance)
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_SERVICE,
                "Message Monitoring Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Required for message notifications to work properly"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(serviceChannel)
        }
    }
    
    private fun createForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_SERVICE)
            .setContentTitle("Nextcloud Talk")
            .setContentText("Monitoring for new messages")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    
    private fun startMonitoring() {
        Log.d(TAG, "Starting message monitoring")
        
        monitoringJob = serviceScope.launch {
            // Delay startup slightly to allow service to be fully initialized
            delay(500)
            
            try {
                val currentUser = userManager.currentUser?.blockingGet()
                if (currentUser == null) {
                    Log.e(TAG, "No current user found, stopping service")
                    stopSelf()
                    return@launch
                }
                
                Log.d(TAG, "Started message monitoring for user ${currentUser.id ?: 0L}")
                
                // Send a broadcast to WebSocketService to register for message notifications
                val registerIntent = Intent("com.nextcloud.talk.REGISTER_MESSAGE_LISTENER")
                registerIntent.putExtra("serviceId", "MessageNotificationDetectionService")
                LocalBroadcastManager.getInstance(this@MessageNotificationDetectionService).sendBroadcast(registerIntent)
                
                Log.d(TAG, "Registered with WebSocketService for chat messages")
                
                // Start polling for messages as a fallback
                startPollingForMessages(currentUser)
            } catch (e: Exception) {
                Log.e(TAG, "Error while monitoring messages", e)
            }
        }
    }
    
    private fun startPollingForMessages(user: User) {
        Log.d(TAG, "Starting message polling for user ${user.id ?: 0L}")
        
        pollingJob = serviceScope.launch {
            while (true) {
                try {
                    pollForNewMessages(user)
                } catch (e: Exception) {
                    Log.e(TAG, "Error during message polling", e)
                }
                delay(MESSAGE_POLLING_INTERVAL)
            }
        }
    }
    
    private suspend fun pollForNewMessages(user: User) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Polling for new messages for user ${user.id ?: 0L}")
            
            // Get conversations for the user
            val conversations = conversationsDao.getConversationsForUser(user.id ?: 0L).firstOrNull() ?: emptyList()
            
            for (conversation in conversations) {
                val roomToken = conversation.token ?: continue
                val roomName = conversation.displayName ?: "Chat"
                
                // Get messages for this conversation
                val latestMessages = chatMessagesDao.getMessagesForConversation(conversation.internalId).firstOrNull() ?: emptyList()
                if (latestMessages.isEmpty()) continue
                
                // Get the latest message (first one since ordered by timestamp DESC)
                val latestMessage = latestMessages.first()
                val messageTimestamp = latestMessage.timestamp ?: 0
                val senderId = latestMessage.actorId ?: ""
                val senderName = latestMessage.actorDisplayName ?: "Someone"
                val messageText = latestMessage.message ?: "New message"
                
                // Skip if this is the user's own message
                if (senderId == user.userId) continue
                
                // Check if this is a new message by comparing timestamps
                val lastTimestamp = lastMessageTimestamp[roomToken] ?: 0
                
                if (messageTimestamp > lastTimestamp) {
                    lastMessageTimestamp[roomToken] = messageTimestamp
                    
                    // Only show notification if this is not the first time we're checking
                    // (to avoid showing notifications for old messages)
                    if (lastTimestamp > 0) {
                        createChatNotification(
                            user,
                            roomToken,
                            messageText,
                            senderName,
                            roomName
                        )
                    } else {
                        Log.d(TAG, "Skipping notification for first-time check of room $roomToken")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error polling for new messages", e)
        }
    }
    
    private fun stopMonitoring() {
        // Unregister from message notifications
        val unregisterIntent = Intent("com.nextcloud.talk.UNREGISTER_MESSAGE_LISTENER")
        unregisterIntent.putExtra("serviceId", "MessageNotificationDetectionService")
        LocalBroadcastManager.getInstance(this).sendBroadcast(unregisterIntent)
        
        monitoringJob?.cancel()
        monitoringJob = null
        
        // Stop polling
        pollingJob?.cancel()
        pollingJob = null
        
        Log.d(TAG, "Stopped message monitoring service")
    }
    
    private fun processChatMessage(roomToken: String, roomName: String, messageJson: String, senderId: String, senderName: String) {
        serviceScope.launch {
            try {
                val currentUser = userManager.currentUser?.blockingGet() ?: return@launch
                
                // Parse the message and extract timestamp
                val messageObj = JSONObject(messageJson)
                val messageText = messageObj.optString("message", "New message")
                val messageTimestamp = messageObj.optLong("timestamp", System.currentTimeMillis())
                
                // Track message but don't block if it's already been processed
                notificationCoordinator.trackMessage(roomToken, messageTimestamp)
                
                // ALWAYS broadcast message to update conversation list
                notificationCoordinator.broadcastMessageUpdate(roomToken, messageTimestamp)
                
                // Don't show notifications for messages sent by the current user
                if (senderId.equals(currentUser.userId, ignoreCase = true)) {
                    Log.d(TAG, "Skipping notification for own message from: $senderId")
                    return@launch
                }
                
                // Create and show the notification
                Log.d(TAG, "Creating notification for message in $roomToken from $senderName: $messageText")
                createChatNotification(
                    currentUser,
                    roomToken,
                    messageText,
                    senderName,
                    roomName
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error processing chat message", e)
            }
        }
    }
    
    private fun createChatNotification(user: User, roomToken: String, message: String, sender: String, conversationName: String) {
        // Generate a notification ID based on room token
        val notificationId = calculateCRC32(roomToken).toInt()
        
        val pendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            notificationIntent.putExtra(BundleKeys.KEY_ROOM_TOKEN, roomToken)
            notificationIntent.putExtra(BundleKeys.KEY_INTERNAL_USER_ID, user.id ?: 0L)
            notificationIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            
            PendingIntent.getActivity(
                this, 
                System.currentTimeMillis().toInt(), 
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        
        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(sender)
            .setContentText(message)
            .setSubText(conversationName)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notificationBuilder.build())
        
        Log.d(TAG, "Notification shown for message in room: $roomToken from: $sender")
    }
    
    private fun calculateCRC32(data: String): Long {
        val crc = CRC32()
        crc.update(data.toByteArray())
        return crc.value
    }
} 