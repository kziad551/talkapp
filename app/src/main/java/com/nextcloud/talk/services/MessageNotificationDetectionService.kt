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
        
        // Faster polling for more reliable notifications
        private const val MESSAGE_POLLING_INTERVAL = 5000L // 5 seconds (reduced from 10)
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
        
        // Using START_STICKY to ensure service restarts if killed
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        stopMonitoring()
        serviceScope.cancel()
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
                
                // Clear existing timestamps to force notification of new messages
                lastMessageTimestamp.clear()
                
                // Start polling for messages immediately
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
                if (senderId == user.userId) {
                    continue
                }
                
                // Use a much wider time window (5 minutes) to catch more messages
                // This ensures we don't miss notifications due to time sync issues
                val systemLastCheckTime = System.currentTimeMillis() - (5 * 60 * 1000) // 5 minutes
                val messageRecent = messageTimestamp > systemLastCheckTime
                
                // Check if this is a new message by comparing timestamps
                val lastTimestamp = lastMessageTimestamp[roomToken] ?: 0
                val isNewMessage = messageTimestamp > lastTimestamp
                
                Log.d(TAG, "Room: $roomToken, Message time: $messageTimestamp, Last seen: $lastTimestamp, Recent: $messageRecent, New: $isNewMessage")
                
                if (isNewMessage) {
                    // Always update the timestamp
                    lastMessageTimestamp[roomToken] = messageTimestamp
                    
                    // Broadcast message update to refresh conversation list FIRST
                    // This ensures UI is updated regardless of notification
                    notificationCoordinator.broadcastMessageUpdate(roomToken, messageTimestamp)
                    
                    // Always show notification for first detection if message is from last 30 minutes
                    val isFirstDetection = lastTimestamp == 0L
                    val isRecent30Min = System.currentTimeMillis() - messageTimestamp < 30 * 60 * 1000
                    
                    if (isFirstDetection && isRecent30Min) {
                        Log.d(TAG, "First detection of recent message in $roomToken from $senderName, showing notification")
                        createChatNotification(user, roomToken, messageText, senderName, roomName)
                        continue
                    }
                    
                    // For subsequent detections, check if we should show a notification
                    if (notificationCoordinator.trackMessage(roomToken, messageTimestamp)) {
                        Log.d(TAG, "New message detected in $roomToken from $senderName: $messageText, showing notification")
                        createChatNotification(user, roomToken, messageText, senderName, roomName)
                    } else {
                        Log.d(TAG, "Message update broadcast for $roomToken but notification suppressed")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error polling for new messages", e)
        }
    }
    
    private fun stopMonitoring() {
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
                
                // ALWAYS broadcast message to update conversation list
                // The broadcast will return immediately if a similar one was just processed
                notificationCoordinator.broadcastMessageUpdate(roomToken, messageTimestamp)
                
                // Don't show notifications for messages sent by the current user
                if (senderId.equals(currentUser.userId, ignoreCase = true)) {
                    Log.d(TAG, "Skipping notification for own message from: $senderId")
                    return@launch
                }
                
                // Only show notification if it hasn't been shown already
                // Track message and check if it should be processed
                if (notificationCoordinator.trackMessage(roomToken, messageTimestamp)) {
                    // Create and show the notification
                    Log.d(TAG, "Creating notification for message in $roomToken from $senderName: $messageText")
                    createChatNotification(
                        currentUser,
                        roomToken,
                        messageText,
                        senderName,
                        roomName
                    )
                } else {
                    Log.d(TAG, "Notification already processed by another service, skipping")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing chat message", e)
            }
        }
    }
    
    private fun createChatNotification(user: User, roomToken: String, message: String, sender: String, conversationName: String) {
        // First check if we have notification permission
        if (!checkNotificationPermission()) {
            Log.e(TAG, "Cannot show notification for room $roomToken - missing notification permission")
            return
        }
        
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
        
        // Create a unique channel ID for each conversation to ensure reliable delivery
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val uniqueChannelId = "CHAT_CHANNEL_$roomToken"
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Check if channel exists; if not, create it
            if (notificationManager.getNotificationChannel(uniqueChannelId) == null) {
                val channel = NotificationChannel(
                    uniqueChannelId,
                    "Chat: $conversationName",
                    NotificationManager.IMPORTANCE_HIGH
                )
                channel.description = "Notifications for chat: $conversationName"
                channel.enableLights(true)
                channel.enableVibration(true)
                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "Created notification channel $uniqueChannelId for room $roomToken")
            }
            
            uniqueChannelId
        } else {
            NOTIFICATION_CHANNEL_MESSAGES
        }
        
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(sender)
            .setContentText(message)
            .setSubText(conversationName)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL) // Enable sound, vibration and lights
        
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(notificationId, notificationBuilder.build())
            Log.d(TAG, "Notification shown for message in room: $roomToken from: $sender - ID: $notificationId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show notification for room $roomToken", e)
        }
    }
    
    private fun checkNotificationPermission(): Boolean {
        // Check system notification permission
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val hasSystemPermission = notificationManager.areNotificationsEnabled()
        
        // Check runtime permission for Android 13+
        val hasRuntimePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == 
                PackageManager.PERMISSION_GRANTED
        } else {
            true // No runtime permission needed on older Android versions
        }
        
        // Log the permission status
        Log.d(TAG, "Notification permission check - System: $hasSystemPermission, Runtime: $hasRuntimePermission")
        
        return hasSystemPermission && hasRuntimePermission
    }
    
    private fun calculateCRC32(data: String): Long {
        val crc = CRC32()
        crc.update(data.toByteArray())
        return crc.value
    }
} 