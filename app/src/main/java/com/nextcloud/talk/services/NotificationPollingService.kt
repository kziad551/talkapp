/*
 * Nextcloud Talk application
 *
 * @author Mario Danic
 * Copyright (C) 2017 Mario Danic <mario@lovelyhq.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.nextcloud.talk.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import autodagger.AutoInjector
import com.nextcloud.talk.R
import com.nextcloud.talk.activities.MainActivity
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.models.json.notifications.NotificationsOverall
import com.nextcloud.talk.utils.database.user.CurrentUserProviderNew
import com.nextcloud.talk.utils.preferences.AppPreferences
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class NotificationPollingService : Service() {

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject 
    lateinit var currentUserProvider: CurrentUserProviderNew

    @Inject
    lateinit var okHttpClient: OkHttpClient

    private val disposables = CompositeDisposable()
    private var wakeLock: PowerManager.WakeLock? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    companion object {
        private const val TAG = "NotificationPollingService"
        private const val POLLING_INTERVAL = 10000L // 10 seconds for faster debugging
        private const val NOTIFICATION_CHANNEL_ID = "notification_polling_channel"
        private const val FOREGROUND_NOTIFICATION_ID = 123
        
        fun startService(context: Context) {
            Log.d(TAG, "startService() called")
            val intent = Intent(context, NotificationPollingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.d(TAG, "Starting as foreground service (Android O+)")
                context.startForegroundService(intent)
            } else {
                Log.d(TAG, "Starting as background service (pre-Android O)")
                context.startService(intent)
            }
        }
        
        fun stopService(context: Context) {
            Log.d(TAG, "stopService() called")
            val intent = Intent(context, NotificationPollingService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        Log.d(TAG, "onCreate() called")
        super.onCreate()
        
        try {
            Log.d(TAG, "Injecting dependencies...")
            NextcloudTalkApplication.sharedApplication!!.componentApplication.inject(this)
            Log.d(TAG, "Dependencies injected successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject dependencies", e)
            stopSelf()
            return
        }
        
        Log.d(TAG, "NotificationPollingService created")
        
        // Create notification channel for Android O+
        createNotificationChannel()
        
        // Start as foreground service
        try {
            val notification = createForegroundNotification()
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            Log.d(TAG, "Started as foreground service with notification")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            stopSelf()
            return
        }
        
        // Acquire wake lock to keep the service running
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NextcloudTalk::NotificationPolling")
            wakeLock?.acquire(10*60*1000L /*10 minutes*/)
            Log.d(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
        
        // Start background thread for polling
        try {
            handlerThread = HandlerThread("NotificationPollingThread").apply {
                start()
                backgroundHandler = Handler(looper)
            }
            Log.d(TAG, "Background thread started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start background thread", e)
            stopSelf()
            return
        }
        
        // Start polling
        Log.d(TAG, "Starting polling...")
        startPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand() called with flags=$flags, startId=$startId")
        return START_STICKY // Service should restart if killed
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy() called")
        super.onDestroy()
        
        disposables.clear()
        wakeLock?.release()
        handlerThread?.quitSafely()
        
        stopForeground(true)
        Log.d(TAG, "NotificationPollingService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.nc_notification_channel_messages),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.nc_notification_channel_messages_description)
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
                
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "Notification channel created: $NOTIFICATION_CHANNEL_ID")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create notification channel", e)
            }
        }
    }

    private fun createForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Checking for notifications every ${POLLING_INTERVAL/1000}s")
            .setSmallIcon(R.drawable.ic_logo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startPolling() {
        Log.d(TAG, "startPolling() called")
        backgroundHandler?.post(object : Runnable {
            override fun run() {
                Log.v(TAG, "Polling iteration started")
                checkForNotifications()
                // Schedule next poll
                backgroundHandler?.postDelayed(this, POLLING_INTERVAL)
            }
        })
    }

    private fun checkForNotifications() {
        try {
            Log.v(TAG, "checkForNotifications() called")
            
            val currentUser = currentUserProvider.currentUser.blockingGet()
            if (currentUser == null) {
                Log.w(TAG, "No current user found, skipping notification check")
                return
            }
            
            Log.d(TAG, "Checking for notifications for user: ${currentUser.displayName} (baseUrl: ${currentUser.baseUrl})")
            
            // Use direct HTTP call since the API might not be available through NcApi
            val url = "${currentUser.baseUrl}/ocs/v2.php/apps/notifications/api/v2/notifications?format=json"
            Log.d(TAG, "Fetching notifications from: $url")
            
            val credentials = Credentials.basic(currentUser.username!!, currentUser.token!!)
            Log.d(TAG, "Using credentials for user: ${currentUser.username}")
            
            val request = Request.Builder()
                .url(url)
                .header("Authorization", credentials)
                .header("OCS-APIRequest", "true")
                .build()
            
            val response: Response = okHttpClient.newCall(request).execute()
            Log.d(TAG, "HTTP Response: ${response.code} ${response.message}")
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrEmpty()) {
                    Log.d(TAG, "Successfully fetched notifications, body length: ${body.length}")
                    Log.v(TAG, "Response body: $body")
                    
                    // Check if we have notification permission before showing notifications
                    if (hasNotificationPermission()) {
                        Log.d(TAG, "Notification permission granted, processing notifications")
                        processNotifications(body)
                    } else {
                        Log.w(TAG, "No notification permission, notifications fetched but not shown")
                    }
                } else {
                    Log.d(TAG, "Empty response body - no notifications")
                }
            } else {
                Log.e(TAG, "Failed to fetch notifications: ${response.code} ${response.message}")
                val errorBody = response.body?.string()
                if (!errorBody.isNullOrEmpty()) {
                    Log.e(TAG, "Error response body: $errorBody")
                }
            }
            
            response.close()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for notifications", e)
        }
    }

    private fun processNotifications(responseBody: String) {
        try {
            // For now, just log that we would show a notification
            // You can expand this to parse the JSON and show actual notifications
            Log.d(TAG, "Processing notifications from response")
            
            // Create a test notification to verify the system works
            if (responseBody.contains("\"notifications\"") || responseBody.contains("\"data\"")) {
                Log.d(TAG, "Found notifications in response, showing test notification")
                showTestNotification("Found ${responseBody.length} chars in notification response")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notifications", e)
        }
    }

    private fun showTestNotification(message: String) {
        try {
            if (!hasNotificationPermission()) {
                Log.w(TAG, "Cannot show notification - no permission")
                return
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val intent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Nextcloud Talk Debug")
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_logo)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            notificationManager.notify(999, notification)
            Log.d(TAG, "Test notification shown: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show test notification", e)
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Notification permission check (Android 13+): $result")
            result
        } else {
            val result = NotificationManagerCompat.from(this).areNotificationsEnabled()
            Log.d(TAG, "Notifications enabled check (pre-Android 13): $result")
            result
        }
    }
} 