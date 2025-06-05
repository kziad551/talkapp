package com.nextcloud.talk.notification

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.net.toUri
import autodagger.AutoInjector
import com.nextcloud.talk.R
import com.nextcloud.talk.BuildConfig
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.callnotification.CallNotificationActivity
import com.nextcloud.talk.chat.ChatActivity
import com.nextcloud.talk.conversationlist.ConversationsListActivity
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.utils.CredentialsUtil
import com.nextcloud.talk.utils.NotificationPermissionHelper
import com.nextcloud.talk.utils.NotificationUtils
import com.nextcloud.talk.utils.NotificationUtils.getCallRingtoneUri
import com.nextcloud.talk.utils.database.user.CurrentUserProviderNew
import com.nextcloud.talk.utils.preferences.AppPreferencesImpl
import com.nextcloud.talk.utils.bundle.BundleKeys
import com.nextcloud.talk.users.UserManager
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.IOException
import java.io.StringReader
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_CALL_FLAG
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_CALL_VOICE_ONLY
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_CONVERSATION_NAME
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_CONVERSATION_PASSWORD
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_FROM_NOTIFICATION_START_CALL
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_INTERNAL_USER_ID
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_IS_MODERATOR
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_MESSAGE_ID
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_MODIFIED_BASE_URL
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_NOTIFICATION_TIMESTAMP
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_PARTICIPANT_PERMISSION_CAN_PUBLISH_AUDIO
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_PARTICIPANT_PERMISSION_CAN_PUBLISH_VIDEO
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_RECORDING_STATE
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_ROOM_ONE_TO_ONE
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_ROOM_TOKEN
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_SWITCH_TO_ROOM
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_CONVERSATION_DISPLAY_NAME

@AutoInjector(NextcloudTalkApplication::class)
class PingForegroundService : Service() {

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var currentUserProvider: CurrentUserProviderNew

    /* ---------- constants ---------- */
    private val TAG = "PingForegroundService"

    private val NOTIF_ID       = 1
    private val CH_PING        = "ping_channel"
    private val CH_CHAT        = "chat_channel"
    
    // Grouped notifications
    private val GROUP_CHAT = "group_chat_messages"
    private val SUMMARY_ID = 999999

    private val PREFS          = "PingServicePrefs"
    private val KEY_ERRORS     = "errors"
    private val KEY_LAST_IDS   = "last_msg_ids"   // <roomToken, msgId>
    private val KEY_LAST_CALLS = "last_call_ids"  // <roomToken, callStartTime>

    private val ROOMS_API      = "/ocs/v2.php/apps/spreed/api/v4/room?includeStatus=1"

    /* ---------- members ---------- */
    private val scope          = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var p     : SharedPreferences
    private var  err           = 0
    private val maxBackoff     = 300   // 5 min
    private val ok             = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
                                .readTimeout (30, TimeUnit.SECONDS)
        .build()
    private val handler = Handler()

    /* ---------- Android lifecycle ---------- */
    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        Log.d(TAG, "🚀 PingForegroundService.onCreate() called")
        super.onCreate()

        try {
            Log.d(TAG, "🔧 Injecting dependencies...")
            NextcloudTalkApplication.sharedApplication!!.componentApplication.inject(this)
            Log.d(TAG, "✅ Dependencies injected successfully")
            
            Log.d(TAG, "🔧 Initializing SharedPreferences...")
            p   = getSharedPreferences(PREFS, MODE_PRIVATE)
            err = p.getInt(KEY_ERRORS, 0)
            Log.d(TAG, "✅ SharedPreferences initialized, error count: $err")

            Log.d(TAG, "📢 Creating notification channels...")
            createChannels()
            Log.d(TAG, "✅ Notification channels created")
            
            Log.d(TAG, "🏃 Starting foreground with persistent notification...")
        startForeground(
                NOTIF_ID, persistent(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
            Log.d(TAG, "✅ Foreground service started successfully")

            Log.d(TAG, "🔐 Checking notification permissions...")
        if (NotificationPermissionHelper.hasNotificationPermission(this)) {
                Log.d(TAG, "✅ Notification permission granted, starting loop")
                loop()
        } else {
                Log.e(TAG, "❌ Notification permission missing, stopping service")
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in onCreate: ${e.message}", e)
            stopSelf()
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (!NotificationPermissionHelper.hasNotificationPermission(this) || !nm.areNotificationsEnabled()) {
            stopSelf(); return START_NOT_STICKY
        }
        return START_STICKY
    }

    /* ---------- poll / loop ---------- */
    private fun loop() = scope.launch {
        Log.d(TAG, "🔄 Main polling loop started")
        Log.d(TAG, "🚀 Service started and running - beginning message polling")
            while (isActive) {
            val delayS = if (err > 0) min(2.0.pow(err).toInt(), maxBackoff) else 30
            Log.d(TAG, "⏰ Next poll in ${delayS}s (error count: $err)")
            try { 
                Log.d(TAG, "🔎 Starting poll cycle...")
                poll() 
                Log.d(TAG, "✅ Poll cycle completed successfully")
                } catch (e: Exception) {
                Log.e(TAG, "❌ Poll failed: ${e.message}", e)
                incrErr()
            }
            delay(delayS.seconds)
        }
    }

    private suspend fun poll() {
        Log.d(TAG, "🔍 poll() called - determining credentials...")
        
        // 🔧 EMERGENCY CLEANUP: Check for lingering notifications at each poll
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val activeNotifications = notificationManager.activeNotifications
            var talkNotificationsCount = 0
            
            for (notification in activeNotifications) {
                if (notification.packageName == packageName) {
                    talkNotificationsCount++
                    // If we find the problematic group summary notification, log it
                    if (notification.id == 999999) {
                        Log.w(TAG, "⚠️ EMERGENCY: Found persistent group summary notification 999999 - will clean up")
                        
                        // Emergency cleanup if too many notifications are active
                        if (talkNotificationsCount > 3) {
                            Log.w(TAG, "🚨 EMERGENCY CLEANUP: Found $talkNotificationsCount Talk notifications - cleaning up")
                            notificationManager.cancel(999999)
                            notificationManager.cancel("group_chat_messages", 999999)
                        }
                    }
                }
            }
            
            if (talkNotificationsCount > 0) {
                Log.d(TAG, "📊 Currently active Talk notifications: $talkNotificationsCount")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error during emergency notification check: ${e.message}")
        }
        
        // Check if we have saved credentials
        if (!CredentialsUtil.hasCredentials(this)) {
            Log.w(TAG, "⚠️ No credentials found - user needs to log in first")
            Log.d(TAG, "💡 To enable push notifications:")
            Log.d(TAG, "   1. Open the Nextcloud Talk app") 
            Log.d(TAG, "   2. Log in with your username and password")
            Log.d(TAG, "   3. Credentials will be saved automatically for notifications")
            return // Skip polling until user logs in
        }
        
        val server = getServer() ?: return
        val user   = getUser() ?: return
        val pass   = getPass() ?: return

        Log.d(TAG, "🔑 Using saved credentials - Server: $server, User: $user")
        
        if (pollRooms(server, user, pass)) {
            Log.d(TAG, "✅ Poll successful, resetting error count")
            resetErr()
        } else {
            Log.e(TAG, "❌ Poll failed")
        }
    }

    /* ---------- rooms endpoint ---------- */
    private suspend fun pollRooms(server: String, user: String, pass: String): Boolean {
        Log.d(TAG, "🌐 pollRooms called with server=$server, user=$user")
        val url = "$server$ROOMS_API"
        Log.d(TAG, "🔗 Full API URL: $url")

        val req = Request.Builder()
            .url(url).header("Authorization", Credentials.basic(user, pass))
            .header("OCS-APIRequest", "true").build()

        Log.d(TAG, "📡 Making HTTP request to rooms API...")
        ok.newCall(req).execute().use { rsp ->
            Log.d(TAG, "📥 HTTP response received - Code: ${rsp.code}, Success: ${rsp.isSuccessful}")
            if (!rsp.isSuccessful) {
                Log.e(TAG, "❌ HTTP error ${rsp.code} - ${rsp.message}")
                return fail("rooms http ${rsp.code}")
            }
            val xml = rsp.body?.string()
            if (xml == null) {
                Log.e(TAG, "❌ Empty response body from API")
                return fail("rooms empty")
            }
            Log.d(TAG, "📄 Received XML response (${xml.length} chars)")
            Log.d(TAG, "📄 Response preview: ${xml.take(500)}...")
            
            // Log the full XML for debugging
            if (xml.length < 2000) {
                Log.d(TAG, "📄 FULL XML RESPONSE: $xml")
            }
            
            return parseXml(xml)
        }
    }

    private fun parseXml(xml: String): Boolean {
        Log.d(TAG, "🔍 parseXml called with ${xml.length} chars")
        try {
            val doc  = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))
            val rooms = doc.getElementsByTagName("element")
            val memorised = getIdMap()
            val callMemorised = getCallMap()
            var somethingNew = false

            Log.d(TAG, "🏠 Found ${rooms.length} rooms in XML")
            Log.d(TAG, "💾 Current memorised message IDs: $memorised")
            
            // Note: Removed debug code that cleared stored IDs - keep for production

            for (i in 0 until rooms.length) {
                val e         = rooms.item(i) as Element
                val token     = e.text("token")
                val unread    = e.text("unreadMessages").trim().toIntOrNull() ?: 0
                val roomName  = e.text("displayName")
                val callFlag  = e.text("callFlag").trim().toIntOrNull() ?: 0
                val callStart = e.text("callStartTime").trim().toLongOrNull() ?: 0L
                val hasCall   = e.text("hasCall").trim().equals("true", true)

                Log.d(TAG, "🔍 Call detection for room '$roomName':")
                Log.d(TAG, "   🚩 callFlag: $callFlag")
                Log.d(TAG, "   ⏰ callStart: $callStart")
                Log.d(TAG, "   📞 hasCall: $hasCall")
                Log.d(TAG, "   💾 Last call start stored: ${callMemorised[token]}")
                
                val isNewCall = (hasCall || callFlag > 0 || callStart > 0) && callMemorised[token] != callStart
                Log.d(TAG, "   🆕 Is new call? $isNewCall")

                if (isNewCall) {
                    Log.d(TAG, "🚨 NEW CALL DETECTED!")
                    Log.d(TAG, "   🏠 Room: '$roomName'")
                    Log.d(TAG, "   🎫 Token: '$token'")
                    Log.d(TAG, "   📤 Triggering full-screen call notification...")
                    
                    showCall(roomName, token, callFlag, callStart)
                    callMemorised[token] = callStart
                    somethingNew = true
                } else if (!hasCall && callFlag == 0 && callStart == 0L && callMemorised.containsKey(token)) {
                    // 🔧 PERSISTENT RINGING FIX: Cancel any existing call notifications if call ended
                    Log.d(TAG, "🔕 Call ended for room: '$roomName' (token: $token)")
                    Log.d(TAG, "🔕 AGGRESSIVE CLEANUP: Removing all call-related notifications")
                    
                    // Cancel specific call notification for this room
                    val currentUser = currentUserProvider.currentUser.blockingGet()
                    NotificationUtils.cancelExistingNotificationsForRoom(applicationContext, currentUser, token)
                    
                    // AGGRESSIVE: Cancel all possible call notification IDs for this call
                    val callStartTime = callMemorised[token] ?: 0L
                    if (callStartTime > 0) {
                        Log.d(TAG, "🔕 Canceling notifications based on call start time: $callStartTime")
                        // Cancel notifications created around the call start time (±2 minutes)
                        for (offset in -120..120) {
                            val notificationId = (callStartTime + (offset * 1000)).toInt()
                            try {
                                NotificationManagerCompat.from(applicationContext).cancel(notificationId)
                            } catch (e: Exception) {
                                // Silently continue - some IDs might not exist
                            }
                        }
                    }
                    
                    // NUCLEAR OPTION: Cancel ALL active Talk notifications to ensure cleanup
                    try {
                        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        val activeNotifications = notificationManager.activeNotifications
                        Log.d(TAG, "🔕 NUCLEAR CLEANUP: Found ${activeNotifications.size} active notifications")
                        
                        for (notification in activeNotifications) {
                            if (notification.packageName == packageName) {
                                Log.d(TAG, "🔕 NUCLEAR: Canceling notification ID=${notification.id}, tag=${notification.tag}")
                                notificationManager.cancel(notification.tag, notification.id)
                            }
                        }
                        
                        // Also cancel group summary
                        notificationManager.cancel("group_chat_messages", 999999)
                        notificationManager.cancel(999999)
                        
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Error during nuclear cleanup: ${e.message}")
                    }
                    
                    callMemorised.remove(token)
                    somethingNew = true
                    Log.d(TAG, "🔕 Call cleanup completed for room: $roomName")
                }
                
                Log.d(TAG, "🏠 Room #$i: token='$token', name='$roomName', unreadMessages=$unread")
                
                // REMOVED: Smart unread check - notify whenever lastMessage ID changes
                // The server often marks messages as read too quickly, so we ignore unread count
                // and focus purely on message ID changes
                Log.d(TAG, "🔍 Processing room '$roomName' (checking message ID only)")
                
                val lastMessageElement = e.getElementsByTagName("lastMessage").item(0) as? Element
                if (lastMessageElement == null) {
                    Log.w(TAG, "⚠️ No lastMessage element found for room '$roomName'")
                    continue
                }
                
                val msgId     = lastMessageElement.text("id")
                val msgTxt    = lastMessageElement.text("message")
                val sender    = lastMessageElement.text("actorDisplayName")
                val timestamp = lastMessageElement.text("timestamp")

                Log.d(TAG, "💬 Last message details:")
                Log.d(TAG, "   📝 Message ID: '$msgId'")
                Log.d(TAG, "   📄 Message text: '$msgTxt'")
                Log.d(TAG, "   👤 Sender: '$sender'")
                Log.d(TAG, "   ⏰ Timestamp: '$timestamp'")

                if (token.isBlank()) {
                    Log.w(TAG, "⚠️ Skipping room - empty token")
                    continue
                }
                if (msgId.isBlank()) {
                    Log.w(TAG, "⚠️ Skipping room '$roomName' - empty message ID")
                    continue
                }
                if (msgTxt.isBlank()) {
                    Log.w(TAG, "⚠️ Skipping room '$roomName' - empty message text")
                    continue
                }

                // Using cleared map for debugging
                Log.d(TAG, "🔍 Comparison for room '$roomName':")
                Log.d(TAG, "   💾 Previously seen ID: '${memorised[token]}'")
                Log.d(TAG, "   🆕 Current message ID: '$msgId'")
                Log.d(TAG, "   �� Are they equal? ${memorised[token] == msgId}")
                
                if (memorised[token] == msgId) {
                    Log.d(TAG, "⏭️ Message $msgId already seen in room '$roomName', skipping notification")
                    continue   // already shown
                }

                // 🚫 Skip notifications for messages sent by current user
                if (getUser() != null && sender.equals(getUser(), ignoreCase = true)) {
                    Log.d(TAG, "🚫 Skipping self-sent message:")
                    Log.d(TAG, "   👤 Current user: '${getUser()}'")
                    Log.d(TAG, "   📤 Message sender: '$sender'")
                    Log.d(TAG, "   💬 Message: '$msgTxt'")
                    Log.d(TAG, "   ⏭️ Updating message ID and continuing...")
                    
                    // Still update the message ID to mark it as seen
                    memorised[token] = msgId
                    somethingNew = true
                    continue
                }

                // 🔧 CALL-RELATED MESSAGE FILTER: Skip notifications for call system messages
                val messageText = msgTxt.lowercase()
                val isCallRelatedMessage = messageText.contains("ended the call") || 
                                         messageText.contains("call ended") ||
                                         messageText.contains("duration") ||
                                         messageText.contains("unanswered call") ||
                                         messageText.contains("missed call") ||
                                         messageText.contains("call with") ||
                                         messageText.contains("{actor} ended") ||
                                         messageText.contains("{user1}")
                
                if (isCallRelatedMessage) {
                    Log.d(TAG, "🔇 FILTERED: Skipping call-related system message notification")
                    Log.d(TAG, "   💬 Message: '$msgTxt'")
                    Log.d(TAG, "   📝 Reason: Call system messages should not vibrate/sound")
                    memorised[token] = msgId
                    somethingNew = true
                    continue
                }

                val title = if (sender.isNotBlank())
                    "$sender in $roomName" else "Message in $roomName"

                Log.d(TAG, "🚨 NEW MESSAGE DETECTED!")
                Log.d(TAG, "   🏠 Room: '$roomName' (token: $token)")
                Log.d(TAG, "   📧 Title: '$title'")
                Log.d(TAG, "   💬 Text: '$msgTxt'")
                Log.d(TAG, "   👤 Sender: '$sender'")
                Log.d(TAG, "   🆔 Message ID: '$msgId'")
                Log.d(TAG, "   📤 Triggering notification...")
                
                show(title, msgTxt, token)
                memorised[token] = msgId
                somethingNew = true
                
                Log.d(TAG, "✅ Notification triggered and message ID saved")
            }

            if (somethingNew) {
                saveIdMap(memorised)
                saveCallMap(callMemorised)
                Log.d(TAG, "💾 Saved updated message IDs to storage: $memorised")
            } else {
                Log.d(TAG, "📭 No new messages found in any rooms")
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing XML: ${e.message}", e)
            return false
        }
    }

    /* ---------- tiny XML helpers ---------- */
    private fun Element.text(tag: String): String {
        val result = getElementsByTagName(tag).item(0)?.textContent ?: ""
        Log.v(TAG, "📄 XML field '$tag' = '$result'")
        return result
    }

    /* ---------- notification helpers ---------- */
    private fun show(title: String, text: String, token: String) {
        Log.d(TAG, "🔔 show() called:")
        Log.d(TAG, "   📧 Title: '$title'")
        Log.d(TAG, "   💬 Text: '$text'")
        Log.d(TAG, "   🎫 Token: '$token'")
        
        try {
            val nm = getSystemService(NotificationManager::class.java)
            
            // Check notification system status
            Log.d(TAG, "🔔 Notification system checks:")
            Log.d(TAG, "   📱 Notifications enabled: ${nm.areNotificationsEnabled()}")
            Log.d(TAG, "   🔐 Notification permission: ${NotificationPermissionHelper.hasNotificationPermission(this)}")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Log.d(TAG, "   🔕 DND filter: ${nm.currentInterruptionFilter}")
            }
            
            // Check channels
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val chatChannel = nm.getNotificationChannel(CH_CHAT)
                if (chatChannel != null) {
                    Log.d(TAG, "   📢 Chat channel importance: ${chatChannel.importance}")
                    Log.d(TAG, "   📢 Chat channel enabled: ${chatChannel.importance != NotificationManager.IMPORTANCE_NONE}")
        } else {
                    Log.e(TAG, "   ❌ Chat channel not found!")
                }
            }
            
            val i = Intent(this,
                if (token.isBlank()) ConversationsListActivity::class.java else ChatActivity::class.java
            ).apply {
                if (token.isNotBlank()) putExtra(BundleKeys.KEY_ROOM_TOKEN, token)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pi = TaskStackBuilder.create(this).run {
                addNextIntentWithParentStack(i)
                getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0))
            }

            // Create individual message notification (child of group)
            val notification = NotificationCompat.Builder(this, CH_CHAT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setGroup(GROUP_CHAT)           // Add to group
                .setGroupSummary(false)         // This is a child notification
            .build()

            // Add FLAG_INSISTENT like NotificationWorker
            notification.flags = notification.flags or Notification.FLAG_INSISTENT
            
            // Android 14+ introduces a user setting controlling full-screen intents
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !nm.canUseFullScreenIntent()) {
                // If permission was denied, inform the user how to enable it
                showFullScreenPermissionGuidance()
            }
            
            val notificationId = System.currentTimeMillis().toInt()
            
            if (nm.areNotificationsEnabled()) {
                nm.notify(notificationId, notification)
                Log.d(TAG, "✅ Child notification posted successfully!")
                
                // Create/update group summary notification
                createGroupSummaryNotification(nm)
                
            } else {
                Log.e(TAG, "❌ Notifications are disabled in system settings!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing notification: ${e.message}", e)
        }
    }

    private fun showCall(roomName: String, token: String, callFlag: Int, callStart: Long) {
        Log.d(TAG, "🔔 showCall() called - Creating call notification exactly like NotificationWorker")
        Log.d(TAG, "   🏠 Room: '$roomName'")
        Log.d(TAG, "   🎫 Token: '$token'")
        Log.d(TAG, "   🚩 Call Flag: $callFlag")
        Log.d(TAG, "   ⏰ Call Start: $callStart")
        
        try {
            val appPreferences = AppPreferencesImpl(this)
            val currentUser = currentUserProvider.currentUser.blockingGet()
            
            if (currentUser == null) {
                Log.e(TAG, "❌ No current user found, cannot show call notification")
                return
            }
            
            // Check Android 14+ full-screen intent permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                checkAndRequestFullScreenIntentPermission()
            }
            
            val notificationTimestamp = System.currentTimeMillis()
            
            // Create intent for CallNotificationActivity
            val fullScreenIntent = Intent(this, CallNotificationActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or 
                       Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION
                putExtra(KEY_INTERNAL_USER_ID, currentUser.id)
                putExtra(KEY_ROOM_TOKEN, token)  
                putExtra(KEY_CONVERSATION_DISPLAY_NAME, roomName)
                putExtra(KEY_CALL_FLAG, callFlag)
                putExtra(KEY_NOTIFICATION_TIMESTAMP, notificationTimestamp.toInt())
                putExtra(KEY_MESSAGE_ID, "call_$callStart")
                putExtra(KEY_CALL_VOICE_ONLY, false)
                putExtra(KEY_FROM_NOTIFICATION_START_CALL, true)
                putExtra(KEY_SWITCH_TO_ROOM, token)
                
                // 🔧 CRITICAL FIX: Add participant permissions to prevent "not allowed to talk" error
                putExtra(KEY_PARTICIPANT_PERMISSION_CAN_PUBLISH_AUDIO, true)
                putExtra(KEY_PARTICIPANT_PERMISSION_CAN_PUBLISH_VIDEO, true) 
                putExtra(KEY_IS_MODERATOR, false) // Default to false, can be enhanced later
                putExtra(KEY_ROOM_ONE_TO_ONE, false) // Default for group calls
                putExtra(KEY_CONVERSATION_PASSWORD, "") // Empty for now
                putExtra(KEY_MODIFIED_BASE_URL, currentUser.baseUrl ?: "")
                putExtra(KEY_CONVERSATION_NAME, roomName)
                putExtra(KEY_RECORDING_STATE, 0) // No recording by default
                
                Log.d(TAG, "🔧 Creating incoming call notification intent:")
                Log.d(TAG, "   📞 FROM_NOTIFICATION_START_CALL: true")
                Log.d(TAG, "   🎫 Room Token: $token")
                Log.d(TAG, "   📧 Display Name: $roomName")
                Log.d(TAG, "   🚩 Call Flag: $callFlag")
            }
            
            val fullScreenPendingIntent = PendingIntent.getActivity(
                this,
                notificationTimestamp.toInt(),
                fullScreenIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                else
                    PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            // Create call notification
            val soundUri = NotificationUtils.getCallRingtoneUri(this, appPreferences)
            val notificationChannelId = NotificationUtils.NotificationChannels.NOTIFICATION_CHANNEL_CALLS_V4.name
            val notification = NotificationCompat.Builder(this, notificationChannelId)
                .setSmallIcon(R.drawable.ic_call_white_24dp)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentTitle(roomName)
                .setContentText("Incoming call...")
                .setOngoing(true)
                .setAutoCancel(false)
                .setDefaults(0) // No default sound/vibration, we set custom
                .setSound(soundUri, AudioManager.STREAM_RING)
                .setVibrate(longArrayOf(0, 1000, 1000, 1000))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setContentIntent(fullScreenPendingIntent)
                .build()
            
            // Apply FLAG_INSISTENT for continuous ringing
            notification.flags = notification.flags or Notification.FLAG_INSISTENT
            
            Log.d(TAG, "📢 Showing call notification with ID: $notificationTimestamp")
            Log.d(TAG, "   🔊 Sound URI: $soundUri")
            Log.d(TAG, "   📳 Vibration: ON")
            Log.d(TAG, "   🚨 FLAG_INSISTENT: ON")
            Log.d(TAG, "   📱 Full-screen intent: YES")
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Android 14+ introduces a user setting controlling full-screen intents
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !notificationManager.canUseFullScreenIntent()) {
                // If permission was denied, inform the user how to enable it
                showFullScreenPermissionGuidance()
            }
            
            notificationManager.notify(notificationTimestamp.toInt(), notification)
            
            Log.d(TAG, "✅ Call notification created successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating call notification", e)
        }
    }
    
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun checkAndRequestFullScreenIntentPermission() {
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            
            if (!notificationManager.canUseFullScreenIntent()) {
                Log.w(TAG, "⚠️ Full-screen intent permission not granted on Android 14+")
                showFullScreenPermissionGuidance()
            } else {
                Log.d(TAG, "✅ Full-screen intent permission is granted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error checking full-screen intent permission: ${e.message}", e)
        }
    }
    
    private fun showFullScreenPermissionGuidance() {
        try {
            Log.d(TAG, "📖 Showing full-screen permission guidance for Android 14+")
            
            // Create intent to open the full-screen intent settings
            val settingsIntent = Intent().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    action = "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT"
                    data = android.net.Uri.fromParts("package", packageName, null)
                } else {
                    action = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            val settingsPendingIntent = PendingIntent.getActivity(
                this,
                9998,
                settingsIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                else
                    PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            val guidanceNotification = NotificationCompat.Builder(this, NotificationUtils.NotificationChannels.NOTIFICATION_CHANNEL_MESSAGES_V4.name)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Enable Full-Screen Calls")
                .setContentText("Tap to allow full-screen incoming calls")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("To see incoming calls as full-screen alerts when your phone is unlocked, please enable the 'Display over other apps' permission for Talk. Tap this notification to open settings."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(settingsPendingIntent)
                .addAction(R.drawable.ic_settings, "Open Settings", settingsPendingIntent)
                .build()
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(9998, guidanceNotification)
            Log.d(TAG, "✅ Full-screen permission guidance notification shown")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing full-screen permission guidance: ${e.message}", e)
        }
    }

    private fun createGroupSummaryNotification(nm: NotificationManager) {
        Log.d(TAG, "📊 Creating/updating group summary notification")
        
        // Simple summary notification
        val summaryNotification = NotificationCompat.Builder(this, CH_CHAT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New Talk messages")
            .setContentText("You have new messages")
            .setStyle(NotificationCompat.InboxStyle()
                .addLine("Multiple conversations have new messages")
                .setBigContentTitle("Nextcloud Talk")
                .setSummaryText("New messages"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP_CHAT)           // Same group
            .setGroupSummary(true)          // This IS the summary
            .setAutoCancel(true)
            .build()

        nm.notify(SUMMARY_ID, summaryNotification)
        Log.d(TAG, "✅ Group summary notification posted with ID 999999")
        Log.d(TAG, "✅ Notification triggered and message ID saved")
        
        // 🔧 PREVENTIVE CLEANUP: Schedule cleanup to prevent group summary from becoming persistently noisy
        handler.postDelayed({
            try {
                Log.d(TAG, "🧹 PREVENTIVE: Running cleanup 3 seconds after notification creation")
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val activeNotifications = notificationManager.activeNotifications
                
                var groupSummaryCount = 0
                var totalTalkNotifications = 0
                
                for (notification in activeNotifications) {
                    if (notification.packageName == packageName) {
                        totalTalkNotifications++
                        if (notification.id == 999999) {
                            groupSummaryCount++
                        }
                    }
                }
                
                Log.d(TAG, "🧹 PREVENTIVE: Found $totalTalkNotifications total Talk notifications, $groupSummaryCount group summaries")
                
                // If we have too many notifications or multiple group summaries, clean them up
                if (groupSummaryCount > 1 || totalTalkNotifications > 5) {
                    Log.w(TAG, "⚠️ PREVENTIVE: Excessive notifications detected - cleaning up")
                    notificationManager.cancel(999999)
                    notificationManager.cancel("group_chat_messages", 999999)
                    
                    // Also clean up any very old individual notifications
                    for (notification in activeNotifications) {
                        if (notification.packageName == packageName && notification.id != 999999 && notification.id != 1) {
                            val age = System.currentTimeMillis() - notification.postTime
                            if (age > 300000) { // 5 minutes old
                                Log.d(TAG, "🧹 PREVENTIVE: Cleaning old notification ID ${notification.id}")
                                notificationManager.cancel(notification.id)
                            }
                        }
                    }
                } else {
                    Log.d(TAG, "🧹 PREVENTIVE: Notification levels normal, no cleanup needed")
                }
                
                Log.d(TAG, "🧹 PREVENTIVE: Cleanup completed")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error during preventive cleanup: ${e.message}")
            }
        }, 3000) // 3 second delay
    }

    private fun persistent() = NotificationCompat.Builder(this, CH_PING)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Talk background service")
        .setContentText("Polling every 30 s")
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true).build()

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        
        val chatChannel = NotificationChannel(CH_CHAT, "Messages & calls",
            NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Chat messages and call notifications"
                enableLights(true)
                enableVibration(true)
                setBypassDnd(true)  // Break through Do Not Disturb
                setShowBadge(true)
            }
        
        val serviceChannel = NotificationChannel(CH_PING, "Service status",
            NotificationManager.IMPORTANCE_LOW).apply {
                description = "Background service status"
                setShowBadge(false)
            }
            
        // Create the calls notification channel that NotificationWorker uses
        val callsChannel = NotificationChannel(
            NotificationUtils.NotificationChannels.NOTIFICATION_CHANNEL_CALLS_V4.name,
            "Call notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming call notifications"
            enableLights(true)
            enableVibration(true)
            setBypassDnd(true)  // Break through Do Not Disturb
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            // For calls, we want maximum importance
            importance = NotificationManager.IMPORTANCE_HIGH
            // Enable sound
            setSound(
                android.provider.Settings.System.DEFAULT_RINGTONE_URI,
                android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()
            )
        }
            
        nm.createNotificationChannels(listOf(serviceChannel, chatChannel, callsChannel))
        Log.d(TAG, "📢 Notification channels created: $CH_PING (LOW), $CH_CHAT (HIGH), ${NotificationUtils.NotificationChannels.NOTIFICATION_CHANNEL_CALLS_V4.name} (HIGH) - all with DND bypass")
    }

    /* ---------- SharedPreferences helpers ---------- */
    private fun getIdMap(): MutableMap<String,String> {
        val stored = p.getString(KEY_LAST_IDS, "{}") ?: "{}"
        Log.d(TAG, "\uD83D\uDCBE Loading stored message IDs: $stored")
        return JSONObject(stored).let { json ->
            mutableMapOf<String,String>().apply {
                for (k in json.keys()) this[k] = json.getString(k)
            }
        }
    }

    private fun getCallMap(): MutableMap<String,Long> {
        val stored = p.getString(KEY_LAST_CALLS, "{}") ?: "{}"
        Log.d(TAG, "\uD83D\uDCBE Loading stored call IDs: $stored")
        return JSONObject(stored).let { json ->
            mutableMapOf<String,Long>().apply {
                for (k in json.keys()) this[k] = json.getLong(k)
            }
        }
    }

    private fun saveIdMap(m: Map<String,String>) {
        val jsonString = JSONObject(m).toString()
        Log.d(TAG, "\uD83D\uDCBE Saving message IDs: $jsonString")
        p.edit().putString(KEY_LAST_IDS, jsonString).apply()
    }

    private fun saveCallMap(m: Map<String,Long>) {
        val jsonString = JSONObject(m).toString()
        Log.d(TAG, "\uD83D\uDCBE Saving call IDs: $jsonString")
        p.edit().putString(KEY_LAST_CALLS, jsonString).apply()
    }

    /* ---------- error/backoff helpers ---------- */
    private fun resetErr() { 
        if (err != 0) { 
            Log.d(TAG, "🔄 Resetting error count from $err to 0")
            err = 0; p.edit().putInt(KEY_ERRORS, 0).apply() 
        } 
    }
    private fun incrErr() { 
        err++
        Log.d(TAG, "⚠️ Incrementing error count to $err")
        p.edit().putInt(KEY_ERRORS, err).apply() 
    }
    private fun fail(msg:String): Boolean { 
        Log.e(TAG,"❌ FAILURE: $msg")
        incrErr()
        return false 
    }

    /* ---------- account helpers (using saved credentials) ---------- */
    private fun getServer(): String? {
        val result = CredentialsUtil.getServerUrl(this)
        Log.d(TAG, "🔑 getServer() = $result")
        
        if (result == null) {
            Log.w(TAG, "⚠️ No server URL found in saved credentials")
            Log.d(TAG, "💡 Please log in through the app to save credentials for push notifications")
        }
        return result
    }
    
    private fun getUser(): String? {
        val result = CredentialsUtil.getUsername(this)
        Log.d(TAG, "🔑 getUser() = $result")
        
        if (result == null) {
            Log.w(TAG, "⚠️ No username found in saved credentials")
            Log.d(TAG, "💡 Please log in through the app to save credentials for push notifications")
        }
        return result
    }
    
    private fun getPass(): String? {
        val result = CredentialsUtil.getPassword(this)
        Log.d(TAG, "🔑 getPass() = ${if(result?.isNotEmpty() == true) "[HIDDEN]" else "[EMPTY]"}")
        
        if (result == null || result.isEmpty()) {
            Log.w(TAG, "⚠️ No password found in saved credentials")
            Log.d(TAG, "💡 Please log in through the app to save credentials for push notifications")
        }
        return result
    }

    private fun acc() =
        android.accounts.AccountManager.get(this)
            .getAccountsByType("com.nextcloud.talk")
            .firstOrNull()
            ?.let {
                val am = android.accounts.AccountManager.get(this)
                am.getUserData(it, "raw_username") to
                am.getUserData(it, "server_url")
            }

    private fun accPw() =
        android.accounts.AccountManager.get(this)
            .getAccountsByType("com.nextcloud.talk")
            .firstOrNull()
            ?.let { android.accounts.AccountManager.get(this).getPassword(it) }

    /* ---------- convenience ---------- */
    companion object {
        fun start(ctx: Context) {
            Log.d("PingForegroundService", "🚀 start() called - attempting to start service")
            
            // Always attempt to start the service - handle permissions inside onCreate()
            val i = Intent(ctx, PingForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                    Log.d("PingForegroundService", "✅ startForegroundService() called successfully")
                } else {
                    ctx.startService(i)
                    Log.d("PingForegroundService", "✅ startService() called successfully")
                }
            } catch (e: Exception) {
                Log.e("PingForegroundService", "❌ Failed to start service: ${e.message}", e)
            }
        }
    }
}
