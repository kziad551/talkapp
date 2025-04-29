package com.nextcloud.talk.utils

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Utility class to test WebSocket connection with provided credentials
 */
object WebSocketTest {
    private const val TAG = "WebSocketTest"
    
    // User provided credentials
    private const val HASH_KEY = "15e3f8f2e89a9f2dd4a5e7cb2ef8bb12f56dbd28e4027b6df9126d46c1bb91f7"
    private const val SECRET = "changeme123"
    
    // Block key and backend URL from WebSocketService
    private const val BLOCK_KEY = "994ce40d771b52136ddf8fd51e86b41c"
    private const val BACKEND_URL = "https://nextcloud.wztechno.com"
    
    // WebSocket endpoint
    private const val WS_URL = "wss://nextcloud.wztechno.com/apps/spreed/ws"
    
    private var webSocketClient: WebSocket? = null
    private var isConnected = false
    private var connectionListener: ConnectionListener? = null
    
    interface ConnectionListener {
        fun onConnected()
        fun onMessage(message: String)
        fun onError(error: String)
        fun onClosed(reason: String)
    }
    
    private val webSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connection opened: ${response.message}")
            isConnected = true
            
            // Send authentication message
            webSocket.send(buildAuthenticationMessage())
            
            connectionListener?.onConnected()
        }
        
        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "WebSocket message received: $text")
            connectionListener?.onMessage(text)
            
            try {
                val json = JSONObject(text)
                // Additional message processing can be added here
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing message", e)
            }
        }
        
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket connection closed. Code: $code, Reason: $reason")
            isConnected = false
            connectionListener?.onClosed("Connection closed: $reason ($code)")
        }
        
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket connection failure", t)
            isConnected = false
            connectionListener?.onError("Connection failed: ${t.message}")
        }
    }
    
    /**
     * Connect to the WebSocket server with the provided credentials
     * @param context Application context
     * @param listener Optional listener for connection events
     */
    fun connect(context: Context, listener: ConnectionListener? = null) {
        connectionListener = listener
        
        // Create OkHttpClient for WebSocket
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // Disable read timeout for WebSocket
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
        
        val request = Request.Builder()
            .url(WS_URL)
            .build()
        
        // Connect to WebSocket
        Log.d(TAG, "Connecting to WebSocket: $WS_URL")
        webSocketClient = client.newWebSocket(request, webSocketListener)
    }
    
    /**
     * Disconnect from the WebSocket server
     */
    fun disconnect() {
        webSocketClient?.close(1000, "Manual disconnection")
        webSocketClient = null
        isConnected = false
    }
    
    /**
     * Send a test message to the WebSocket server
     * @param message The message to send
     * @return true if sent successfully, false otherwise
     */
    fun sendTestMessage(message: String): Boolean {
        return if (isConnected && webSocketClient != null) {
            webSocketClient?.send(message) ?: false
        } else {
            Log.e(TAG, "Cannot send message, not connected")
            false
        }
    }
    
    /**
     * Check if the WebSocket is connected
     * @return true if connected, false otherwise
     */
    fun isConnected(): Boolean {
        return isConnected
    }
    
    /**
     * Build the authentication message with the provided credentials
     */
    private fun buildAuthenticationMessage(): String {
        return """
            {
                "type": "hello",
                "hello": {
                    "version": "1.0",
                    "auth": {
                        "hashKey": "$HASH_KEY",
                        "blockKey": "$BLOCK_KEY",
                        "backend": "$BACKEND_URL",
                        "secret": "$SECRET"
                    }
                }
            }
        """.trimIndent()
    }
} 