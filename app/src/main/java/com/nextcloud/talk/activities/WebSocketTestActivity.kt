package com.nextcloud.talk.activities

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nextcloud.talk.R
import com.nextcloud.talk.utils.WebSocketTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity to test WebSocket connection with the provided credentials
 */
class WebSocketTestActivity : AppCompatActivity(), WebSocketTest.ConnectionListener {
    
    private val TAG = "WebSocketTestActivity"
    private lateinit var statusTextView: TextView
    private lateinit var messagesTextView: TextView
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var sendTestMessageButton: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_websocket_test)
        
        // Initialize views
        statusTextView = findViewById(R.id.statusTextView)
        messagesTextView = findViewById(R.id.messagesTextView)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        sendTestMessageButton = findViewById(R.id.sendTestMessageButton)
        
        // Set up button click listeners
        connectButton.setOnClickListener {
            updateStatus("Connecting to WebSocket...")
            WebSocketTest.connect(this, this)
        }
        
        disconnectButton.setOnClickListener {
            WebSocketTest.disconnect()
            updateStatus("Disconnected")
            updateButtonState(false)
        }
        
        sendTestMessageButton.setOnClickListener {
            if (WebSocketTest.isConnected()) {
                val testMessage = """
                    {
                        "type": "ping",
                        "ping": {}
                    }
                """.trimIndent()
                
                val sent = WebSocketTest.sendTestMessage(testMessage)
                if (sent) {
                    addMessage("SENT: $testMessage")
                } else {
                    addMessage("ERROR: Failed to send test message")
                }
            } else {
                updateStatus("Not connected")
            }
        }
        
        // Initial UI state
        updateButtonState(false)
    }
    
    override fun onConnected() {
        runOnUiThread {
            updateStatus("Connected to WebSocket")
            updateButtonState(true)
        }
    }
    
    override fun onMessage(message: String) {
        runOnUiThread {
            addMessage("RECEIVED: $message")
        }
    }
    
    override fun onError(error: String) {
        runOnUiThread {
            updateStatus("Error: $error")
            updateButtonState(false)
        }
    }
    
    override fun onClosed(reason: String) {
        runOnUiThread {
            updateStatus("Connection closed: $reason")
            updateButtonState(false)
        }
    }
    
    private fun updateStatus(status: String) {
        val timestamp = getCurrentTimestamp()
        statusTextView.text = "$timestamp - $status"
        Log.d(TAG, status)
    }
    
    private fun addMessage(message: String) {
        val timestamp = getCurrentTimestamp()
        val currentText = messagesTextView.text.toString()
        val newText = "$timestamp - $message\n\n$currentText"
        messagesTextView.text = newText
    }
    
    private fun updateButtonState(connected: Boolean) {
        connectButton.isEnabled = !connected
        disconnectButton.isEnabled = connected
        sendTestMessageButton.isEnabled = connected
    }
    
    private fun getCurrentTimestamp(): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (WebSocketTest.isConnected()) {
            WebSocketTest.disconnect()
        }
    }
} 