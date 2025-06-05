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
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.nextcloud.talk.services.MessageNotificationDetectionService
import com.nextcloud.talk.notification.PingForegroundService
import com.nextcloud.talk.users.UserManager
import android.app.AlarmManager
import android.app.PendingIntent

/**
 * Utility class to ensure notification services are running
 */
object MessageNotificationServiceUtil {
    private const val TAG = "MsgNotifServiceUtil"
    
    /**
     * Start all notification services if notification permissions are granted
     */
    fun startMessageNotificationService(context: Context) {
        Log.d(TAG, "📞 startMessageNotificationService() called from context: ${context.javaClass.simpleName}")
        
        try {
            // Check for notification permission on Android 13+
            var hasPermission = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val permissionCheck = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                )
                
                hasPermission = permissionCheck == PackageManager.PERMISSION_GRANTED
                
                if (!hasPermission) {
                    Log.w(TAG, "⚠️ Notification permission not granted, will still start polling but no notifications will appear")
                } else {
                    Log.d(TAG, "✅ Notification permission granted")
                }
            }
            
            // Only start services if there's a logged-in user
            Log.d(TAG, "🚀 Starting notification services, permission state: $hasPermission")
            
            // Log detailed permission information
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                val areNotificationsEnabled = notificationManager.areNotificationsEnabled()
                Log.d(TAG, "📱 System notification settings: areNotificationsEnabled=$areNotificationsEnabled")
            }
            
            // Use the new PingForegroundService instead of the old NotificationPollingService
            val appContext = context.applicationContext
            
            try {
                Log.d(TAG, "🎯 Starting PingForegroundService for comprehensive notifications")
                PingForegroundService.start(appContext)
                Log.d(TAG, "✅ PingForegroundService started successfully, can show notifications: $hasPermission")
                
                // Schedule an alarm to restart the service if it gets killed
                scheduleServiceRestart(context)
                    } catch (e: Exception) {
                Log.e(TAG, "❌ Error starting PingForegroundService: ${e.message}", e)
                Log.e(TAG, "❌ Full stack trace: ${e.stackTrace.joinToString("\n")}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting notification services: ${e.message}", e)
        }
        
        Log.d(TAG, "🏁 startMessageNotificationService() completed")
    }
    
    /**
     * Stop all notification services
     */
    fun stopMessageNotificationService(context: Context) {
        try {
            Log.d(TAG, "Stopping notification services")
            
            // Stop the message detection service
            val msgIntent = Intent(context, MessageNotificationDetectionService::class.java)
            context.stopService(msgIntent)
            
            // Stop the new PingForegroundService
            val pingIntent = Intent(context, PingForegroundService::class.java)
            context.stopService(pingIntent)
            
            Log.d(TAG, "All notification services stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping notification services", e)
        }
    }
    
    /**
     * Schedules periodic restarts of the notification service to ensure it keeps running
     */
    private fun scheduleServiceRestart(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, PingForegroundService::class.java)
            
            val pendingIntent = PendingIntent.getService(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Schedule service restart every 15 minutes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 15 * 60 * 1000, // 15 minutes
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 15 * 60 * 1000, // 15 minutes
                    pendingIntent
                )
            }
            
            Log.d(TAG, "Scheduled PingForegroundService restart alarm")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule service restart", e)
        }
    }
} 