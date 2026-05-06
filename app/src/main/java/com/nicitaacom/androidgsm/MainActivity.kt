package com.nicitaacom.androidgsm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import android.widget.SeekBar
import android.widget.TextView
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
    private lateinit var copyLogsButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var versionTextView: TextView
    private lateinit var micGainSeekBar: SeekBar
    private lateinit var micGainLabel: TextView
    private lateinit var playbackVolSeekBar: SeekBar
    private lateinit var playbackVolLabel: TextView
    private var hasSimAvailable = true
    private var hasInternetConnection = true
    private var hasRequiredPermissions = false
    private var pendingAllowStartWithoutSim = false
    private var pendingStartAudioTest = false
    private var isTestAudioActive = false
    private val meetsMinAndroid = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q // API 29
    private var isServiceAudioActive = false

    private val logBuffer = StringBuilder()
    private val logLock = Any()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var originalBrightness = -1f
    private var originalScreenTimeout: Long = -1
    private var hasCallPermissions = false
    private var logUpdatePending = false

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val TAG = "GSM"
        private var instance: WeakReference<MainActivity>? = null

        fun log(message: String) {
            android.util.Log.d(TAG, message)
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
        copyLogsButton = findViewById(R.id.copyLogsButton)
        statusTextView = findViewById(R.id.statusTextView)
        versionTextView = findViewById(R.id.versionTextView)
        versionTextView.text = "outreach-tool.com | v.${BuildConfig.VERSION_NAME}"
        evaluateVersionFreshness()

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        micGainSeekBar = findViewById(R.id.micGainSeekBar)
        micGainLabel = findViewById(R.id.micGainLabel)
        playbackVolSeekBar = findViewById(R.id.playbackVolSeekBar)
        playbackVolLabel = findViewById(R.id.playbackVolLabel)

        micGainSeekBar.progress = prefs.getInt("mic_gain_progress", 100)
        playbackVolSeekBar.progress = prefs.getInt("playback_vol_progress", 70)
        updateGainLabels()

        micGainSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                updateGainLabels()
                prefs.edit { putInt("mic_gain_progress", progress) }
                AudioWebSocketHandler.micGain = progress / 100f
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        playbackVolSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                updateGainLabels()
                prefs.edit { putInt("playback_vol_progress", progress) }
                AudioWebSocketHandler.playbackGain = progress / 100f
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // Apply saved values immediately
        AudioWebSocketHandler.micGain = micGainSeekBar.progress / 100f
        AudioWebSocketHandler.playbackGain = playbackVolSeekBar.progress / 100f

        // SERVICE starts automatically — toggle is just a manual emergency stop/restart
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

        copyLogsButton.setOnClickListener {
            copyLastLogsToClipboard()
        }

        checkNetworkAvailability()
        registerNetworkCallback()
        requestPermissionsOnLaunchIfNeeded() // auto-starts SERVICE inside once granted
        updateButtonState()

        addLog("App started")
        addLog("Android version: ${Build.VERSION.RELEASE}")
        if (!meetsMinAndroid) addLog("❌ Android ${Build.VERSION.RELEASE} unsupported - SERVICE mode requires Android 10+ (API 29)")
        addLog("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        Thread {
            val isRooted = RootUtils.isRooted()
            addLog(if (isRooted) "✅ Root access detected - REMOTE_SUBMIX audio output capture can be attempted" else "⚠️ Root access not detected by app checks - fallback to mic capture")
        }.start()

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
        checkNetworkAvailability()  // snapshot check on resume; real-time handled by networkCallback
        // Re-check SIM on every resume — subscription list can be empty on first onCreate
        if (hasPhoneStatePermission()) {
            try { loadSimSelection() } catch (_: Exception) {}
        }
        updateButtonState()
        // Re-check actual running state - handles crash/restart scenario
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
            copyLogsButton.isEnabled = true
            copyLogsButton.alpha = 1f
            statusTextView.text = when {
                !meetsMinAndroid -> "Status: Android 10+ required"
                !hasInternetConnection -> "Status: No Internet"
                else -> "Status: Waiting for permissions"
            }
            return
        }

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
            startGsmServiceSafely(intent)
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
        startGsmServiceSafely(intent)
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
        if (!startGsmServiceSafely(intent)) return
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
        if (!startGsmServiceSafely(intent)) return
        isServiceAudioActive = true
        isTestAudioActive = false
        updateButtonState()
        addLog("📞 SERVICE audio started")
    }

    private fun stopTestAudio() {
        val intent = Intent(this, GsmService::class.java).apply {
            action = GsmService.ACTION_STOP_TEST_DUPLEX_MIC_TO_SERVER_AND_SERVER_TO_OUTPUT
        }
        startGsmServiceSafely(intent)
        isTestAudioActive = false
        isServiceAudioActive = false
        pendingStartAudioTest = false
        updateButtonState()
        addLog("🛑 Test audio stopped")
    }

    private fun startGsmServiceSafely(intent: Intent): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            true
        } catch (error: IllegalStateException) {
            addLog("❌ Cannot start service right now: ${error.message}")
            false
        } catch (error: SecurityException) {
            addLog("❌ Service start blocked by permissions: ${error.message}")
            false
        } catch (error: Exception) {
            addLog("❌ Unexpected service start error: ${error.message}")
            false
        }
    }

    fun addLog(message: String) {
        val compactMessage = sanitizeLogMessage(message)
        val timestamp = dateFormat.format(Date())
        val logEntry = "[$timestamp] $compactMessage\n"

        val shouldPost: Boolean
        synchronized(logLock) {
            logBuffer.append(logEntry)
            // Trim to last 300 lines to bound memory — do string work off UI thread here
            val lines = logBuffer.lines()
            if (lines.size > 300) {
                logBuffer.clear()
                logBuffer.append(lines.takeLast(300).joinToString("\n"))
            }
            shouldPost = !logUpdatePending
            if (shouldPost) logUpdatePending = true
        }

        if (!shouldPost) return  // UI update already queued, skip

        runOnUiThread {
            val snapshot = synchronized(logLock) {
                logUpdatePending = false
                logBuffer.toString()
            }
            logTextView.text = snapshot
            val child = scrollView.getChildAt(0) ?: return@runOnUiThread
            val atBottom = scrollView.scrollY + scrollView.height >= child.height - 100
            if (atBottom) scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun copyLastLogsToClipboard() {
        val logs = synchronized(logLock) { logBuffer.toString() }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("GSM Logs", logs))
        addLog("📋 All logs copied to clipboard")
    }

    private fun updateGainLabels() {
        micGainLabel.text = "%.1fx".format(micGainSeekBar.progress / 100f)
        playbackVolLabel.text = "%.1fx".format(playbackVolSeekBar.progress / 100f)
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
            addLog("Requesting permissions...")
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            if (hasPhoneStatePermission()) loadSimSelection()
            // Auto-start SERVICE — phone only needs to show status, frontend controls the rest
            if (!isServiceAudioActive && !isTestAudioActive && hasSimAvailable) {
                addLog("✅ Permissions ready — auto-starting SERVICE")
                pendingStartAudioTest = false
                startService()
                dispatchServiceAudioInputRequest()
            }
        }
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun isNetworkValidated(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun checkNetworkAvailability() {
        val wasConnected = hasInternetConnection
        hasInternetConnection = isNetworkValidated()
        if (!hasInternetConnection && wasConnected) {
            addLog("❌ No validated internet connection")
        } else if (hasInternetConnection && !wasConnected) {
            addLog("✅ Internet connection available")
        }
    }

    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (validated && !hasInternetConnection) {
                    hasInternetConnection = true
                    addLog("✅ Internet connection restored")
                    runOnUiThread { updateButtonState() }
                } else if (!validated && hasInternetConnection) {
                    hasInternetConnection = false
                    addLog("❌ No internet — stopping active audio")
                    runOnUiThread {
                        updateButtonState()
                        if (isTestAudioActive) stopTestAudio()
                        else if (isServiceAudioActive) stopServiceAudioInput()
                    }
                }
            }
            override fun onLost(network: android.net.Network) {
                if (!isNetworkValidated() && hasInternetConnection) {
                    hasInternetConnection = false
                    addLog("❌ Network lost — stopping active audio")
                    runOnUiThread {
                        updateButtonState()
                        if (isTestAudioActive) stopTestAudio()
                        else if (isServiceAudioActive) stopServiceAudioInput()
                    }
                }
            }
        }
        val request = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback!!)
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
            networkCallback = null
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
        unregisterNetworkCallback()
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
            // Fallback: check simState directly — activeSubscriptionInfoList can return empty
            // on some devices during early startup even when a SIM is present.
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            val simReady = tm.simState == android.telephony.TelephonyManager.SIM_STATE_READY
            if (simReady) {
                addLog("⚠️ SubscriptionManager returned empty but SIM state=READY — treating as SIM available")
                hasSimAvailable = true
                findViewById<View>(R.id.sim_selection_container).visibility = View.GONE
                updateButtonState()
                return
            }
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
