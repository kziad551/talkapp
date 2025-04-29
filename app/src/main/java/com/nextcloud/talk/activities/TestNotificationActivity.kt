/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import autodagger.AutoInjector
import com.nextcloud.talk.R
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.application.NextcloudTalkApplication.Companion.sharedApplication
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.services.NCWebSocketNotificationService
import com.nextcloud.talk.services.WebSocketService
import com.nextcloud.talk.users.UserManager
import com.nextcloud.talk.utils.bundle.BundleKeys
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class TestNotificationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TestNotificationActivity"
        
        // WebSocket connection URL
        private const val WS_URL = "wss://nextcloud.wztechno.com/apps/spreed/ws"
        
        // Hardcoded keys
        private const val HASH_KEY = "15e3f8f2e89a9f2dd4a5e7cb2ef8bb12f56dbd28e4027b6df9126d46c1bb91f7"
        private const val BLOCK_KEY = "994ce40d771b52136ddf8fd51e86b41c"
        private const val BACKEND_URL = "https://nextcloud.wztechno.com"
        private const val BACKEND_SECRET = "changeme123"
    }

    @Inject
    lateinit var userManager: UserManager
    
    @Inject
    lateinit var okHttpClient: OkHttpClient
    
    private lateinit var userSpinner: Spinner
    private lateinit var connectionStatusText: TextView
    private lateinit var startServiceSwitch: SwitchCompat
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var sendMessageButton: Button
    private lateinit var roomTokenInput: EditText
    private lateinit var messageInput: EditText
    private lateinit var logText: TextView
    
    private var webSocket: WebSocket? = null
    private var userList: List<User> = emptyList()
    private var selectedUser: User? = null
    
    private val webSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            runOnUiThread {
                connectionStatusText.text = "Connected"
                connectButton.isEnabled = false
                disconnectButton.isEnabled = true
                sendMessageButton.isEnabled = true
                
                appendToLog("WebSocket Connected")
                
                // Send authentication message
                sendAuthenticationMessage()
            }
        }
        
        override fun onMessage(webSocket: WebSocket, text: String) {
            runOnUiThread {
                appendToLog("Received: $text")
            }
        }
        
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            runOnUiThread {
                connectionStatusText.text = "Disconnected"
                connectButton.isEnabled = true
                disconnectButton.isEnabled = false
                sendMessageButton.isEnabled = false
                
                appendToLog("WebSocket Closed: $code $reason")
            }
        }
        
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            runOnUiThread {
                connectionStatusText.text = "Connection Failed"
                connectButton.isEnabled = true
                disconnectButton.isEnabled = false
                sendMessageButton.isEnabled = false
                
                appendToLog("WebSocket Failure: ${t.message}")
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedApplication?.componentApplication?.inject(this)
        
        setContentView(R.layout.activity_test_notification)
        
        // Initialize views
        userSpinner = findViewById(R.id.user_spinner)
        connectionStatusText = findViewById(R.id.connection_status)
        startServiceSwitch = findViewById(R.id.start_service_switch)
        connectButton = findViewById(R.id.connect_button)
        disconnectButton = findViewById(R.id.disconnect_button)
        sendMessageButton = findViewById(R.id.send_message_button)
        roomTokenInput = findViewById(R.id.room_token_input)
        messageInput = findViewById(R.id.message_input)
        logText = findViewById(R.id.log_text)
        
        // Initial UI state
        connectionStatusText.text = "Disconnected"
        disconnectButton.isEnabled = false
        sendMessageButton.isEnabled = false
        
        // Setup user spinner
        setupUserSpinner()
        
        // Setup click listeners
        setupClickListeners()
    }
    
    private fun setupUserSpinner() {
        // Get users as a synchronous list
        val users = userManager.users.blockingGet() ?: emptyList()
        userList = users
        
        val userNames = userList.map { 
            "${it.displayName ?: it.userId} (${it.baseUrl})" 
        }.toTypedArray()
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, userNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        userSpinner.adapter = adapter
        
        userSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedUser = userList[position]
            }
            
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                selectedUser = null
            }
        })
        
        if (userList.isNotEmpty()) {
            selectedUser = userList[0]
        }
    }
    
    private fun setupClickListeners() {
        // Connect button
        connectButton.setOnClickListener {
            if (selectedUser == null) {
                Toast.makeText(this, "Please select a user", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            connectWebSocket()
        }
        
        // Disconnect button
        disconnectButton.setOnClickListener {
            disconnectWebSocket()
        }
        
        // Send message button
        sendMessageButton.setOnClickListener {
            val roomToken = roomTokenInput.text.toString().trim()
            val message = messageInput.text.toString().trim()
            
            if (roomToken.isEmpty()) {
                Toast.makeText(this, "Please enter a room token", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            if (message.isEmpty()) {
                Toast.makeText(this, "Please enter a message", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            sendTestMessage(roomToken, message)
        }
        
        // Service switch
        startServiceSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startWebSocketService()
            } else {
                stopWebSocketService()
            }
        }
    }
    
    private fun connectWebSocket() {
        if (webSocket != null) {
            webSocket?.close(1000, "User reconnect")
            webSocket = null
        }
        
        val request = Request.Builder()
            .url(WS_URL)
            .build()
            
        webSocket = okHttpClient.newWebSocket(request, webSocketListener)
        
        connectionStatusText.text = "Connecting..."
        connectButton.isEnabled = false
        
        appendToLog("Connecting to WebSocket...")
    }
    
    private fun disconnectWebSocket() {
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        
        connectionStatusText.text = "Disconnected"
        connectButton.isEnabled = true
        disconnectButton.isEnabled = false
        sendMessageButton.isEnabled = false
        
        appendToLog("Disconnected from WebSocket")
    }
    
    private fun sendAuthenticationMessage() {
        val authMessage = """
            {
                "type": "hello",
                "hello": {
                    "version": "1.0",
                    "auth": {
                        "hashKey": "$HASH_KEY",
                        "blockKey": "$BLOCK_KEY",
                        "backend": "$BACKEND_URL",
                        "secret": "$BACKEND_SECRET"
                    }
                }
            }
        """.trimIndent()
        
        webSocket?.send(authMessage)
        appendToLog("Sent authentication message")
    }
    
    private fun sendTestMessage(roomToken: String, message: String) {
        try {
            // Create a test notification message
            val testMessage = """
                {
                    "type": "message",
                    "message": {
                        "roomId": "$roomToken",
                        "message": "$message",
                        "actorId": "testUser",
                        "timestamp": ${System.currentTimeMillis() / 1000}
                    }
                }
            """.trimIndent()
            
            // Send to WebSocket if connected
            if (webSocket != null) {
                webSocket?.send(testMessage)
                appendToLog("Sent test message to WebSocket")
            }
            
            // Also send directly to notification service for testing
            sendDirectToNotificationService(testMessage)
            
            // Clear message input
            messageInput.setText("")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending test message", e)
            appendToLog("Error sending test message: ${e.message}")
        }
    }
    
    private fun sendDirectToNotificationService(message: String) {
        if (selectedUser == null) return
        
        val intent = Intent(this, NCWebSocketNotificationService::class.java).apply {
            putExtra("websocket_message", message)
            putExtra(BundleKeys.KEY_INTERNAL_USER_ID, selectedUser?.id)
        }
        
        startService(intent)
        appendToLog("Sent test message directly to notification service")
    }
    
    private fun startWebSocketService() {
        if (selectedUser == null) {
            Toast.makeText(this, "Please select a user", Toast.LENGTH_SHORT).show()
            startServiceSwitch.isChecked = false
            return
        }
        
        val intent = Intent(this, WebSocketService::class.java).apply {
            putExtra(BundleKeys.KEY_INTERNAL_USER_ID, selectedUser?.id)
        }
        
        startService(intent)
        appendToLog("Started WebSocket service")
    }
    
    private fun stopWebSocketService() {
        val intent = Intent(this, WebSocketService::class.java)
        stopService(intent)
        appendToLog("Stopped WebSocket service")
    }
    
    private fun appendToLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        
        val logMessage = "[$timestamp] $message\n"
        logText.append(logMessage)
        
        // Scroll to bottom
        val scrollView = findViewById<android.widget.ScrollView>(R.id.log_scroll)
        scrollView.post {
            scrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, "Activity destroyed")
    }
} 