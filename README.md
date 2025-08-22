# FraudGuard Android App

A comprehensive fraud detection system for Android applications that uses multiple behavioral analysis agents to detect suspicious activities and potential security threats.

## Overview

FraudGuard implements a multi-agent fraud detection system that monitors various aspects of user behavior and device interactions to identify anomalous patterns that may indicate fraudulent activity, automated attacks, or unauthorized access.

## Architecture

### Core Components

1. **FraudAgent Interface** - Base interface for all detection agents
2. **FusionAgent** - Central fusion engine that combines scores from all agents
3. **DecisionEngine** - Executes actions based on threat levels
4. **Individual Agents** - Specialized detection modules

### Detection Agents

#### 1. TouchAgent (Weight: 25%)
- **Purpose**: Analyzes touch gesture patterns and interactions
- **Detects**: 
  - Unusual pressure patterns
  - Abnormal gesture velocity
  - Inconsistent touch timing
  - Non-human touch patterns
- **Baseline Metrics**: Pressure, velocity, dwell time, gesture smoothness

#### 2. TypingAgent (Weight: 20%)
- **Purpose**: Monitors keystroke dynamics and typing behavior
- **Detects**: 
  - Abnormal dwell times (key hold duration)
  - Unusual flight times (between key presses)
  - Irregular typing rhythm
  - Automated typing patterns
- **Baseline Metrics**: Dwell time, flight time, typing speed, rhythm variance

#### 3. UsageAgent (Weight: 15%)
- **Purpose**: Tracks app usage patterns and navigation behavior
- **Detects**: 
  - Unusual session durations
  - Abnormal navigation patterns
  - Excessive feature usage
  - Rapid screen transitions
- **Baseline Metrics**: Session duration, navigation speed, feature usage rate

#### 4. MovementAgent (Weight: 20%)
- **Purpose**: Analyzes device movement and location patterns
- **Detects**: 
  - Location spoofing
  - Unusual movement patterns
  - Abnormal accelerometer readings
  - Inconsistent GPS data
- **Baseline Metrics**: Acceleration patterns, movement velocity, location accuracy

#### 5. HoneypotAgent (Weight: 20%)
- **Purpose**: Creates traps to detect automated attacks
- **Detects**: 
  - Hidden form field interactions
  - Access to fake endpoints
  - Suspicious user agents
  - Automated request patterns
- **Traps**: Hidden fields, fake API endpoints, timing traps, behavior traps

## Fusion Logic

### Weighted Scoring System
```kotlin
// Each agent contributes to overall score based on its weight
val overallScore = Σ(agentScore × agentWeight × confidence)
```

### Threat Levels & Actions

| Threat Level | Score Range | Action |
|--------------|-------------|---------|
| **LOW** | 0.0 - 0.3 | Log activity for monitoring |
| **MEDIUM** | 0.3 - 0.6 | Require re-authentication |
| **HIGH** | 0.6 - 1.0 | Lock account immediately |

### Decision Engine Actions

#### Low Threat (Score < 0.3)
- Log suspicious activity
- Continue normal operation
- Monitor for escalation

#### Medium Threat (Score 0.3-0.6)
- Trigger re-authentication
- Type determined by contributing agents:
  - **Biometric**: Touch/Typing anomalies
  - **Location Verification**: Movement anomalies
  - **CAPTCHA + Password**: Network anomalies

#### High Threat (Score > 0.6)
- Immediately lock account
- Invalidate all active sessions
- Clear sensitive cached data
- Log forensic information
- Send urgent notifications

## Features

### Real-time Monitoring
- Continuous behavioral analysis
- Live threat level updates
- Real-time agent status monitoring
- Activity logging with timestamps

### Adaptive Learning
- Baseline adjustment based on normal behavior
- Configurable thresholds
- Historical pattern analysis
- False positive reduction

### Comprehensive Logging
- Detailed activity logs
- Forensic data collection
- Decision history tracking
- Performance metrics

### Security Integration
- Biometric authentication support
- Session management
- Secure data storage
- Privacy-focused design

## Installation & Setup

### Prerequisites
- Android Studio Arctic Fox or later
- Android SDK API Level 24+ (Android 7.0)
- Kotlin 1.9.20+
- Gradle 8.2.0+

### Required Permissions
```xml
<!-- Location tracking for MovementAgent -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

<!-- Sensor access for MovementAgent -->
<uses-permission android:name="android.permission.BODY_SENSORS" />

<!-- Network monitoring for HoneypotAgent -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- Usage statistics for UsageAgent -->
<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" />

<!-- Biometric authentication -->
<uses-permission android:name="android.permission.USE_BIOMETRIC" />
```

### Build Instructions
1. Clone the repository
2. Open in Android Studio
3. Sync Gradle files
4. Build and run on device/emulator

## Usage

### Starting Fraud Detection
```kotlin
// Initialize agents
val agents = listOf(touchAgent, typingAgent, usageAgent, movementAgent, honeypotAgent)
val fusionAgent = FusionAgent(context, agents)

// Start monitoring
fusionAgent.startFusion()
```

### Monitoring Results
```kotlin
// Subscribe to fusion results
fusionAgent.getFusionResultFlow()
    .onEach { result ->
        when (result.threatLevel) {
            ThreatLevel.LOW -> logActivity(result)
            ThreatLevel.MEDIUM -> requireReauth(result)
            ThreatLevel.HIGH -> lockAccount(result)
        }
    }
    .launchIn(scope)
```

### Integration Examples

#### Touch Event Monitoring
```kotlin
override fun onTouchEvent(event: MotionEvent?): Boolean {
    event?.let { touchAgent.onTouchEvent(it) }
    return super.onTouchEvent(event)
}
```

#### Keystroke Monitoring
```kotlin
override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
    event?.let { typingAgent.onKeyEvent(it) }
    return super.onKeyUp(keyCode, event)
}
```

#### Activity Navigation
```kotlin
override fun onResume() {
    super.onResume()
    usageAgent.onActivityChanged(this::class.simpleName ?: "Unknown")
}
```

## Configuration

### Fusion Configuration
```kotlin
val config = FusionConfig(
    lowThreshold = 0.3f,
    mediumThreshold = 0.6f,
    highThreshold = 1.0f,
    timeWindowMs = 300000L, // 5 minutes
    adaptiveLearning = true
)
fusionAgent.updateConfiguration(config)
```

### Agent Weights
```kotlin
// Customize agent weights based on your use case
TouchAgent(context, weight = 0.25f)      // 25% contribution
TypingAgent(context, weight = 0.20f)     // 20% contribution
UsageAgent(context, weight = 0.15f)      // 15% contribution
MovementAgent(context, weight = 0.20f)   // 20% contribution
HoneypotAgent(context, weight = 0.20f)   // 20% contribution
```

## Testing

### Test Scenarios
The app includes built-in test scenarios to verify agent functionality:

1. **Touch Anomaly Test**: Simulates unusual touch patterns
2. **Typing Anomaly Test**: Generates rapid keystroke patterns
3. **Honeypot Trigger Test**: Activates hidden field traps
4. **Usage Anomaly Test**: Creates suspicious navigation patterns

### Running Tests
```kotlin
// Use the test button in MainActivity or call directly
runTestScenario()
```

## Security Considerations

### Data Privacy
- All behavioral data is processed locally
- No sensitive data transmitted to external servers
- Configurable data retention periods
- Secure storage using Android Keystore

### Performance
- Lightweight agent implementations
- Efficient data structures
- Background processing optimization
- Memory-conscious design

### False Positives
- Adaptive learning reduces false positives
- Configurable sensitivity thresholds
- User feedback integration
- Baseline adjustment mechanisms

## Customization

### Adding New Agents
1. Implement the `FraudAgent` interface
2. Add to the agent list in `FusionAgent`
3. Configure appropriate weight
4. Update UI to display agent status

### Custom Decision Logic
```kotlin
class CustomDecisionEngine(context: Context) : DecisionEngine(context) {
    override fun executeDecision(fusionResult: FusionResult) {
        // Custom decision logic
        when (fusionResult.threatLevel) {
            ThreatLevel.MEDIUM -> customReauthFlow(fusionResult)
            ThreatLevel.HIGH -> customLockFlow(fusionResult)
            else -> super.executeDecision(fusionResult)
        }
    }
}
```

## API Reference

### Core Classes
- `FraudAgent`: Base interface for all detection agents
- `FusionAgent`: Central fusion and coordination engine
- `DecisionEngine`: Action execution based on threat levels
- `AnomalyResult`: Individual agent detection result
- `FusionResult`: Combined detection result with recommendations

### Key Methods
- `startFusion()`: Initialize and start all agents
- `stopFusion()`: Stop monitoring and cleanup
- `getAnomalyScore()`: Get current agent anomaly score
- `getFusionResultFlow()`: Subscribe to real-time results

## Contributing

1. Fork the repository
2. Create a feature branch
3. Implement your changes
4. Add tests for new functionality
5. Submit a pull request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Support

For questions, issues, or feature requests, please open an issue on the GitHub repository.

---

**Note**: This is a demonstration implementation for educational purposes. In a production environment, additional security measures, testing, and validation would be required.
