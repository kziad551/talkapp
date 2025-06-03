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
import com.nextcloud.talk.services.NotificationPollingService
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
                    Log.w(TAG, "Notification permission not granted, will still start polling but no notifications will appear")
                }
            }
            
            // Only start services if there's a logged-in user
            Log.d(TAG, "Starting notification services, permission state: $hasPermission")
            
            // Log detailed permission information
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                val areNotificationsEnabled = notificationManager.areNotificationsEnabled()
                Log.d(TAG, "System notification settings: areNotificationsEnabled=$areNotificationsEnabled")
            }
            
            // Use a simpler approach to start services - start only one at a time
            val appContext = context.applicationContext
            
            // Always start the polling service regardless of permission
            // The service itself will check permission before showing notifications
                    try {
                val intent = Intent(appContext, NotificationPollingService::class.java)
                        
                // Set any extra parameters for the service here
                intent.putExtra("POLL_INTERVAL", 10L) // Set to 10 seconds for faster polling
                
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Log.d(TAG, "Starting service as foreground service (Android O+)")
                            appContext.startForegroundService(intent)
                        } else {
                    Log.d(TAG, "Starting service as background service (pre-Android O)")
                            appContext.startService(intent)
                        }
                Log.d(TAG, "Notification polling service started, can show notifications: $hasPermission")
                
                // Also schedule an alarm to restart the service if it gets killed
                scheduleServiceRestart(context)
                    } catch (e: Exception) {
                Log.e(TAG, "Error starting polling service", e)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting notification services", e)
        }
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
            
            // Also stop the polling service
            val pollingIntent = Intent(context, NotificationPollingService::class.java)
            context.stopService(pollingIntent)
            
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
            val intent = Intent(context, NotificationPollingService::class.java)
            
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
            
            Log.d(TAG, "Scheduled service restart alarm")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule service restart", e)
        }
    }
} 