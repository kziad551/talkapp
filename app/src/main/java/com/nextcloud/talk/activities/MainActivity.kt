/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2023 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-FileCopyrightText: 2023 Ezhil Shanmugham <ezhil56x.contact@gmail.com>
 * SPDX-FileCopyrightText: 2021 Andy Scherzinger <infoi@andy-scherzinger.de>
 * SPDX-FileCopyrightText: 2017 Mario Danic <mario@lovelyhq.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.activities

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import autodagger.AutoInjector
import com.google.android.material.snackbar.Snackbar
import com.nextcloud.talk.R
import com.nextcloud.talk.account.ServerSelectionActivity
import com.nextcloud.talk.account.WebViewLoginActivity
import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.chat.ChatActivity
import com.nextcloud.talk.conversationlist.ConversationsListActivity
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.databinding.ActivityMainBinding
import com.nextcloud.talk.invitation.InvitationsActivity
import com.nextcloud.talk.lock.LockedActivity
import com.nextcloud.talk.models.json.conversations.RoomOverall
import com.nextcloud.talk.users.UserManager
import com.nextcloud.talk.utils.ApiUtils
import com.nextcloud.talk.utils.ClosedInterfaceImpl
import com.nextcloud.talk.utils.MessageNotificationServiceUtil
import com.nextcloud.talk.utils.SecurityUtils
import com.nextcloud.talk.utils.bundle.BundleKeys
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_ROOM_TOKEN
import com.nextcloud.talk.utils.NotificationCoordinator
import com.nextcloud.talk.services.NotificationPollingService
import io.reactivex.Observer
import io.reactivex.SingleObserver
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class MainActivity : BaseActivity(), ActionBarProvider {
    lateinit var binding: ActivityMainBinding

    @Inject
    lateinit var ncApi: NcApi

    @Inject
    lateinit var userManager: UserManager

    // Notification coordinator instance
    private lateinit var notificationCoordinator: NotificationCoordinator

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_NOTIFICATION_PERMISSION = 1001
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate: Activity: " + System.identityHashCode(this).toString())

        super.onCreate(savedInstanceState)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                lockScreenIfConditionsApply()
                // Set app as in foreground
                notificationCoordinator.setAppForeground(true)
            }
            
            override fun onStop(owner: LifecycleOwner) {
                // Set app as in background
                notificationCoordinator.setAppForeground(false)
            }
        })

        // Set the default theme to replace the launch screen theme.
        setTheme(R.style.AppTheme)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        NextcloudTalkApplication.sharedApplication!!.componentApplication.inject(this)
        
        // Initialize notification coordinator
        notificationCoordinator = NotificationCoordinator.getInstance(applicationContext)

        setSupportActionBar(binding.toolbar)

        handleIntent(intent)

        // Run detailed notification diagnosis
        diagnoseNotificationPermissions()

        // Request notification permissions if needed and start the service
        checkNotificationPermissionsAndStartService()

        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    private fun diagnoseNotificationPermissions() {
        // Log detailed notification permission status
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        
        Log.d(TAG, "===== NOTIFICATION PERMISSION DIAGNOSIS =====")
        Log.d(TAG, "Package name: ${packageName}")
        
        val areNotificationsEnabled = notificationManager.areNotificationsEnabled()
        Log.d(TAG, "System notifications enabled: $areNotificationsEnabled")
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val permissionCheck = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            val hasPermission = permissionCheck == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            Log.d(TAG, "Runtime notification permission granted: $hasPermission")
        }
        
        // Check notification channels
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channels = notificationManager.notificationChannels
            Log.d(TAG, "Total notification channels: ${channels.size}")
            
            for (channel in channels) {
                Log.d(TAG, "Channel: ${channel.id}, Importance: ${channel.importance}, Enabled: ${channel.importance > android.app.NotificationManager.IMPORTANCE_NONE}")
            }
        }
        
        // Check Do Not Disturb status
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val currentInterruptionFilter = notificationManager.currentInterruptionFilter
            val isDoNotDisturbActive = currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            Log.d(TAG, "Do Not Disturb active: $isDoNotDisturbActive (filter: $currentInterruptionFilter)")
        }
        
        // Check battery optimization
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(packageName)
            Log.d(TAG, "Is ignoring battery optimizations: $isIgnoringBatteryOptimizations")
        }
        
        Log.d(TAG, "==============================================")
    }

    private fun checkNotificationPermissionsAndStartService() {
        // Check if notifications are enabled at system level
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        
        if (!notificationManager.areNotificationsEnabled()) {
            // Notifications are disabled at system level
            showNotificationPermissionDialog()
            return
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // Check runtime permission for Android 13+
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // Request notification permission
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION)
            } else {
                // Permission already granted, start all notification services
                startAllNotificationServices()
            }
        } else {
            // For older Android versions, no runtime permission needed
            startAllNotificationServices()
        }
    }
    
    private fun showNotificationPermissionDialog() {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle(R.string.nc_common_notification_permissions)
            .setMessage(getString(R.string.nc_notification_settings_explanation) + "\n\n" + 
                        getString(R.string.nc_notification_permission_required))
            .setPositiveButton(R.string.nc_settings) { _, _ ->
                // Open app notification settings
                openNotificationSettings()
            }
            .setNeutralButton(R.string.nc_notification_request_permission) { _, _ ->
                // Directly request the permission again
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION)
                } else {
                    // Fallback for older Android versions - just open settings
                    openNotificationSettings()
                }
            }
            .setNegativeButton(R.string.nc_common_skip) { _, _ ->
                // User chose to skip, start services anyway but warn them
                Toast.makeText(
                    this,
                    getString(R.string.nc_notification_permission_warning),
                    Toast.LENGTH_LONG
                ).show()
                // Still start the service to poll in background
                MessageNotificationServiceUtil.startMessageNotificationService(this)
            }
            .setCancelable(false)
            .show()
    }
    
    private fun openNotificationSettings() {
        val intent = Intent()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // For Android 8.0+
            intent.action = android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
            intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            // For older versions
            intent.action = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            intent.data = android.net.Uri.parse("package:$packageName")
        }
        startActivity(intent)
        
        // Show a toast to guide the user
        Toast.makeText(
            this,
            getString(R.string.nc_notification_settings_guide),
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // Permission granted, start all notification services
                startAllNotificationServices()
            } else {
                // Permission denied, show a message
                android.widget.Toast.makeText(
                    this,
                    "Notification permission denied. You may not receive message notifications.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Method to launch WebSocket test activity
    fun launchWebSocketTest() {
        val intent = Intent(this, WebSocketTestActivity::class.java)
        startActivity(intent)
    }

    fun lockScreenIfConditionsApply() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguardManager.isKeyguardSecure && appPreferences.isScreenLocked) {
            if (!SecurityUtils.checkIfWeAreAuthenticated(appPreferences.screenLockTimeout)) {
                val lockIntent = Intent(context, LockedActivity::class.java)
                startActivity(lockIntent)
            }
        }
    }

    private fun launchServerSelection() {
        if (isBrandingUrlSet()) {
            val intent = Intent(context, WebViewLoginActivity::class.java)
            val bundle = Bundle()
            bundle.putString(BundleKeys.KEY_BASE_URL, resources.getString(R.string.weblogin_url))
            intent.putExtras(bundle)
            startActivity(intent)
        } else {
            val intent = Intent(context, ServerSelectionActivity::class.java)
            startActivity(intent)
        }
    }

    private fun isBrandingUrlSet() = !TextUtils.isEmpty(resources.getString(R.string.weblogin_url))

    override fun onStart() {
        Log.d(TAG, "onStart: Activity: " + System.identityHashCode(this).toString())
        super.onStart()
        // Set app as in foreground
        notificationCoordinator.setAppForeground(true)
    }

    override fun onResume() {
        Log.d(TAG, "onResume: Activity: " + System.identityHashCode(this).toString())
        super.onResume()

        if (appPreferences.isScreenLocked) {
            SecurityUtils.createKey(appPreferences.screenLockTimeout)
        }
        
        // Check notification permissions again on resume
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!notificationManager.areNotificationsEnabled()) {
            // Only show dialog if it's not the first launch (to avoid showing it twice)
            // We'll use a simple flag in shared preferences to track if we've shown the dialog already in this session
            if (appPreferences.getNotificationWarningLastPostponedDate() ?: 0 < System.currentTimeMillis() - 60000) {
                appPreferences.setNotificationWarningLastPostponedDate(System.currentTimeMillis())
                showNotificationPermissionDialog()
            }
        }
        
        // Add test notification button
        addTestNotificationButton()
    }
    
    private fun addTestNotificationButton() {
        try {
            // Check if we already have a test button
            if (binding.root.findViewById<android.widget.Button>(R.id.test_notification_button) != null) {
                return
            }
            
            // Create a test notification button
            val button = android.widget.Button(this)
            button.id = R.id.test_notification_button
            button.text = "Test Notification"
            button.setOnClickListener {
                sendTestNotification()
            }
            
            // Add button to the layout
            val params = android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT
            )
            params.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
            params.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
            params.setMargins(0, 0, 16, 16) // right, bottom margins in dp
            
            binding.root.addView(button, params)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error adding test notification button", e)
        }
    }

    private fun sendTestNotification() {
        try {
            Log.d(TAG, "Sending test notification...")
            
            // Create channel if needed
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "test_channel",
                    "Test Notifications",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                )
                channel.description = "Channel for notification tests"
                channel.enableLights(true)
                channel.lightColor = android.graphics.Color.RED
                channel.enableVibration(true)
                channel.vibrationPattern = longArrayOf(0, 250, 250, 250)
                channel.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                channel.setBypassDnd(true)
                
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.createNotificationChannel(channel)
            }
            
            // Create a basic notification
            val intent = Intent(this, MainActivity::class.java)
            val pendingIntent = android.app.PendingIntent.getActivity(
                this, 
                0, 
                intent, 
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            // Create full-screen intent for heads-up notification
            val fullScreenIntent = Intent(this, MainActivity::class.java)
            fullScreenIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            val fullScreenPendingIntent = android.app.PendingIntent.getActivity(
                this,
                1,
                fullScreenIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            val builder = androidx.core.app.NotificationCompat.Builder(this, "test_channel")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("IMPORTANT TEST")
                .setContentText("This is a HIGH PRIORITY test notification: " + System.currentTimeMillis())
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
                .setVibrate(longArrayOf(0, 250, 250, 250))
                .setAutoCancel(true)
            
            // Add action button
            val actionIntent = Intent(this, MainActivity::class.java)
            val actionPendingIntent = android.app.PendingIntent.getActivity(
                this, 
                1, 
                actionIntent, 
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_notification, "Open", actionPendingIntent)
            
            // Show the notification
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.notify(9999, builder.build())
            
            Log.d(TAG, "Test notification sent!")
            
            // Also show a toast
            android.widget.Toast.makeText(
                this,
                "Test notification sent!", 
                android.widget.Toast.LENGTH_SHORT
            ).show()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending test notification", e)
            
            // Show error in toast
            android.widget.Toast.makeText(
                this,
                "Error sending notification: " + e.message, 
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onPause() {
        Log.d(TAG, "onPause: Activity: " + System.identityHashCode(this).toString())
        super.onPause()
    }

    override fun onStop() {
        Log.d(TAG, "onStop: Activity: " + System.identityHashCode(this).toString())
        super.onStop()
        // Set app as in background when activity stops
        notificationCoordinator.setAppForeground(false)
    }

    private fun openConversationList() {
        val intent = Intent(this, ConversationsListActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.putExtras(Bundle())
        startActivity(intent)
    }

    private fun handleActionFromContact(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            val cursor = contentResolver.query(intent.data!!, null, null, null, null)

            var userId = ""
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    // userId @ server
                    userId = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1))
                }

                cursor.close()
            }

            when (intent.type) {
                "vnd.android.cursor.item/vnd.com.nextcloud.talk2.chat" -> {
                    val user = userId.substringBeforeLast("@")
                    val baseUrl = userId.substringAfterLast("@")

                    if (currentUserProvider.currentUser.blockingGet()?.baseUrl!!.endsWith(baseUrl) == true) {
                        startConversation(user)
                    } else {
                        Snackbar.make(
                            binding.root,
                            R.string.nc_phone_book_integration_account_not_found,
                            Snackbar.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun startConversation(userId: String) {
        val roomType = "1"

        val currentUser = currentUserProvider.currentUser.blockingGet()

        val apiVersion = ApiUtils.getConversationApiVersion(currentUser, intArrayOf(ApiUtils.API_V4, 1))
        val credentials = ApiUtils.getCredentials(currentUser?.username, currentUser?.token)
        val retrofitBucket = ApiUtils.getRetrofitBucketForCreateRoom(
            version = apiVersion,
            baseUrl = currentUser?.baseUrl!!,
            roomType = roomType,
            invite = userId
        )

        ncApi.createRoom(
            credentials,
            retrofitBucket.url,
            retrofitBucket.queryMap
        )
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : Observer<RoomOverall> {
                override fun onSubscribe(d: Disposable) {
                    // unused atm
                }

                override fun onNext(roomOverall: RoomOverall) {
                    val bundle = Bundle()
                    bundle.putString(KEY_ROOM_TOKEN, roomOverall.ocs!!.data!!.token)

                    val chatIntent = Intent(context, ChatActivity::class.java)
                    chatIntent.putExtras(bundle)
                    startActivity(chatIntent)
                }

                override fun onError(e: Throwable) {
                    // unused atm
                }

                override fun onComplete() {
                    // unused atm
                }
            })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent Activity: " + System.identityHashCode(this).toString())
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        handleActionFromContact(intent)

        val internalUserId = intent.extras?.getLong(BundleKeys.KEY_INTERNAL_USER_ID)

        var user: User? = null
        if (internalUserId != null) {
            user = userManager.getUserWithId(internalUserId).blockingGet()
        }

        if (user != null && userManager.setUserAsActive(user).blockingGet()) {
            if (intent.hasExtra(BundleKeys.KEY_REMOTE_TALK_SHARE)) {
                if (intent.getBooleanExtra(BundleKeys.KEY_REMOTE_TALK_SHARE, false)) {
                    val invitationsIntent = Intent(this, InvitationsActivity::class.java)
                    startActivity(invitationsIntent)
                }
            } else {
                val chatIntent = Intent(context, ChatActivity::class.java)
                chatIntent.putExtras(intent.extras!!)
                startActivity(chatIntent)
            }
        } else {
            if (!appPreferences.isDbRoomMigrated) {
                appPreferences.isDbRoomMigrated = true
            }

            userManager.users.subscribe(object : SingleObserver<List<User>> {
                override fun onSubscribe(d: Disposable) {
                    // unused atm
                }

                override fun onSuccess(users: List<User>) {
                    if (users.isNotEmpty()) {
                        ClosedInterfaceImpl().setUpPushTokenRegistration()
                        runOnUiThread {
                            openConversationList()
                        }
                    } else {
                        runOnUiThread {
                            launchServerSelection()
                        }
                    }
                }

                override fun onError(e: Throwable) {
                    Log.e(TAG, "Error loading existing users", e)
                    Toast.makeText(
                        context,
                        context.resources.getString(R.string.nc_common_error_sorry),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
        }
    }

    private fun startAllNotificationServices() {
        try {
            // Check if we have notification permission first
            var hasPermission = true
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                hasPermission = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == 
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                
                // If no permission, prompt the user again but continue with service start
                if (!hasPermission) {
                    Log.w(TAG, "No notification permission, showing prompt")
                    // Use a handler to not block the UI
                    android.os.Handler().postDelayed({
                        showNotificationPermissionDialog()
                    }, 500)
                }
            }
            
            // Start the message notification service regardless of permission
            // (it will handle the permission check internally)
            MessageNotificationServiceUtil.startMessageNotificationService(this)
            
            Log.d(TAG, "Started notification services, permission: $hasPermission")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting notification services", e)
        }
    }
}
