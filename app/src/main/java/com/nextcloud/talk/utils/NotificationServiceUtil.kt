/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.nextcloud.talk.services.WebSocketService
import com.nextcloud.talk.users.UserManager
import com.nextcloud.talk.utils.bundle.BundleKeys

/**
 * Utility class to ensure WebSocketService is running for notifications
 */
object NotificationServiceUtil {
    private const val TAG = "NotificationServiceUtil"
    
    /**
     * Start the WebSocketService for the current user
     */
    fun startWebSocketService(context: Context, userManager: UserManager) {
        try {
            val currentUser = userManager.currentUser?.blockingGet()
            
            if (currentUser != null) {
                Log.d(TAG, "Starting WebSocketService for user ${currentUser.id}")
                
                val intent = Intent(context, WebSocketService::class.java).apply {
                    putExtra(BundleKeys.KEY_INTERNAL_USER_ID, currentUser.id)
                }
                
                // Use startForegroundService for Android 8.0+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                Log.e(TAG, "Cannot start WebSocketService: No current user")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting WebSocketService", e)
        }
    }
} 