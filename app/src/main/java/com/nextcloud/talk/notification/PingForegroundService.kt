package com.nextcloud.talk.notification

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import com.nextcloud.talk.R
import com.nextcloud.talk.BuildConfig
import com.nextcloud.talk.chat.ChatActivity
import com.nextcloud.talk.conversationlist.ConversationsListActivity
import com.nextcloud.talk.utils.NotificationPermissionHelper
import com.nextcloud.talk.utils.bundle.BundleKeys
import java.io.StringReader
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.w3c.dom.Element
import org.xml.sax.InputSource

class PingForegroundService : Service() {

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

    /* ---------- Android lifecycle ---------- */
    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        Log.d(TAG, "🚀 PingForegroundService.onCreate() called")
        super.onCreate()
        
        try {
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
        val server = getServer() ?: return
        val user   = getUser() ?: return
        val pass   = getPass() ?: return

        Log.d(TAG, "🔑 Using credentials - Server: $server, User: $user, Pass: ${if(pass.isNotEmpty()) "[HIDDEN]" else "[EMPTY]"}")
        
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
            var somethingNew = false

            Log.d(TAG, "🏠 Found ${rooms.length} rooms in XML")
            Log.d(TAG, "💾 Current memorised message IDs: $memorised")
            
            // Note: Removed debug code that cleared stored IDs - keep for production

            for (i in 0 until rooms.length) {
                val e         = rooms.item(i) as Element
                val token     = e.text("token")
                val unread    = e.text("unreadMessages").trim().toIntOrNull() ?: 0
                val roomName  = e.text("displayName")
                
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

            // Use token.hashCode() as notification ID to update per-room
            val notificationId = token.hashCode()
            Log.d(TAG, "🔔 Creating child notification with ID $notificationId for token: $token")
            
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
        Log.d(TAG, "✅ Group summary notification posted with ID $SUMMARY_ID")
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
            
        nm.createNotificationChannels(listOf(serviceChannel, chatChannel))
        Log.d(TAG, "📢 Notification channels created: $CH_PING (LOW), $CH_CHAT (HIGH with DND bypass)")
    }

    /* ---------- SharedPreferences helpers ---------- */
    private fun getIdMap(): MutableMap<String,String> {
        val stored = p.getString(KEY_LAST_IDS, "{}") ?: "{}"
        Log.d(TAG, "💾 Loading stored message IDs: $stored")
        return JSONObject(stored).let { json ->
            mutableMapOf<String,String>().apply {
                for (k in json.keys()) this[k] = json.getString(k)
            }
        }
    }

    private fun saveIdMap(m: Map<String,String>) {
        val jsonString = JSONObject(m).toString()
        Log.d(TAG, "💾 Saving message IDs: $jsonString")
        p.edit().putString(KEY_LAST_IDS, jsonString).apply()
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

    /* ---------- account helpers (real mode) ---------- */
    private fun getServer(): String? {
        val result = acc()?.second
        Log.d(TAG, "🔑 getServer() = $result")
        
        // TEMPORARY: Fallback to hardcoded server for testing
        if (result == null) {
            Log.d(TAG, "🔧 No account found, using hardcoded server for testing")
            return "https://nextcloud.wztechno.com"
        }
        return result
    }
    
    private fun getUser(): String? {
        val result = acc()?.first
        Log.d(TAG, "🔑 getUser() = $result")
        
        // TEMPORARY: Fallback to hardcoded user for testing
        if (result == null) {
            Log.d(TAG, "🔧 No account found, using hardcoded user for testing")
            return "admin"
        }
        return result
    }
    
    private fun getPass(): String? {
        val result = accPw()
        Log.d(TAG, "🔑 getPass() = ${if(result?.isNotEmpty() == true) "[HIDDEN]" else "[EMPTY]"}")
        
        // TEMPORARY: Fallback to hardcoded password for testing
        if (result == null || result.isEmpty()) {
            Log.d(TAG, "🔧 No account found, using hardcoded password for testing")
            return "admin"
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
