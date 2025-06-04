package com.nextcloud.talk.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Utility class for storing and retrieving login credentials.
 * Uses SharedPreferences to store user credentials in the app's private storage.
 */
object CredentialsUtil {
    private const val TAG = "CredentialsUtil"
    private const val PREFS_NAME = "login_credentials"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    
    /**
     * Saves login credentials
     */
    fun saveCredentials(context: Context, serverUrl: String, username: String, password: String) {
        try {
            val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            
            sharedPreferences.edit()
                .putString(KEY_SERVER_URL, serverUrl)
                .putString(KEY_USERNAME, username)
                .putString(KEY_PASSWORD, password)
                .apply()

            Log.d(TAG, "✅ Credentials saved for user: $username at $serverUrl")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error saving credentials: ${e.message}", e)
        }
    }
    
    /**
     * Gets the saved server URL
     */
    fun getServerUrl(context: Context): String? {
        return getCredentialValue(context, KEY_SERVER_URL)
    }
    
    /**
     * Gets the saved username
     */
    fun getUsername(context: Context): String? {
        return getCredentialValue(context, KEY_USERNAME)
    }
    
    /**
     * Gets the saved password
     */
    fun getPassword(context: Context): String? {
        return getCredentialValue(context, KEY_PASSWORD)
    }
    
    /**
     * Checks if credentials are available
     */
    fun hasCredentials(context: Context): Boolean {
        val serverUrl = getServerUrl(context)
        val username = getUsername(context)
        val password = getPassword(context)
        
        return !serverUrl.isNullOrEmpty() && !username.isNullOrEmpty() && !password.isNullOrEmpty()
    }
    
    /**
     * Clears all saved credentials
     */
    fun clearCredentials(context: Context) {
        try {
            val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sharedPreferences.edit().clear().apply()
            Log.d(TAG, "✅ Credentials cleared")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing credentials: ${e.message}", e)
        }
    }
    
    private fun getCredentialValue(context: Context, key: String): String? {
        return try {
            val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sharedPreferences.getString(key, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error getting credential value for key $key: ${e.message}", e)
            null
        }
    }
} 