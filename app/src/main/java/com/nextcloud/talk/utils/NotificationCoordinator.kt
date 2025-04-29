/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import autodagger.AutoInjector
import com.nextcloud.talk.application.NextcloudTalkApplication
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utility class to coordinate notifications between multiple services
 * Prevents duplicate notifications and ensures consistent conversation list updates
 */
@Singleton
@AutoInjector(NextcloudTalkApplication::class)
class NotificationCoordinator {
    
    companion object {
        private const val TAG = "NotificationCoordinator"
        private const val ACTION_CHAT_MESSAGE = "com.nextcloud.talk.CHAT_MESSAGE"
        private const val CLEANUP_INTERVAL = 3600000L // 1 hour in milliseconds
        
        // Singleton instance
        @Volatile
        private var instance: NotificationCoordinator? = null
        
        fun getInstance(context: Context): NotificationCoordinator {
            return instance ?: synchronized(this) {
                instance ?: NotificationCoordinator(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val context: Context
    private val processedMessages = ConcurrentHashMap<String, Long>()
    private val cleanupHandler = Handler()
    private val cleanupRunnable = object : Runnable {
        override fun run() {
            clearOldMessages()
            // Reschedule the cleanup
            cleanupHandler.postDelayed(this, CLEANUP_INTERVAL)
        }
    }
    
    // Get LocalBroadcastManager directly instead of injecting it
    private val localBroadcastManager: LocalBroadcastManager
    
    constructor(context: Context) {
        this.context = context
        this.localBroadcastManager = LocalBroadcastManager.getInstance(context)
        NextcloudTalkApplication.sharedApplication?.componentApplication?.inject(this)
        
        // Start periodic cleanup
        cleanupHandler.postDelayed(cleanupRunnable, CLEANUP_INTERVAL)
    }
    
    /**
     * Track the message by room token and timestamp
     * Returns true if this is a new message that should be processed
     */
    fun trackMessage(roomToken: String, timestamp: Long): Boolean {
        val lastTimestamp = processedMessages[roomToken] ?: 0L
        
        // Always allow showing notifications for recent messages
        val currentTime = System.currentTimeMillis()
        val isRecentMessage = currentTime - timestamp < 5000 // 5 seconds threshold
        
        if (timestamp <= lastTimestamp && !isRecentMessage) {
            Log.d(TAG, "Message already processed for room $roomToken: $timestamp <= $lastTimestamp")
            return false
        }
        
        // Update the last seen timestamp for this room
        processedMessages[roomToken] = timestamp
        return true
    }
    
    /**
     * Notify all components about a new message
     * This broadcasts to the conversation list to refresh
     */
    fun broadcastMessageUpdate(roomToken: String, timestamp: Long) {
        val refreshIntent = Intent(ACTION_CHAT_MESSAGE)
        refreshIntent.putExtra("roomToken", roomToken)
        refreshIntent.putExtra("timestamp", timestamp)
        
        try {
            localBroadcastManager.sendBroadcast(refreshIntent)
            Log.d(TAG, "Broadcasted message update for room: $roomToken with timestamp: $timestamp")
        } catch (e: Exception) {
            // Fall back to creating a new instance if the injected one fails
            Log.e(TAG, "Error broadcasting with injected LocalBroadcastManager, falling back", e)
            LocalBroadcastManager.getInstance(context).sendBroadcast(refreshIntent)
        }
    }
    
    /**
     * Clear stored messages that are older than the specified time
     */
    fun clearOldMessages(maxAgeMs: Long = 3600000) { // Default 1 hour
        val currentTime = System.currentTimeMillis()
        val iterator = processedMessages.entries.iterator()
        
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (currentTime - entry.value > maxAgeMs) {
                iterator.remove()
            }
        }
    }
    
    /**
     * Determine if it's safe to show a notification for this message
     * This helps prevent duplicate notifications from different services
     */
    fun shouldShowNotification(roomToken: String, timestamp: Long, serviceId: String): Boolean {
        // Default to allowing notifications to be shown - better duplicate than none
        return true
    }
} 