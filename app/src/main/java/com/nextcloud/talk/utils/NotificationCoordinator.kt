/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Coordinator for notifications to prevent duplicates and handle message broadcasts.
 * This is a singleton that should be used by all services that handle notifications.
 */
open class NotificationCoordinator private constructor(private val context: Context) {

    companion object {
        private const val TAG = "NotificationCoordinator"
        private const val CHAT_REFRESH_MESSAGE = "CHAT_MESSAGE_REFRESH"
        private const val KEY_CONVERSATION_TOKEN = "CONVERSATION_TOKEN"
        private const val KEY_TIMESTAMP = "TIMESTAMP"
        
        // This flag determines whether to force notifications even when a message is seen in a room
        // Setting to true to always show notifications (more reliable)
        private const val FORCE_NOTIFICATIONS = true
        
        @Volatile
        private var INSTANCE: NotificationCoordinator? = null
        
        fun getInstance(context: Context): NotificationCoordinator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: try {
                    NotificationCoordinator(context.applicationContext).also { 
                        INSTANCE = it 
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating NotificationCoordinator", e)
                    // Create a simple instance that won't crash
                    NotificationCoordinator(context.applicationContext)
                }
            }
        }
    }
    
    // Store processed message timestamps for each room
    private val processedMessages = ConcurrentHashMap<String, MutableList<Long>>()
    
    // Store information about active conversations (where the user is currently chatting)
    private val activeConversations = ConcurrentHashMap<String, Long>()
    
    // Track app foreground/background state
    private var isAppInForeground = false
    
    /**
     * Set the app's foreground state
     */
    fun setAppForeground(inForeground: Boolean) {
        isAppInForeground = inForeground
        Log.d(TAG, "App foreground state set to: $inForeground")
    }
    
    /**
     * Track when a user enters a conversation room
     */
    fun enterConversation(roomToken: String) {
        activeConversations[roomToken] = System.currentTimeMillis()
        Log.d(TAG, "User entered conversation: $roomToken")
    }
    
    /**
     * Track when a user leaves a conversation room
     */
    fun leaveConversation(roomToken: String) {
        activeConversations.remove(roomToken)
        Log.d(TAG, "User left conversation: $roomToken")
    }
    
    /**
     * Check if the user is currently in a specific conversation
     */
    fun isInConversation(roomToken: String): Boolean {
        val isActive = activeConversations.containsKey(roomToken)
        Log.d(TAG, "Check if in conversation $roomToken: $isActive (Active conversations: ${activeConversations.keys.joinToString()})")
        return isActive
    }
    
    /**
     * Track a message and determine if it should trigger a notification.
     * Returns true if the message should be processed (shown as notification),
     * false if it's a duplicate or should be suppressed.
     */
    fun trackMessage(roomToken: String, timestamp: Long): Boolean {
        // Always allow test notifications from the periodic notification system
        if (roomToken == "PERIODIC_TEST_NOTIFICATION") {
            Log.d(TAG, "Test notification detected, always allowing: $roomToken")
            return true
        }
        
        // Clean up old tracked messages first (older than 30 minutes)
        cleanupOldMessages()
        
        // Get or create the list of processed message timestamps for this room
        val processedTimestamps = processedMessages.getOrPut(roomToken) { mutableListOf() }
        
        // Check if this timestamp is already processed (with small 2 second tolerance for duplicates)
        val isDuplicate = processedTimestamps.any { Math.abs(it - timestamp) < 2000 }
        
        if (isDuplicate) {
            Log.d(TAG, "Message in $roomToken at $timestamp is a duplicate, skipping notification")
            return false
        }
        
        // Add this timestamp to processed list
        processedTimestamps.add(timestamp)
        Log.d(TAG, "Added timestamp $timestamp to room $roomToken (total processed: ${processedTimestamps.size})")
        
        // Always show notifications, regardless of app state or conversation state
        // This is more reliable for making sure notifications are shown
        return true
    }
    
    /**
     * Broadcast a message update to refresh conversation lists and chat UI
     */
    open fun broadcastMessageUpdate(roomToken: String, timestamp: Long) {
        try {
            val intent = Intent(CHAT_REFRESH_MESSAGE).apply {
                putExtra(KEY_CONVERSATION_TOKEN, roomToken)
                putExtra(KEY_TIMESTAMP, timestamp)
            }
            
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
            Log.d(TAG, "Broadcast message update for room: $roomToken at timestamp: $timestamp")
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting message update", e)
        }
    }
    
    /**
     * Clean up old tracked messages to prevent memory leaks
     */
    private fun cleanupOldMessages() {
        val cutoffTime = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(30)
        
        for ((roomToken, timestamps) in processedMessages) {
            // Remove old timestamps
            val iterator = timestamps.iterator()
            while (iterator.hasNext()) {
                val timestamp = iterator.next()
                if (timestamp < cutoffTime) {
                    iterator.remove()
                }
            }
            
            // If no timestamps remain, remove the room entry
            if (timestamps.isEmpty()) {
                processedMessages.remove(roomToken)
            }
        }
    }
} 