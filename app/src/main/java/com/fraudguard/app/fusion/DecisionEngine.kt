package com.fraudguard.app.fusion

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import com.fraudguard.app.core.FusionResult
import com.fraudguard.app.core.ThreatLevel
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * DecisionEngine executes actions based on fusion results
 * Handles logging, re-authentication, account locking, and notifications
 */
class DecisionEngine(private val context: Context) {
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    
    // Decision history
    private val decisionHistory = mutableListOf<DecisionRecord>()
    private val maxHistorySize = 500
    
    // Callbacks for UI integration
    private var onReauthenticationRequired: ((ThreatLevel, String) -> Unit)? = null
    private var onAccountLocked: ((String) -> Unit)? = null
    private var onThreatDetected: ((FusionResult) -> Unit)? = null
    
    data class DecisionRecord(
        val timestamp: Long,
        val threatLevel: ThreatLevel,
        val overallScore: Float,
        val action: String,
        val agentsInvolved: Set<String>,
        val success: Boolean = true,
        val errorMessage: String? = null
    )
    
    /**
     * Execute decision based on fusion result
     */
    fun executeDecision(fusionResult: FusionResult) {
        scope.launch {
            try {
                val action = when (fusionResult.threatLevel) {
                    ThreatLevel.LOW -> executeLowThreatAction(fusionResult)
                    ThreatLevel.MEDIUM -> executeMediumThreatAction(fusionResult)
                    ThreatLevel.HIGH -> executeHighThreatAction(fusionResult)
                }
                
                // Record decision
                recordDecision(fusionResult, action, true)
                
                // Notify UI
                onThreatDetected?.invoke(fusionResult)
                
            } catch (e: Exception) {
                Log.e("DecisionEngine", "Error executing decision: ${e.message}")
                recordDecision(fusionResult, "ERROR", false, e.message)
            }
        }
    }
    
    /**
     * Execute low threat level actions (logging only)
     */
    private suspend fun executeLowThreatAction(fusionResult: FusionResult): String {
        val logMessage = createLogMessage(fusionResult)
        
        // Log to system log
        Log.i("FraudGuard", logMessage)
        
        // Log to persistent storage
        saveThreatLog(fusionResult, "LOW_THREAT_LOGGED")
        
        return "LOGGED"
    }
    
    /**
     * Execute medium threat level actions (re-authentication)
     */
    private suspend fun executeMediumThreatAction(fusionResult: FusionResult): String {
        val logMessage = createLogMessage(fusionResult)
        Log.w("FraudGuard", "MEDIUM THREAT: $logMessage")
        
        // Save threat log
        saveThreatLog(fusionResult, "MEDIUM_THREAT_REAUTH_REQUIRED")
        
        // Determine re-authentication type
        val reauthType = determineReauthenticationType(fusionResult)
        
        // Trigger re-authentication
        triggerReauthentication(fusionResult.threatLevel, reauthType)
        
        // Send notification if configured
        sendThreatNotification(fusionResult, "Re-authentication required")
        
        return "REAUTH_REQUIRED"
    }
    
    /**
     * Execute high threat level actions (account locking)
     */
    private suspend fun executeHighThreatAction(fusionResult: FusionResult): String {
        val logMessage = createLogMessage(fusionResult)
        Log.e("FraudGuard", "HIGH THREAT: $logMessage")
        
        // Save critical threat log
        saveThreatLog(fusionResult, "HIGH_THREAT_ACCOUNT_LOCKED")
        
        // Lock account
        lockAccount(fusionResult)
        
        // Send immediate notification
        sendUrgentThreatNotification(fusionResult)
        
        // Additional security measures
        executeAdditionalSecurityMeasures(fusionResult)
        
        return "ACCOUNT_LOCKED"
    }
    
    /**
     * Create detailed log message
     */
    private fun createLogMessage(fusionResult: FusionResult): String {
        val timestamp = dateFormat.format(Date(fusionResult.timestamp))
        val agentScores = fusionResult.agentScores.map { (agent, result) ->
            "$agent: ${String.format("%.2f", result.score)}"
        }.joinToString(", ")
        
        return "[$timestamp] Threat Level: ${fusionResult.threatLevel.name}, " +
                "Overall Score: ${String.format("%.3f", fusionResult.overallScore)}, " +
                "Agent Scores: [$agentScores], " +
                "Action: ${fusionResult.recommendedAction}"
    }
    
    /**
     * Save threat log to persistent storage
     */
    private suspend fun saveThreatLog(fusionResult: FusionResult, action: String) {
        withContext(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences("fraud_logs", Context.MODE_PRIVATE)
                val currentLogs = prefs.getStringSet("threat_logs", mutableSetOf()) ?: mutableSetOf()
                
                val logEntry = createLogEntry(fusionResult, action)
                currentLogs.add(logEntry)
                
                // Keep only last 1000 entries
                if (currentLogs.size > 1000) {
                    val sortedLogs = currentLogs.sorted()
                    val trimmedLogs = sortedLogs.takeLast(1000).toSet()
                    currentLogs.clear()
                    currentLogs.addAll(trimmedLogs)
                }
                
                prefs.edit().putStringSet("threat_logs", currentLogs).apply()
                
            } catch (e: Exception) {
                Log.e("DecisionEngine", "Error saving threat log: ${e.message}")
            }
        }
    }
    
    /**
     * Create log entry string
     */
    private fun createLogEntry(fusionResult: FusionResult, action: String): String {
        return "${fusionResult.timestamp}|${fusionResult.threatLevel.name}|" +
                "${fusionResult.overallScore}|$action|" +
                "${fusionResult.agentScores.keys.joinToString(",")}"
    }
    
    /**
     * Determine the type of re-authentication required
     */
    private fun determineReauthenticationType(fusionResult: FusionResult): String {
        val highScoreAgents = fusionResult.agentScores.filter { it.value.score > 0.6f }.keys
        
        return when {
            highScoreAgents.contains("TouchAgent") || highScoreAgents.contains("TypingAgent") -> {
                "BIOMETRIC" // Behavioral anomalies require biometric verification
            }
            highScoreAgents.contains("MovementAgent") -> {
                "LOCATION_VERIFICATION" // Location anomalies require additional verification
            }
            highScoreAgents.contains("HoneypotAgent") -> {
                "CAPTCHA_AND_PASSWORD" // Network anomalies require CAPTCHA + password
            }
            else -> "PASSWORD" // Standard password re-authentication
        }
    }
    
    /**
     * Trigger re-authentication based on type
     */
    private fun triggerReauthentication(threatLevel: ThreatLevel, reauthType: String) {
        val message = when (reauthType) {
            "BIOMETRIC" -> "Unusual behavior detected. Please verify your identity with biometric authentication."
            "LOCATION_VERIFICATION" -> "Unusual location activity detected. Please verify your identity."
            "CAPTCHA_AND_PASSWORD" -> "Suspicious network activity detected. Please complete verification."
            else -> "Security verification required. Please re-enter your credentials."
        }
        
        // Notify UI to show re-authentication dialog
        onReauthenticationRequired?.invoke(threatLevel, message)
    }
    
    /**
     * Lock account and notify relevant systems
     */
    private fun lockAccount(fusionResult: FusionResult) {
        // Set account lock flag
        val prefs = context.getSharedPreferences("security_state", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("account_locked", true)
            .putLong("lock_timestamp", System.currentTimeMillis())
            .putString("lock_reason", fusionResult.recommendedAction)
            .putFloat("lock_score", fusionResult.overallScore)
            .apply()
        
        // Notify UI
        onAccountLocked?.invoke(fusionResult.recommendedAction)
        
        Log.e("FraudGuard", "ACCOUNT LOCKED: ${fusionResult.recommendedAction}")
    }
    
    /**
     * Send threat notification
     */
    private fun sendThreatNotification(fusionResult: FusionResult, title: String) {
        // In a real implementation, you would send push notifications
        // or integrate with your notification system
        Log.i("FraudGuard", "NOTIFICATION: $title - ${fusionResult.recommendedAction}")
    }
    
    /**
     * Send urgent threat notification
     */
    private fun sendUrgentThreatNotification(fusionResult: FusionResult) {
        sendThreatNotification(fusionResult, "URGENT: Security Alert")
        
        // Additional urgent notification mechanisms
        // Could include email, SMS, or security team alerts
    }
    
    /**
     * Execute additional security measures for high threats
     */
    private suspend fun executeAdditionalSecurityMeasures(fusionResult: FusionResult) {
        try {
            // Invalidate all active sessions
            invalidateActiveSessions()
            
            // Clear sensitive cached data
            clearSensitiveCaches()
            
            // Log detailed forensic information
            logForensicData(fusionResult)
            
        } catch (e: Exception) {
            Log.e("DecisionEngine", "Error executing additional security measures: ${e.message}")
        }
    }
    
    /**
     * Invalidate all active sessions
     */
    private suspend fun invalidateActiveSessions() {
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("user_sessions", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            
            // In a real app, you would also invalidate server-side sessions
            Log.i("FraudGuard", "All active sessions invalidated")
        }
    }
    
    /**
     * Clear sensitive cached data
     */
    private suspend fun clearSensitiveCaches() {
        withContext(Dispatchers.IO) {
            // Clear sensitive preferences
            val sensitivePrefs = listOf(
                "user_credentials", "biometric_data", "payment_info", 
                "personal_data", "location_history"
            )
            
            sensitivePrefs.forEach { prefName ->
                context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                    .edit().clear().apply()
            }
            
            Log.i("FraudGuard", "Sensitive caches cleared")
        }
    }
    
    /**
     * Log detailed forensic data for investigation
     */
    private suspend fun logForensicData(fusionResult: FusionResult) {
        withContext(Dispatchers.IO) {
            try {
                val forensicData = mutableMapOf<String, Any>()
                
                forensicData["timestamp"] = fusionResult.timestamp
                forensicData["threat_level"] = fusionResult.threatLevel.name
                forensicData["overall_score"] = fusionResult.overallScore
                forensicData["recommended_action"] = fusionResult.recommendedAction
                
                // Detailed agent information
                fusionResult.agentScores.forEach { (agentId, result) ->
                    forensicData["${agentId}_score"] = result.score
                    forensicData["${agentId}_confidence"] = result.confidence
                    forensicData["${agentId}_details"] = result.details.toString()
                }
                
                // System information
                forensicData["device_info"] = getDeviceInfo()
                forensicData["app_version"] = getAppVersion()
                forensicData["system_time"] = System.currentTimeMillis()
                
                // Save forensic data
                val prefs = context.getSharedPreferences("forensic_logs", Context.MODE_PRIVATE)
                val forensicJson = forensicData.entries.joinToString(",") { "${it.key}:${it.value}" }
                
                val currentForensics = prefs.getStringSet("forensic_entries", mutableSetOf()) ?: mutableSetOf()
                currentForensics.add("${fusionResult.timestamp}|$forensicJson")
                
                // Keep only last 100 forensic entries
                if (currentForensics.size > 100) {
                    val sortedForensics = currentForensics.sorted()
                    val trimmedForensics = sortedForensics.takeLast(100).toSet()
                    currentForensics.clear()
                    currentForensics.addAll(trimmedForensics)
                }
                
                prefs.edit().putStringSet("forensic_entries", currentForensics).apply()
                
                Log.i("FraudGuard", "Forensic data logged for investigation")
                
            } catch (e: Exception) {
                Log.e("DecisionEngine", "Error logging forensic data: ${e.message}")
            }
        }
    }
    
    /**
     * Get device information for forensics
     */
    private fun getDeviceInfo(): String {
        return "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                "(${android.os.Build.VERSION.RELEASE})"
    }
    
    /**
     * Get app version for forensics
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.versionName} (${packageInfo.versionCode})"
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    /**
     * Record decision in history
     */
    private fun recordDecision(
        fusionResult: FusionResult, 
        action: String, 
        success: Boolean, 
        errorMessage: String? = null
    ) {
        val record = DecisionRecord(
            timestamp = fusionResult.timestamp,
            threatLevel = fusionResult.threatLevel,
            overallScore = fusionResult.overallScore,
            action = action,
            agentsInvolved = fusionResult.agentScores.keys,
            success = success,
            errorMessage = errorMessage
        )
        
        decisionHistory.add(record)
        if (decisionHistory.size > maxHistorySize) {
            decisionHistory.removeAt(0)
        }
    }
    
    /**
     * Set callback for re-authentication required
     */
    fun setOnReauthenticationRequired(callback: (ThreatLevel, String) -> Unit) {
        onReauthenticationRequired = callback
    }
    
    /**
     * Set callback for account locked
     */
    fun setOnAccountLocked(callback: (String) -> Unit) {
        onAccountLocked = callback
    }
    
    /**
     * Set callback for threat detected
     */
    fun setOnThreatDetected(callback: (FusionResult) -> Unit) {
        onThreatDetected = callback
    }
    
    /**
     * Check if account is currently locked
     */
    fun isAccountLocked(): Boolean {
        val prefs = context.getSharedPreferences("security_state", Context.MODE_PRIVATE)
        return prefs.getBoolean("account_locked", false)
    }
    
    /**
     * Unlock account (admin function)
     */
    fun unlockAccount(adminReason: String) {
        val prefs = context.getSharedPreferences("security_state", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("account_locked", false)
            .putLong("unlock_timestamp", System.currentTimeMillis())
            .putString("unlock_reason", adminReason)
            .apply()
        
        Log.i("FraudGuard", "Account unlocked: $adminReason")
    }
    
    /**
     * Get decision history
     */
    fun getDecisionHistory(limit: Int = 50): List<DecisionRecord> {
        return decisionHistory.takeLast(limit)
    }
    
    /**
     * Get decision statistics
     */
    fun getDecisionStats(): Map<String, Any> {
        val recentDecisions = decisionHistory.filter { 
            System.currentTimeMillis() - it.timestamp < 3600000 // Last hour
        }
        
        return mapOf(
            "total_decisions" to decisionHistory.size,
            "recent_decisions" to recentDecisions.size,
            "success_rate" to if (recentDecisions.isNotEmpty()) {
                recentDecisions.count { it.success }.toFloat() / recentDecisions.size
            } else 0f,
            "threat_level_distribution" to recentDecisions.groupBy { it.threatLevel }.mapValues { it.value.size },
            "action_distribution" to recentDecisions.groupBy { it.action }.mapValues { it.value.size },
            "account_locked" to isAccountLocked()
        )
    }
}