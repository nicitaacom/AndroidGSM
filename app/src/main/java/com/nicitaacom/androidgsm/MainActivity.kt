package com.nicitaacom.androidgsm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.app.Activity
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.telecom.TelecomManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toUri
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var toggleButton: Button
    private lateinit var testAudioButton: Button
    private lateinit var defaultDialerButton: Button
    private lateinit var copyLogsButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var versionTextView: TextView
    private var hasSimAvailable = true
    private var hasInternetConnection = true
    private var hasRequiredPermissions = false
    private var pendingAllowStartWithoutSim = false
    private var pendingStartAudioTest = false
    private var isTestAudioActive = false
    private val meetsMinAndroid = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q // API 29
    private var isServiceAudioActive = false

    private val logBuffer = StringBuilder()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var originalBrightness = -1f
    private var originalScreenTimeout: Long = -1
    private var hasCallPermissions = false

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val REQUEST_ROLE_DIALER = 200
        private var instance: WeakReference<MainActivity>? = null

        fun log(message: String) {
            instance?.get()?.addLog(message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Global crash handler - copies last 50 logs to clipboard before dying
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashInfo = "CRASH: ${throwable.message}\n${throwable.stackTraceToString().take(500)}"
                logBuffer.append("\n[CRASH] $crashInfo\n")
                val last50 = logBuffer.lines().filter { it.isNotBlank() }.takeLast(50).joinToString("\n")
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("GSM Crash Logs", last50))
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        instance = WeakReference(this)

        logTextView = findViewById(R.id.logTextView)
        scrollView = findViewById(R.id.scrollView)
        toggleButton = findViewById(R.id.toggleButton)
        testAudioButton = findViewById(R.id.testAudioButton)
        defaultDialerButton = findViewById(R.id.defaultDialerButton)
        copyLogsButton = findViewById(R.id.copyLogsButton)
        statusTextView = findViewById(R.id.statusTextView)
        versionTextView = findViewById(R.id.versionTextView)
        versionTextView.text = "outreach-tool.com | v.${BuildConfig.VERSION_NAME}"
        evaluateVersionFreshness()

        toggleButton.setOnClickListener {
            when {
                isServiceAudioActive -> stopServiceAudioInput()
                !hasSimAvailable -> addLog("❌ SERVICE mode unavailable: no SIM card detected")
                else -> {
                    pendingStartAudioTest = false
                    requestPermissionsAndStart()
                }
            }
        }

        testAudioButton.setOnClickListener {
            when {
                isTestAudioActive -> stopTestAudio()
                isServiceAudioActive -> addLog("⚠️ Stop SERVICE first before starting TEST")
                else -> {
                    pendingStartAudioTest = true
                    addLog("🎧 Test audio requested")
                    requestPermissionsAndStart(allowWithoutSim = true)
                }
            }
        }

        defaultDialerButton.setOnClickListener {
            requestDefaultDialer()
        }

        copyLogsButton.setOnClickListener {
            copyLastLogsToClipboard()
        }

        checkNetworkAvailability()
        requestPermissionsOnLaunchIfNeeded()
        updateButtonState()

        addLog("App started")
        addLog("Android version: ${Build.VERSION.RELEASE}")
        if (!meetsMinAndroid) addLog("❌ Android ${Build.VERSION.RELEASE} unsupported - SERVICE mode requires Android 10+ (API 29)")
        addLog("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        val isRooted = RootUtils.isRooted()
        addLog(if (isRooted) "✅ Root access detected - REMOTE_SUBMIX audio output capture can be attempted" else "⚠️ Root access not detected by app checks - fallback to mic capture")

        checkServiceStatus()

        if (hasPhoneStatePermission()) {
            try {
                loadSimSelection()
            } catch (se: SecurityException) {
                addLog("SecurityException reading SIM info: ${se.message}")
            }
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        addLog("Screen will stay on while app is active")

    }

    override fun onResume() {
        super.onResume()
        checkNetworkAvailability()
        updateButtonState()
        // 1. Re-check actual running state - handles crash/restart scenario
        checkActualServiceState()
    }

    private fun checkActualServiceState() {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        val isActuallyRunning = manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == GsmService::class.java.name }

        if (!isActuallyRunning && (isTestAudioActive || isServiceAudioActive)) {
            addLog("⚠️ Service not running - resetting state")
            isTestAudioActive = false
            isServiceAudioActive = false
            pendingStartAudioTest = false
        }
        updateButtonState()
    }



    private fun checkServiceStatus() {
        statusTextView.text = "Status: Ready"
        updateButtonState()
    }

    private fun updateButtonState() {
        val controlsEnabled = hasInternetConnection && hasRequiredPermissions && meetsMinAndroid
        if (!controlsEnabled) {
            toggleButton.text = if (!hasInternetConnection) "NO INTERNET" else "PERMISSIONS REQUIRED"
            toggleButton.isEnabled = false
            toggleButton.alpha = 0.5f
            testAudioButton.text = "TEST AUDIO"
            testAudioButton.isEnabled = false
            testAudioButton.alpha = 0.5f
            defaultDialerButton.isEnabled = false
            defaultDialerButton.alpha = 0.5f
            copyLogsButton.isEnabled = true
            copyLogsButton.alpha = 1f
            statusTextView.text = when {
                !meetsMinAndroid -> "Status: Android 10+ required"
                !hasInternetConnection -> "Status: No Internet"
                else -> "Status: Waiting for permissions"
            }
            return
        }

        defaultDialerButton.isEnabled = true
        defaultDialerButton.alpha = 1f
        copyLogsButton.isEnabled = true
        copyLogsButton.alpha = 1f

        when {
            // 1. TEST active — only STOP TEST allowed
            isTestAudioActive -> {
                toggleButton.text = if (hasSimAvailable) "START SERVICE" else "NO SIM DETECTED"
                toggleButton.isEnabled = false
                toggleButton.alpha = 0.5f
                toggleButton.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_green))
                testAudioButton.text = "STOP TEST"
                testAudioButton.isEnabled = true
                testAudioButton.alpha = 1f
                testAudioButton.setBackgroundColor(ContextCompat.getColor(this, R.color.error_red))
                statusTextView.text = "Status: Test Audio Active"
            }
            // 2. SERVICE active — only STOP SERVICE allowed
            isServiceAudioActive -> {
                toggleButton.text = "STOP SERVICE"
                toggleButton.isEnabled = true
                toggleButton.alpha = 1f
                toggleButton.setBackgroundColor(ContextCompat.getColor(this, R.color.error_red))
                testAudioButton.text = "TEST AUDIO"
                testAudioButton.isEnabled = false
                testAudioButton.alpha = 0.5f
                testAudioButton.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_green))
                statusTextView.text = "Status: Service Active"
            }
            // 3. Idle, no SIM
            !hasSimAvailable -> {
                toggleButton.text = "NO SIM DETECTED"
                toggleButton.isEnabled = false
                toggleButton.alpha = 0.5f
                toggleButton.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_green))
                testAudioButton.text = "TEST AUDIO"
                testAudioButton.isEnabled = true
                testAudioButton.alpha = 1f
                testAudioButton.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_green))
                statusTextView.text = "Status: No SIM"
            }
            // 4. Idle, SIM available
            else -> {
                toggleButton.text = if (hasCallPermissions) "START SERVICE" else "CALL PERMISSIONS REQUIRED"
                toggleButton.isEnabled = hasCallPermissions
                toggleButton.alpha = if (hasCallPermissions) 1f else 0.5f
                toggleButton.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_green))
                testAudioButton.text = "TEST AUDIO"
                testAudioButton.isEnabled = true
                testAudioButton.alpha = 1f
                testAudioButton.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_green))
                statusTextView.text = if (hasCallPermissions) "Status: Inactive" else "Status: Missing call permissions"
            }
        }
    }

    private fun requestPermissionsAndStart(allowWithoutSim: Boolean = false) {
        pendingAllowStartWithoutSim = allowWithoutSim
        val permissions = mutableListOf<String>().apply {
            if (!allowWithoutSim) {
                add(Manifest.permission.CALL_PHONE)
                add(Manifest.permission.READ_PHONE_NUMBERS)
                add(Manifest.permission.READ_PHONE_STATE)
            }
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.ANSWER_PHONE_CALLS)
            add(Manifest.permission.MODIFY_AUDIO_SETTINGS)
            add(Manifest.permission.INTERNET)
            add(Manifest.permission.ACCESS_NETWORK_STATE)
            add(Manifest.permission.WAKE_LOCK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(Manifest.permission.FOREGROUND_SERVICE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(Manifest.permission.USE_FULL_SCREEN_INTENT)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            addLog("Requesting ${missing.size} permissions...")
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            hasRequiredPermissions = true
            startService(allowWithoutSim)
            // 1. Dispatch audio action AFTER service is started
            if (pendingStartAudioTest) dispatchTestAudioRequest()
            else dispatchServiceAudioInputRequest()
            pendingAllowStartWithoutSim = false
        }
    }

    private fun requestDefaultDialer() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager != null && !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    startActivityForResult(intent, REQUEST_ROLE_DIALER)
                    addLog("Requesting dialer role via RoleManager")
                    return
                } else if (roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true) {
                    addLog("✅ Already default dialer")
                    return
                }
            }
            // 1. Direct TelecomManager fallback — no custom activity needed
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            }
            startActivity(intent)
            addLog("Opening system default dialer prompt")
        } catch (error: Exception) {
            addLog("Error requesting default dialer: ${error.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ROLE_DIALER) {
            if (resultCode == Activity.RESULT_OK) addLog("✅ App set as default dialer")
            else addLog("❌ Default dialer request declined or failed")
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            addLog("Requesting battery optimization exemption...")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            }
            startActivity(intent)
        } else {
            addLog("Battery optimization already exempted")
        }
    }

    private fun startService(allowWithoutSim: Boolean = false) {
        try {
            if (!hasSimAvailable && !allowWithoutSim) {
                addLog("❌ Cannot start: no SIM")
                updateButtonState()
                return
            }
            val intent = Intent(this, GsmService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            addLog("Service process started")
            // 1. Dispatch is handled by caller — never auto-dispatch here
        } catch (error: Exception) {
            addLog("ERROR starting service: ${error.message}")
        }
    }

    private fun stopService() {
        try {
            addLog("Stopping GSM Gateway Service...")
            val intent = Intent(this, GsmService::class.java)
            stopService(intent)

            isTestAudioActive = false
            pendingStartAudioTest = false
            updateButtonState()
            addLog("Service stopped successfully!")

            if (originalScreenTimeout != -1L && Settings.System.canWrite(this)) {
                try {
                    Settings.System.putLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, originalScreenTimeout)
                    addLog("Restored original screen timeout")
                } catch (e: Exception) {
                    addLog("Error restoring screen timeout: ${e.message}")
                }
                originalScreenTimeout = -1L
            }
        } catch (error: Exception) {
            addLog("ERROR stopping service: ${error.message}")
        }
    }

    private fun stopServiceAudioInput() {
        val intent = Intent(this, GsmService::class.java).apply {
            action = GsmService.ACTION_STOP_SERVICE_DUPLEX_OUTPUT_TO_SERVER_AND_SERVER_TO_INPUT
        }
        startService(intent)
        isServiceAudioActive = false
        isTestAudioActive = false
        updateButtonState()
        addLog("🛑 Service audio stopped")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                addLog("All permissions granted!")
                hasRequiredPermissions = true
                hasCallPermissions = true
                requestBatteryOptimizationExemption()
                try { loadSimSelection() } catch (error: Exception) { addLog("SIM load error: ${error.message}") }
                startService(pendingAllowStartWithoutSim)
                // 1. Dispatch after service starts
                if (pendingStartAudioTest) dispatchTestAudioRequest()
                else if (hasSimAvailable || pendingAllowStartWithoutSim) dispatchServiceAudioInputRequest()
                pendingAllowStartWithoutSim = false
            } else {
                hasRequiredPermissions = false
                pendingAllowStartWithoutSim = false
                pendingStartAudioTest = false
                addLog("ERROR: Some permissions were denied")
                val deniedPermissions = permissions.filterIndexed { index, _ ->
                    grantResults[index] != PackageManager.PERMISSION_GRANTED
                }
                addLog("Denied: ${deniedPermissions.joinToString()}")
            }
            updateButtonState()
        }
    }

    private fun dispatchTestAudioRequest() {
        val intent = Intent(this, GsmService::class.java).apply {
            action = GsmService.ACTION_START_TEST_DUPLEX_MIC_TO_SERVER_AND_SERVER_TO_OUTPUT
        }
        startService(intent)
        isTestAudioActive = true
        isServiceAudioActive = false
        pendingStartAudioTest = false
        updateButtonState()
        addLog("🎧 TEST audio started")
    }


    private fun dispatchServiceAudioInputRequest() {
        val intent = Intent(this, GsmService::class.java).apply {
            action = GsmService.ACTION_START_SERVICE_DUPLEX_OUTPUT_TO_SERVER_AND_SERVER_TO_INPUT
        }
        startService(intent)
        isServiceAudioActive = true
        isTestAudioActive = false
        updateButtonState()
        addLog("📞 SERVICE audio started")
    }

    private fun stopTestAudio() {
        val intent = Intent(this, GsmService::class.java).apply {
            action = GsmService.ACTION_STOP_TEST_DUPLEX_MIC_TO_SERVER_AND_SERVER_TO_OUTPUT
        }
        startService(intent)
        isTestAudioActive = false
        isServiceAudioActive = false
        pendingStartAudioTest = false
        updateButtonState()
        addLog("🛑 Test audio stopped")
    }

    fun addLog(message: String) {
        runOnUiThread {
            val compactMessage = sanitizeLogMessage(message)
            val timestamp = dateFormat.format(Date())
            val logEntry = "[$timestamp] $compactMessage\n"
            logBuffer.append(logEntry)

            val lines = logBuffer.lines()
            if (lines.size > 500) {
                logBuffer.clear()
                logBuffer.append(lines.takeLast(500).joinToString("\n"))
            }

            logTextView.text = logBuffer.toString()
            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun copyLastLogsToClipboard() {
        val lines = logBuffer.lines().filter { it.isNotBlank() }
        // Filter to show only important logs (errors, state changes, audio events)
        val importantLogs = lines.filter { line ->
            val lower = line.lowercase()
            lower.contains("error") || lower.contains("call") || lower.contains("connected") ||
                    lower.contains("warning") || lower.contains("fatal") || lower.contains("ended") ||
                    lower.contains("started") || lower.contains("audio capture") || lower.contains("websocket") ||
                    lower.contains("dtmf") || lower.contains("service") || lower.contains("permission")
        }
        val last30 = importantLogs.takeLast(30).joinToString("\n")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("GSM Logs", last30))
        addLog("📋 Copied ${importantLogs.takeLast(30).size} important logs to clipboard")
    }

    private fun sanitizeLogMessage(message: String): String {
        val trimmed = if (message.length > 350) "${message.take(350)}… [trimmed]" else message
        val base64Regex = Regex("[A-Za-z0-9+/]{120,}={0,2}")
        return trimmed.replace(base64Regex, "[base64-audio-trimmed]")
    }

    private fun hasPhoneStatePermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissionsOnLaunchIfNeeded() {
        val required = listOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        val missing = required.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            hasRequiredPermissions = missing.isEmpty()
            hasCallPermissions = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        if (missing.isNotEmpty()) {
            addLog("Permissions required before using controls: ${missing.joinToString()}")
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else if (hasPhoneStatePermission()) {
            loadSimSelection()
        }
    }

    private fun checkNetworkAvailability() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(network)
        val wasConnected = hasInternetConnection
        hasInternetConnection = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        if (!hasInternetConnection && wasConnected) {
            addLog("❌ No internet connection. Controls are disabled until internet is available.")
        } else if (hasInternetConnection && !wasConnected) {
            addLog("✅ Internet connection restored")
        }
    }

    private fun evaluateVersionFreshness() {
        val dateToken = Regex("^(\\d{2}-\\d{2}-\\d{2})").find(BuildConfig.VERSION_NAME)?.groupValues?.get(1) ?: return
        try {
            val parser = SimpleDateFormat("yy-MM-dd", Locale.US)
            val buildDate = parser.parse(dateToken) ?: return
            val nowToken = parser.format(Date())
            val today = parser.parse(nowToken) ?: return
            if (buildDate.before(today)) {
                versionTextView.setTextColor(ContextCompat.getColor(this, R.color.error_red))
                versionTextView.text = "outreach-tool.com | v.${BuildConfig.VERSION_NAME} | OUTDATED"
                addLog("⚠️ App build appears outdated (build date: $dateToken)")
            }
        } catch (_: Exception) {
            // Ignore unknown version format.
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance?.get() == this) {
            instance = null
        }
        if (originalScreenTimeout != -1L && Settings.System.canWrite(this)) {
            try {
                Settings.System.putLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, originalScreenTimeout)
                addLog("Restored timeout on destroy")
            } catch (e: Exception) {
                addLog("Error restoring timeout on destroy: ${e.message}")
            }
            originalScreenTimeout = -1L
        }
    }

    private fun loadSimSelection() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            addLog("⚠️ READ_PHONE_STATE permission missing - SIM selection disabled")
            return
        }

        val subMgr = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val subs = subMgr.activeSubscriptionInfoList ?: emptyList()

        if (subs.isEmpty()) {
            hasSimAvailable = false
            addLog("❌ No SIM cards detected - GSM calling is unavailable")
            findViewById<View>(R.id.sim_selection_container).visibility = View.GONE
            updateButtonState()
            return
        }

        hasSimAvailable = true
        updateButtonState()

        if (subs.size < 2) {
            addLog("Single SIM detected - no selection UI shown")
            findViewById<View>(R.id.sim_selection_container).visibility = View.GONE
            return
        }

        findViewById<View>(R.id.sim_selection_container).visibility = View.VISIBLE

        val radioGroup = findViewById<RadioGroup>(R.id.sim_radio_group)
        radioGroup.removeAllViews()

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val selectedSubId = prefs.getInt("selected_sim", subs[0].subscriptionId)

        for (sub in subs) {
            val radio = RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(R.string.sim_label, sub.simSlotIndex + 1, sub.carrierName)
                tag = sub.subscriptionId
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                buttonTintList = ResourcesCompat.getColorStateList(resources, R.color.brand_green, null)
                setPadding(16, 16, 16, 16)
            }

            radioGroup.addView(radio)

            if (sub.subscriptionId == selectedSubId) {
                radioGroup.check(radio.id)
                addLog("SIM ${sub.simSlotIndex + 1} (${sub.carrierName}) selected by default")
            }
        }

        radioGroup.setOnCheckedChangeListener { group, checkedId ->
            val selected = group.findViewById<RadioButton>(checkedId).tag as Int
            prefs.edit { putInt("selected_sim", selected) }
            addLog("Selected SIM changed to subId $selected")
        }

        addLog("Dual SIM detected - selection UI shown (${subs.size} SIMs)")
    }
}
