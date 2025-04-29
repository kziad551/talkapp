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
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import autodagger.AutoInjector
import com.nextcloud.talk.R
import com.nextcloud.talk.activities.MainActivity
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.users.UserManager
import com.nextcloud.talk.utils.bundle.BundleKeys
import com.nextcloud.talk.chat.data.ChatMessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import org.json.JSONObject
import java.util.zip.CRC32

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
    }
    
    @Inject
    lateinit var userManager: UserManager
    
    @Inject
    lateinit var chatMessageRepository: ChatMessageRepository
    
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var monitoringJob: Job? = null
    
    override fun onCreate() {
        super.onCreate()
        NextcloudTalkApplication.sharedApplication?.componentApplication?.inject(this)
        createNotificationChannels()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start as foreground service for Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
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
                
                Log.d(TAG, "Started message monitoring for user ${currentUser.id}")
                
                // In a real implementation, this would include listening for new messages
                // and creating notifications for them. For now, we'll keep this simple
                // and not show any test notification that could cause issues
            } catch (e: Exception) {
                Log.e(TAG, "Error while monitoring messages", e)
            }
        }
    }
    
    private fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        Log.d(TAG, "Stopped message monitoring service")
    }
    
    private fun createChatNotification(user: User, roomToken: String, message: String, sender: String) {
        // Generate a notification ID
        val notificationId = calculateCRC32(System.currentTimeMillis().toString()).toInt()
        
        val conversationName = "Chat" // Would be fetched from conversation repository
        val senderName = sender // Would be resolved to proper display name
        
        val pendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            notificationIntent.putExtra(BundleKeys.KEY_ROOM_TOKEN, roomToken)
            notificationIntent.putExtra(BundleKeys.KEY_INTERNAL_USER_ID, user.id)
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
            .setContentTitle(senderName)
            .setContentText(message)
            .setSubText(conversationName)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notificationBuilder.build())
    }
    
    private fun calculateCRC32(data: String): Long {
        val crc = CRC32()
        crc.update(data.toByteArray())
        return crc.value
    }
} 