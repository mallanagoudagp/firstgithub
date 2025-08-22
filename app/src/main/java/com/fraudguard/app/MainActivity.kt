package com.fraudguard.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fraudguard.app.agents.*
import com.fraudguard.app.core.FraudAgent
import com.fraudguard.app.core.ThreatLevel
import com.fraudguard.app.fusion.FusionAgent
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    // UI Components
    private lateinit var statusTextView: TextView
    private lateinit var threatLevelTextView: TextView
    private lateinit var overallScoreTextView: TextView
    private lateinit var agentStatusContainer: LinearLayout
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var testButton: Button
    private lateinit var logsTextView: TextView
    private lateinit var scrollView: ScrollView
    
    // Fraud detection components
    private lateinit var touchAgent: TouchAgent
    private lateinit var typingAgent: TypingAgent
    private lateinit var usageAgent: UsageAgent
    private lateinit var movementAgent: MovementAgent
    private lateinit var honeypotAgent: HoneypotAgent
    private lateinit var fusionAgent: FusionAgent
    
    private var isMonitoring = false
    private val logMessages = mutableListOf<String>()
    
    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 1001
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BODY_SENSORS
        )
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initializeViews()
        initializeAgents()
        setupFusionAgent()
        setupEventListeners()
        
        // Request permissions
        requestPermissions()
        
        addLog("FraudGuard initialized - Ready to start monitoring")
    }
    
    private fun initializeViews() {
        statusTextView = findViewById(R.id.statusTextView)
        threatLevelTextView = findViewById(R.id.threatLevelTextView)
        overallScoreTextView = findViewById(R.id.overallScoreTextView)
        agentStatusContainer = findViewById(R.id.agentStatusContainer)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        testButton = findViewById(R.id.testButton)
        logsTextView = findViewById(R.id.logsTextView)
        scrollView = findViewById(R.id.scrollView)
        
        // Initial UI state
        updateUI()
    }
    
    private fun initializeAgents() {
        touchAgent = TouchAgent(this, 0.25f)
        typingAgent = TypingAgent(this, 0.20f)
        usageAgent = UsageAgent(this, 0.15f)
        movementAgent = MovementAgent(this, 0.20f)
        honeypotAgent = HoneypotAgent(this, 0.20f)
    }
    
    private fun setupFusionAgent() {
        val agents: List<FraudAgent> = listOf(
            touchAgent,
            typingAgent,
            usageAgent,
            movementAgent,
            honeypotAgent
        )
        
        fusionAgent = FusionAgent(this, agents)
        
        // Note: DecisionEngine callbacks will be set up after fusion agent is started
        // since DecisionEngine is private within FusionAgent
    }
    
    private fun setupEventListeners() {
        startButton.setOnClickListener {
            startMonitoring()
        }
        
        stopButton.setOnClickListener {
            stopMonitoring()
        }
        
        testButton.setOnClickListener {
            runTestScenario()
        }
        
        // Monitor fusion results
        lifecycleScope.launch {
            fusionAgent.getFusionResultFlow()
                .onEach { result ->
                    runOnUiThread {
                        updateThreatDisplay(result)
                        updateAgentStatus()
                    }
                }
                .launchIn(this)
        }
    }
    
    private fun startMonitoring() {
        if (isMonitoring) return
        
        lifecycleScope.launch {
            try {
                // Start fusion agent (which starts all individual agents)
                fusionAgent.startFusion()
                
                // Start usage monitoring
                usageAgent.onActivityChanged("MainActivity")
                
                isMonitoring = true
                updateUI()
                addLog("Monitoring started - All agents active")
                
            } catch (e: Exception) {
                addLog("Error starting monitoring: ${e.message}")
                Log.e("MainActivity", "Error starting monitoring", e)
            }
        }
    }
    
    private fun stopMonitoring() {
        if (!isMonitoring) return
        
        lifecycleScope.launch {
            try {
                fusionAgent.stopFusion()
                isMonitoring = false
                updateUI()
                addLog("Monitoring stopped - All agents deactivated")
                
            } catch (e: Exception) {
                addLog("Error stopping monitoring: ${e.message}")
                Log.e("MainActivity", "Error stopping monitoring", e)
            }
        }
    }
    
    private fun runTestScenario() {
        if (!isMonitoring) {
            addLog("Start monitoring first to run test scenarios")
            return
        }
        
        lifecycleScope.launch {
            addLog("Running test scenario...")
            
            // Test touch anomaly
            simulateTouchAnomalies()
            
            // Test typing anomaly
            simulateTypingAnomalies()
            
            // Test honeypot trigger
            simulateHoneypotTrigger()
            
            // Test usage anomaly
            simulateUsageAnomalies()
            
            addLog("Test scenario completed")
        }
    }
    
    private fun simulateTouchAnomalies() {
        // Simulate unusual touch patterns
        val event = MotionEvent.obtain(
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            MotionEvent.ACTION_DOWN,
            100f, 100f, 0
        )
        touchAgent.onTouchEvent(event)
        event.recycle()
        
        addLog("Simulated touch anomaly")
    }
    
    private fun simulateTypingAnomalies() {
        // Simulate rapid keystrokes (bot-like behavior)
        for (i in 0..10) {
            val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A + i % 26)
            typingAgent.onKeyEvent(keyEvent)
            
            val keyUpEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A + i % 26)
            typingAgent.onKeyEvent(keyUpEvent)
        }
        
        addLog("Simulated typing anomaly (rapid keystrokes)")
    }
    
    private fun simulateHoneypotTrigger() {
        // Simulate honeypot field interaction
        honeypotAgent.onHiddenFieldInteraction("email_confirm", "bot@example.com", "Suspicious Bot/1.0")
        
        // Simulate fake endpoint access
        honeypotAgent.onFakeEndpointAccess("/api/admin/users", "GET", "Automated Scanner/2.0")
        
        addLog("Simulated honeypot triggers")
    }
    
    private fun simulateUsageAnomalies() {
        // Simulate rapid navigation
        usageAgent.onActivityChanged("LoginActivity")
        usageAgent.onActivityChanged("ProfileActivity")
        usageAgent.onActivityChanged("SettingsActivity")
        usageAgent.onActivityChanged("MainActivity")
        
        // Simulate unusual feature usage
        for (i in 0..20) {
            usageAgent.onFeatureUsed("suspicious_feature_$i")
        }
        
        addLog("Simulated usage anomalies")
    }
    
    private fun updateUI() {
        statusTextView.text = if (isMonitoring) "MONITORING ACTIVE" else "MONITORING INACTIVE"
        statusTextView.setTextColor(
            ContextCompat.getColor(
                this,
                if (isMonitoring) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            )
        )
        
        startButton.isEnabled = !isMonitoring
        stopButton.isEnabled = isMonitoring
        testButton.isEnabled = isMonitoring
        
        updateAgentStatus()
    }
    
    private fun updateAgentStatus() {
        agentStatusContainer.removeAllViews()
        
        val agents = listOf(
            "TouchAgent" to touchAgent.isActive,
            "TypingAgent" to typingAgent.isActive,
            "UsageAgent" to usageAgent.isActive,
            "MovementAgent" to movementAgent.isActive,
            "HoneypotAgent" to honeypotAgent.isActive
        )
        
        agents.forEach { (name, isActive) ->
            val statusView = TextView(this)
            statusView.text = "$name: ${if (isActive) "ACTIVE" else "INACTIVE"}"
            statusView.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (isActive) android.R.color.holo_green_dark else android.R.color.holo_red_dark
                )
            )
            statusView.setPadding(0, 8, 0, 8)
            agentStatusContainer.addView(statusView)
        }
    }
    
    private fun updateThreatDisplay(fusionResult: com.fraudguard.app.core.FusionResult) {
        threatLevelTextView.text = "Threat Level: ${fusionResult.threatLevel.name}"
        overallScoreTextView.text = "Overall Score: ${String.format("%.3f", fusionResult.overallScore)}"
        
        val color = when (fusionResult.threatLevel) {
            ThreatLevel.LOW -> android.R.color.holo_green_dark
            ThreatLevel.MEDIUM -> android.R.color.holo_orange_dark
            ThreatLevel.HIGH -> android.R.color.holo_red_dark
        }
        
        threatLevelTextView.setTextColor(ContextCompat.getColor(this, color))
        overallScoreTextView.setTextColor(ContextCompat.getColor(this, color))
    }
    
    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val logMessage = "[$timestamp] $message"
        
        logMessages.add(logMessage)
        if (logMessages.size > 100) {
            logMessages.removeAt(0)
        }
        
        runOnUiThread {
            logsTextView.text = logMessages.joinToString("\n")
            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
        
        Log.i("FraudGuard", logMessage)
    }
    
    private fun showReauthenticationDialog(threatLevel: ThreatLevel, message: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Security Verification Required")
            .setMessage(message)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton("Authenticate") { _, _ ->
                // In a real app, you would implement actual authentication
                addLog("User authentication requested (${threatLevel.name})")
                Toast.makeText(this, "Authentication would be performed here", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel") { _, _ ->
                addLog("User cancelled authentication")
            }
            .setCancelable(false)
            .show()
    }
    
    private fun showAccountLockedDialog(reason: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Account Locked")
            .setMessage("Your account has been locked due to suspicious activity.\n\nReason: $reason\n\nPlease contact support to unlock your account.")
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton("Contact Support") { _, _ ->
                // In a real app, you would open support contact
                addLog("User requested support contact")
                Toast.makeText(this, "Support contact would be opened here", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close App") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }
    
    private fun requestPermissions() {
        val permissionsToRequest = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSIONS_REQUEST_CODE
            )
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            val deniedPermissions = permissions.filterIndexed { index, _ ->
                grantResults[index] != PackageManager.PERMISSION_GRANTED
            }
            
            if (deniedPermissions.isNotEmpty()) {
                addLog("Some permissions denied: ${deniedPermissions.joinToString(", ")}")
                Toast.makeText(
                    this,
                    "Some features may not work without permissions",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                addLog("All permissions granted")
            }
        }
    }
    
    // Touch event handling for TouchAgent
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event?.let {
            if (isMonitoring) {
                touchAgent.onTouchEvent(it)
            }
        }
        return super.onTouchEvent(event)
    }
    
    // Key event handling for TypingAgent
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        event?.let {
            if (isMonitoring) {
                typingAgent.onKeyEvent(it)
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        event?.let {
            if (isMonitoring) {
                typingAgent.onKeyEvent(it)
            }
        }
        return super.onKeyUp(keyCode, event)
    }
    
    override fun onResume() {
        super.onResume()
        if (isMonitoring) {
            usageAgent.onActivityChanged("MainActivity")
        }
    }
    
    override fun onPause() {
        super.onPause()
        if (isMonitoring) {
            usageAgent.endCurrentSession()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isMonitoring) {
            lifecycleScope.launch {
                stopMonitoring()
            }
        }
    }
}