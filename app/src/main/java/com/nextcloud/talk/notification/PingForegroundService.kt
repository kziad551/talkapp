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

    private val PREFS          = "PingServicePrefs"
    private val KEY_ERRORS     = "errors"
    private val KEY_LAST_IDS   = "last_msg_ids"   // <roomToken, msgId>

    /* ---------- demo credentials (set TEST_MODE = false in prod) ---------- */
    private val TEST_MODE      = true   // TEMPORARILY ENABLED for debugging
    private val HOST           = "https://nextcloud.wztechno.com"
    private val DEMO_USER      = "admin"
    private val DEMO_PASS      = "admin"
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
        Log.d(TAG, "PingForegroundService.onCreate() called")
        super.onCreate()
        
        try {
            Log.d(TAG, "Initializing SharedPreferences...")
            p   = getSharedPreferences(PREFS, MODE_PRIVATE)
            err = p.getInt(KEY_ERRORS, 0)
            Log.d(TAG, "SharedPreferences initialized, error count: $err")

            Log.d(TAG, "Creating notification channels...")
            createChannels()
            Log.d(TAG, "Notification channels created")
            
            Log.d(TAG, "Starting foreground with persistent notification...")
            startForeground(
                NOTIF_ID, persistent(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
            Log.d(TAG, "Foreground service started successfully")

            Log.d(TAG, "Checking notification permissions...")
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
        while (isActive) {
            val delayS = if (err > 0) min(2.0.pow(err).toInt(), maxBackoff) else 30
            try { poll() } catch (e: Exception) {
                Log.e(TAG, "poll failed", e); incrErr()
            }
            delay(delayS.seconds)
        }
    }

    private suspend fun poll() {
        val server = if (TEST_MODE) HOST else getServer() ?: return
        val user   = if (TEST_MODE) DEMO_USER else getUser() ?: return
        val pass   = if (TEST_MODE) DEMO_PASS else getPass() ?: return

        if (pollRooms(server, user, pass)) resetErr()
    }

    /* ---------- rooms endpoint ---------- */
    private suspend fun pollRooms(server: String, user: String, pass: String): Boolean {
        val url = "$server$ROOMS_API"
        val req = Request.Builder()
            .url(url).header("Authorization", Credentials.basic(user, pass))
            .header("OCS-APIRequest", "true").build()

        ok.newCall(req).execute().use { rsp ->
            if (!rsp.isSuccessful) return fail("rooms http ${rsp.code}")
            val xml = rsp.body?.string() ?: return fail("rooms empty")
            return parseXml(xml)
        }
    }

    private fun parseXml(xml: String): Boolean {
        val doc  = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        val rooms = doc.getElementsByTagName("element")
        val memorised = getIdMap()
        var somethingNew = false

        for (i in 0 until rooms.length) {
            val e         = rooms.item(i) as Element
            val unread    = e.text("unreadMessages").trim().toIntOrNull() ?: 0
            if (unread == 0) continue

            val token     = e.text("token")
            val roomName  = e.text("displayName")
            val last      = (e.getElementsByTagName("lastMessage")
                              .item(0) as? Element) ?: continue
            val msgId     = last.text("id")
            val msgTxt    = last.text("message")
            val sender    = last.text("actorDisplayName")

            if (token.isBlank() || msgId.isBlank() || msgTxt.isBlank()) continue
            if (memorised[token] == msgId) continue   // already shown

            val title = if (sender.isNotBlank())
                "$sender in $roomName" else "Message in $roomName"

            show(title, msgTxt, token)
            memorised[token] = msgId
            somethingNew = true
        }

        if (somethingNew) saveIdMap(memorised)
        return true
    }

    /* ---------- tiny XML helpers ---------- */
    private fun Element.text(tag: String): String =
        getElementsByTagName(tag).item(0)?.textContent ?: ""

    /* ---------- notification helpers ---------- */
    private fun show(title: String, text: String, token: String) {
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

        NotificationCompat.Builder(this, CH_CHAT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text)
            .setAutoCancel(true).setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
            .also { getSystemService(NotificationManager::class.java)
                    .notify(token.hashCode(), it) }
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
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_PING, "Service status",
                NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CH_CHAT, "Messages & calls",
                NotificationManager.IMPORTANCE_DEFAULT)
        ))
    }

    /* ---------- SharedPreferences helpers ---------- */
    private fun getIdMap(): MutableMap<String,String> =
        JSONObject(p.getString(KEY_LAST_IDS, "{}") ?: "{}")
            .let { json ->
                mutableMapOf<String,String>().apply {
                    for (k in json.keys()) this[k] = json.getString(k)
                }
            }

    private fun saveIdMap(m: Map<String,String>) =
        p.edit().putString(KEY_LAST_IDS, JSONObject(m).toString()).apply()

    /* ---------- error/backoff helpers ---------- */
    private fun resetErr() { if (err != 0) { err = 0; p.edit().putInt(KEY_ERRORS, 0).apply() } }
    private fun incrErr()  { err++;          p.edit().putInt(KEY_ERRORS, err).apply() }
    private fun fail(msg:String): Boolean { Log.e(TAG,msg); incrErr(); return false }

    /* ---------- account helpers (real mode) ---------- */
    private fun getServer() = acc()?.second
    private fun getUser()   = acc()?.first
    private fun getPass()   = accPw()

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
            val i = Intent(ctx, PingForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
