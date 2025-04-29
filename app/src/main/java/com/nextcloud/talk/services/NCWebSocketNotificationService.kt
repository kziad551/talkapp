/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.services

import android.app.IntentService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.TextUtils
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import autodagger.AutoInjector
import com.nextcloud.talk.R
import com.nextcloud.talk.activities.MainActivity
import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.application.NextcloudTalkApplication.Companion.sharedApplication
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.receivers.DirectReplyReceiver
import com.nextcloud.talk.receivers.MarkAsReadReceiver
import com.nextcloud.talk.users.UserManager
import com.nextcloud.talk.utils.ApiUtils
import com.nextcloud.talk.utils.NotificationUtils
import com.nextcloud.talk.utils.bundle.BundleKeys
import com.nextcloud.talk.utils.preferences.AppPreferences
import io.reactivex.Observer
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import okhttp3.OkHttpClient
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class NCWebSocketNotificationService : IntentService("NCWebSocketNotificationService") {

    companion object {
        private const val TAG = "NCWebSocketNotificationSvc"
        
        // Types of notifications
        private const val TYPE_CHAT = "chat"
        private const val TYPE_CALL = "call"
        
        // Actions
        private const val ACTION_DIRECT_REPLY = "DIRECT_REPLY"
        private const val ACTION_MARK_AS_READ = "MARK_AS_READ"
        
        // Notification channels
        private const val NOTIFICATION_CHANNEL_MESSAGES = "NOTIFICATION_CHANNEL_MESSAGES"
        private const val NOTIFICATION_CHANNEL_CALLS = "NOTIFICATION_CHANNEL_CALLS"
    }

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var ncApi: NcApi

    @Inject
    lateinit var okHttpClient: OkHttpClient
    
    @Inject
    lateinit var appPreferences: AppPreferences

    override fun onCreate() {
        super.onCreate()
        sharedApplication?.componentApplication?.inject(this)
        createNotificationChannels()
    }
    
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Create message notification channel
            val messageChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_MESSAGES,
                "Chat messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for chat messages"
            }
            
            // Create call notification channel
            val callChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_CALLS,
                "Incoming calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming calls"
            }
            
            // Register channels with the system
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(messageChannel)
            notificationManager.createNotificationChannel(callChannel)
        }
    }

    override fun onHandleIntent(intent: Intent?) {
        if (intent == null) return
        
        val userId = intent.getLongExtra(BundleKeys.KEY_INTERNAL_USER_ID, -1)
        if (userId == -1L) {
            Log.e(TAG, "No user ID provided")
            return
        }
        
        val user = userManager.getUserWithId(userId)?.blockingGet()
        if (user == null) {
            Log.e(TAG, "User not found: $userId")
            return
        }
        
        val webSocketMessage = intent.getStringExtra("websocket_message")
        if (webSocketMessage.isNullOrEmpty()) {
            Log.e(TAG, "Empty WebSocket message")
            return
        }
        
        handleWebSocketMessage(user, webSocketMessage)
    }

    private fun handleWebSocketMessage(user: User, message: String) {
        try {
            val jsonMessage = JSONObject(message)
            val messageType = jsonMessage.optString("type", "")
            
            when (messageType) {
                "room" -> handleRoomMessage(user, jsonMessage)
                "message" -> handleChatMessage(user, jsonMessage)
                "event" -> handleEventMessage(user, jsonMessage)
                else -> Log.d(TAG, "Ignoring message of type: $messageType")
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to parse WebSocket message", e)
        }
    }
    
    private fun handleRoomMessage(user: User, jsonMessage: JSONObject) {
        // Handle room join/leave events
        val roomToken = jsonMessage.optString("roomId", "")
        if (roomToken.isEmpty()) return
        
        // Room messages could indicate participants joining or leaving the room
        // For now, just log them, but could trigger participant list updates
        Log.d(TAG, "Room event for room $roomToken")
    }
    
    private fun handleChatMessage(user: User, jsonMessage: JSONObject) {
        try {
            // Extract message data
            val messageData = jsonMessage.optJSONObject("message") ?: return
            val roomToken = messageData.optString("roomId", "")
            val messageText = messageData.optString("message", "")
            val sender = messageData.optString("actorId", "")
            
            if (roomToken.isEmpty() || messageText.isEmpty() || sender.isEmpty()) return
            
            // Check if this is from the current user - don't notify self
            if (isSelfMessage(user, sender)) return
            
            // Create a notification for the chat message
            createChatNotification(user, roomToken, messageText, sender)
            
            // Forward the message to MessageNotificationDetectionService
            forwardMessageToNotificationService(roomToken, messageText, sender, messageData.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error processing chat message", e)
        }
    }
    
    private fun handleEventMessage(user: User, jsonMessage: JSONObject) {
        try {
            val event = jsonMessage.optJSONObject("event") ?: return
            val eventTarget = event.optString("target", "")
            val eventType = event.optString("type", "")
            
            when (eventTarget) {
                "room" -> {
                    if (eventType == "call") {
                        // This is a call notification
                        val roomToken = event.optString("roomid", "")
                        if (roomToken.isNotEmpty()) {
                            createCallNotification(user, roomToken)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing event message", e)
        }
    }
    
    private fun isSelfMessage(user: User, sender: String): Boolean {
        // Check if the sender is the current user to avoid self-notifications
        return sender.endsWith(user.userId.orEmpty())
    }
    
    private fun createChatNotification(user: User, roomToken: String, message: String, sender: String) {
        // Generate a notification ID
        val notificationId = calculateCRC32(System.currentTimeMillis().toString()).toInt()
        
        // Get conversation name and build the notification
        getChatDetails(user, roomToken, sender) { conversationName, senderName ->
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
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(senderName)
                .setContentText(message)
                .setSubText(conversationName)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
            
            // Add reply and mark-as-read actions
            addReplyAction(notificationBuilder, notificationId, roomToken, user.id ?: 0L)
            addMarkAsReadAction(notificationBuilder, notificationId, roomToken, user.id ?: 0L)
            
            sendNotification(notificationId, notificationBuilder.build())
        }
    }
    
    private fun createCallNotification(user: User, roomToken: String) {
        // Generate a notification ID
        val notificationId = calculateCRC32(System.currentTimeMillis().toString()).toInt()
        
        // Get conversation name
        getChatDetails(user, roomToken, "") { conversationName, _ ->
            val pendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
                notificationIntent.putExtra(BundleKeys.KEY_ROOM_TOKEN, roomToken)
                notificationIntent.putExtra(BundleKeys.KEY_INTERNAL_USER_ID, user.id ?: 0L)
                notificationIntent.putExtra(KEY_FROM_NOTIFICATION_START_CALL, true)
                notificationIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                
                PendingIntent.getActivity(
                    this, 
                    System.currentTimeMillis().toInt(), 
                    notificationIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            
            val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_CALLS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Incoming call")
                .setContentText("Incoming call in $conversationName")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setTimeoutAfter(60000) // 1 minute timeout for call notifications
            
            sendNotification(notificationId, notificationBuilder.build())
        }
    }
    
    private fun getChatDetails(user: User?, roomToken: String, sender: String, callback: (String, String) -> Unit) {
        // Default values
        var conversationName = "Chat"
        var senderName = "Someone"
        
        // Try to get details from the server
        try {
            // If user is null, try to get the current user
            val currentUser = user ?: userManager.currentUser?.blockingGet()
            
            if (currentUser != null) {
                // This would normally get conversation details and sender name
                // For this example, we just use defaults
                
                // In a real implementation, you would:
                // 1. Query conversation details from the API
                // 2. Resolve the sender display name
            }
            
            // Return the details via callback
            callback(conversationName, senderName)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting chat details", e)
            callback(conversationName, senderName)
        }
    }
    
    private fun addReplyAction(
        notificationBuilder: NotificationCompat.Builder, 
        notificationId: Int, 
        roomToken: String, 
        userId: Long?
    ) {
        val remoteInput = RemoteInput.Builder("KEY_TEXT_REPLY")
            .setLabel("Reply")
            .build()
            
        val replyIntent = Intent(this, DirectReplyReceiver::class.java)
        replyIntent.putExtra(BundleKeys.KEY_ROOM_TOKEN, roomToken)
        replyIntent.putExtra(BundleKeys.KEY_INTERNAL_USER_ID, userId)
        replyIntent.putExtra(BundleKeys.KEY_SYSTEM_NOTIFICATION_ID, notificationId)
        
        val replyPendingIntent = PendingIntent.getBroadcast(
            this,
            notificationId,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        
        // Create reply action
        notificationBuilder.addAction(
            R.drawable.ic_launcher_foreground,
            "Reply",
            replyPendingIntent
        )
    }
    
    private fun addMarkAsReadAction(
        notificationBuilder: NotificationCompat.Builder, 
        notificationId: Int, 
        roomToken: String, 
        userId: Long?
    ) {
        val markAsReadIntent = Intent(this, MarkAsReadReceiver::class.java)
        markAsReadIntent.putExtra(BundleKeys.KEY_ROOM_TOKEN, roomToken)
        markAsReadIntent.putExtra(BundleKeys.KEY_INTERNAL_USER_ID, userId)
        markAsReadIntent.putExtra(BundleKeys.KEY_SYSTEM_NOTIFICATION_ID, notificationId)
        
        val markAsReadPendingIntent = PendingIntent.getBroadcast(
            this,
            notificationId,
            markAsReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Create mark as read action
        notificationBuilder.addAction(
            R.drawable.ic_launcher_foreground,
            "Mark as read",
            markAsReadPendingIntent
        )
    }
    
    private fun sendNotification(notificationId: Int, notification: android.app.Notification) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }
    
    private fun calculateCRC32(text: String): Long {
        val crc = CRC32()
        crc.update(text.toByteArray())
        return crc.value
    }
    
    private fun forwardMessageToNotificationService(roomToken: String, message: String, senderId: String, messageJson: String) {
        // Get conversation name 
        getChatDetails(null, roomToken, senderId) { conversationName, senderName ->
            // Send a broadcast to MessageNotificationDetectionService
            val intent = Intent("com.nextcloud.talk.CHAT_MESSAGE")
            intent.putExtra("roomToken", roomToken)
            intent.putExtra("roomName", conversationName)
            intent.putExtra("message", messageJson)
            intent.putExtra("senderId", senderId)
            intent.putExtra("senderName", senderName)
            
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
                .sendBroadcast(intent)
            
            Log.d(TAG, "Forwarded message to notification service: $roomToken, $senderName")
        }
    }
    
    private val KEY_FROM_NOTIFICATION_START_CALL = BundleKeys.KEY_FROM_NOTIFICATION_START_CALL
} 