# FraudGuard

Modular fraud/anomaly detection for Android. Combine multiple behavioral agents into a weighted fusion score that drives a decision engine.

## Modules
- Touch Agent: gestures anomaly detection
- Typing Agent: keystroke timing analysis
- Usage Agent: app usage patterns
- Movement Agent: accelerometer/GPS patterns
- Honeypot Agent: fake endpoint trap (via OkHttp interceptor)

## Decisions
- LOG (low)
- REAUTH (medium)
- LOCK (high)

## Install
Add the module to your Android project (as a Gradle module or library). Ensure Kotlin coroutines and OkHttp are available.

Gradle (root-level):
```gradle
repositories { mavenCentral() }
```

App module dependencies:
```gradle
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
```

## Honeypot Interceptor
Register to your OkHttpClient.
```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(HoneypotInterceptor())
    .build()
```

## Usage
Construct `FraudGuard` with optional custom agents and `FusionConfig` weights/thresholds.
```kotlin
val fraudGuard = FraudGuard(
    fusionConfig = FusionConfig(
        agentWeights = mapOf(
            "TouchAgent" to 0.25,
            "TypingAgent" to 0.25,
            "UsageAgent" to 0.2,
            "MovementAgent" to 0.2,
            "HoneypotAgent" to 0.1
        ),
        lowThreshold = 0.35,
        highThreshold = 0.75,
        normalizeWeights = true
    )
)
```

Collect signals into `AgentContext` and evaluate.
```kotlin
val context = AgentContext(
    sessionId = "abc-123",
    userId = "user-42",
    touchEvents = listOf(),
    typingEvents = listOf(),
    appUsageEvents = listOf(),
    movementSamples = listOf(),
    request = RequestMeta(url = "https://api.example.com/v1/account", method = "GET")
)

val outcome = withContext(Dispatchers.Default) { fraudGuard.evaluate(context) }
when (outcome.decision) {
    Decision.LOG -> Log.i("FraudGuard", "LOG score=${outcome.totalScore}")
    Decision.REAUTH -> promptReauth()
    Decision.LOCK -> lockAccount()
}
```

## Notes
- Each agent returns a score in [0, 1]. You can replace stubs with real models.
- Weights may be normalized; tune thresholds per your risk appetite.
- Ensure user privacy and obtain consent per applicable laws.