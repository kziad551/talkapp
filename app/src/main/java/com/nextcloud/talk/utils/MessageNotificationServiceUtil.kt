/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.nextcloud.talk.services.MessageNotificationDetectionService
import com.nextcloud.talk.users.UserManager

/**
 * Utility class to ensure MessageNotificationDetectionService is running
 */
object MessageNotificationServiceUtil {
    private const val TAG = "MsgNotifServiceUtil"
    
    /**
     * Start the MessageNotificationDetectionService only if notification permissions are granted
     */
    fun startMessageNotificationService(context: Context) {
        try {
            // Check for notification permission on Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val permissionCheck = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                )
                
                if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Notification permission not granted, skipping service start")
                    return
                }
            }
            
            // Only start service if there's a logged-in user
            Log.d(TAG, "Starting MessageNotificationDetectionService")
            
            // Delay starting the service to avoid potential race conditions
            context.applicationContext.let { appContext ->
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        val intent = Intent(appContext, MessageNotificationDetectionService::class.java)
                        
                        // Use startForegroundService for Android 8.0+
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            appContext.startForegroundService(intent)
                        } else {
                            appContext.startService(intent)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in delayed start of MessageNotificationDetectionService", e)
                    }
                }, 2000) // Delay by 2 seconds
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting MessageNotificationDetectionService", e)
        }
    }
    
    /**
     * Stop the MessageNotificationDetectionService
     */
    fun stopMessageNotificationService(context: Context) {
        try {
            Log.d(TAG, "Stopping MessageNotificationDetectionService")
            
            val intent = Intent(context, MessageNotificationDetectionService::class.java)
            context.stopService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MessageNotificationDetectionService", e)
        }
    }
} 