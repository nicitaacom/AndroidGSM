package com.nicitaacom.androidgsm

import android.Manifest
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
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
    private lateinit var copyLogsButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var versionTextView: TextView
    private lateinit var micSourceToggleButton: Button
    private var hasSimAvailable = true
    private var hasInternetConnection = true
    private var hasRequiredPermissions = false
    private var pendingAllowStartWithoutSim = false
    private var isTestAudioActive = false
    private val meetsMinAndroid = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q // API 29
    private var isServiceAudioActive = false

    private val logBuffer = StringBuilder()
    private val logLock = Any()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var originalScreenTimeout: Long = -1
    private var logUpdatePending = false

    private val dialerRoleLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val granted = result.resultCode == RESULT_OK
            addLog(if (granted) "✅ Default dialer GRANTED" else "❌ Default dialer DENIED (resultCode=${result.resultCode})")
        }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val TAG = "GSM"
        private var instance: WeakReference<MainActivity>? = null

        fun log(message: String) {
            android.util.Log.d(TAG, message)
            instance?.get()?.addLog(message)
        }

        fun setStatus(text: String, active: Boolean) {
            instance?.get()?.runOnUiThread {
                val activity = instance?.get() ?: return@runOnUiThread
                activity.statusTextView.text = text
                activity.statusTextView.setTextColor(
                    ContextCompat.getColor(
                        activity,
                        if (active) R.color.brand_green else R.color.text_secondary
                    )
                )
            }
        }

        // Called by GsmService when START_SERVICE/STOP_SERVICE arrives over WebSocket,
        // so MainActivity.isServiceAudioActive stays in sync and onResume doesn't reset the UI.
        fun notifyServiceActive(active: Boolean) {
            instance?.get()?.runOnUiThread {
                val activity = instance?.get() ?: return@runOnUiThread
                activity.isServiceAudioActive = active
                if (active) activity.isTestAudioActive = false
                activity.updateStatus()
            }
        }

        fun notifyTestActive(active: Boolean) {
            instance?.get()?.runOnUiThread {
                val activity = instance?.get() ?: return@runOnUiThread
                activity.isTestAudioActive = active
                if (active) activity.isServiceAudioActive = false
                activity.updateStatus()
            }
        }

        fun notifyMicSource(useBrowser: Boolean) {
            instance?.get()?.runOnUiThread {
                instance?.get()?.updateMicSourceButton(useBrowser)
            }
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
        copyLogsButton = findViewById(R.id.copyLogsButton)
        statusTextView = findViewById(R.id.statusTextView)
        versionTextView = findViewById(R.id.versionTextView)
        micSourceToggleButton = findViewById(R.id.micSourceToggleButton)
        versionTextView.text = "outreach-tool.com | v.${BuildConfig.VERSION_NAME}"
        evaluateVersionFreshness()

        copyLogsButton.setOnClickListener {
            copyLastLogsToClipboard()
        }

        micSourceToggleButton.setOnClickListener {
            val newUseBrowser = !AudioWebSocketHandler.useBrowserMicUplink
            AudioWebSocketHandler.useBrowserMicUplink = newUseBrowser
            updateMicSourceButton(newUseBrowser)
            addLog("🎤 Mic source toggled to: ${if (newUseBrowser) "browser" else "phone"}")
            // Notify GsmService to hot-swap injection if call is active
            val intent = Intent(this, GsmService::class.java).apply {
                action = GsmService.ACTION_SET_MIC_SOURCE
                putExtra(GsmService.EXTRA_MIC_SOURCE, if (newUseBrowser) "browser" else "phone")
            }
            startGsmServiceSafely(intent)
        }

        checkNetworkAvailability()
        registerNetworkCallback()
        requestPermissionsOnLaunchIfNeeded() // auto-starts SERVICE inside once granted
        updateStatus()

        addLog("App started")
        addLog("Android version: ${Build.VERSION.RELEASE}")
        if (!meetsMinAndroid) addLog("❌ Android ${Build.VERSION.RELEASE} unsupported - SERVICE mode requires Android 10+ (API 29)")
        addLog("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        Thread {
            addLog(if (RootUtils.isRooted) "✅ Root access detected" else "⚠️ Root not detected — fallback to mic capture")
        }.start()

        if (hasPhoneStatePermission()) {
            try {
                loadSimSelection()
            } catch (se: SecurityException) {
                addLog("SecurityException reading SIM info: ${se.message}")
            }
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        addLog("Screen will stay on while app is active")

        // Defer to after window is attached — AlertDialog.show() in onCreate can silently fail.
        window.decorView.post { promptDefaultDialerIfNeeded() }
    }

    private var defaultDialerPromptShown = false

    // Prompt user to set this app as the default dialer. Required so our GsmInCallService
    // receives the Call object — the only reliable way to send DTMF (Call.playDtmfTone).
    private fun promptDefaultDialerIfNeeded() {
        addLog("🔍 Checking default dialer status…")
        if (defaultDialerPromptShown) { addLog("(prompt already shown this session)"); return }
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                addLog("Default dialer prompt skipped — pre-Android 10")
                return
            }
            val rm = getSystemService(Context.ROLE_SERVICE) as? RoleManager
            if (rm == null) { addLog("❌ RoleManager unavailable"); return }
            if (rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                addLog("✅ App is the default dialer — DTMF available")
                return
            }
            if (!rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                addLog("❌ ROLE_DIALER not available on this MIUI build — set manually in Settings → Apps → Default apps → Phone")
                return
            }
            defaultDialerPromptShown = true
            addLog("⚠️ App is not default dialer — showing prompt")
            AlertDialog.Builder(this)
                .setTitle("Set as default Phone app")
                .setMessage("DTMF tones (key presses during calls) require this app to be the default Phone app. Otherwise key presses won't be sent to the remote party.\n\nTap 'Set as default' to grant.")
                .setPositiveButton("Set as default") { _, _ ->
                    try {
                        val intent = rm.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                        dialerRoleLauncher.launch(intent)
                        addLog("Default dialer role request launched")
                    } catch (e: Exception) {
                        addLog("❌ Failed to launch role intent: ${e.message}")
                    }
                }
                .setNegativeButton("Skip (no DTMF)") { _, _ ->
                    addLog("User skipped default dialer prompt")
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            addLog("default dialer prompt failed: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        checkNetworkAvailability()  // snapshot check on resume; real-time handled by networkCallback
        // Re-check SIM on every resume — subscription list can be empty on first onCreate
        if (hasPhoneStatePermission()) {
            try { loadSimSelection() } catch (_: Exception) {}
        }
        updateStatus()
        // Backup trigger — guarded by defaultDialerPromptShown, won't re-show after grant/deny
        window.decorView.post { promptDefaultDialerIfNeeded() }
    }

    private fun updateStatus() {
        val isError = !meetsMinAndroid || !hasInternetConnection || !hasRequiredPermissions || !hasSimAvailable
        val isActive = isServiceAudioActive || isTestAudioActive
        val text = when {
            !meetsMinAndroid -> "Status: Android 10+ required"
            !hasInternetConnection -> "Status: No Internet"
            !hasRequiredPermissions -> "Status: Waiting for permissions"
            isTestAudioActive -> "Status: Test Audio Active"
            isServiceAudioActive -> "Status: Service Active"
            !hasSimAvailable -> "Status: No SIM"
            else -> "Status: Not Active"
        }
        val color = when {
            isError -> R.color.error_red
            isActive -> R.color.brand_green
            else -> R.color.text_secondary
        }
        statusTextView.text = text
        statusTextView.setTextColor(ContextCompat.getColor(this, color))
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
                updateStatus()
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
            updateStatus()
            addLog("Service stopped successfully!")
        } catch (error: Exception) {
            addLog("ERROR stopping service: ${error.message}")
        }
    }

    private fun stopServiceAudioInput() {
        val intent = Intent(this, GsmService::class.java).apply {
            action = GsmService.ACTION_STOP_SERVICE
        }
        startGsmServiceSafely(intent)
        isServiceAudioActive = false
        isTestAudioActive = false
        updateStatus()
        addLog("🛑 Service audio stopped")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                addLog("All permissions granted!")
                hasRequiredPermissions = true
                requestBatteryOptimizationExemption()
                try { loadSimSelection() } catch (error: Exception) { addLog("SIM load error: ${error.message}") }
                startService(pendingAllowStartWithoutSim)
                pendingAllowStartWithoutSim = false
            } else {
                hasRequiredPermissions = false
                pendingAllowStartWithoutSim = false
                addLog("ERROR: Some permissions were denied")
                val deniedPermissions = permissions.filterIndexed { index, _ ->
                    grantResults[index] != PackageManager.PERMISSION_GRANTED
                }
                addLog("Denied: ${deniedPermissions.joinToString()}")
            }
            updateStatus()
        }
    }

    private fun dispatchServiceAudioInputRequest() {
        val intent = Intent(this, GsmService::class.java).apply {
            action = GsmService.ACTION_START_SERVICE
        }
        if (!startGsmServiceSafely(intent)) return
        isServiceAudioActive = true
        isTestAudioActive = false
        updateStatus()
        addLog("📞 SERVICE audio started")
    }

    private fun stopTestAudio() {
        val intent = Intent(this, GsmService::class.java).apply {
            action = GsmService.ACTION_STOP_TEST
        }
        startGsmServiceSafely(intent)
        isTestAudioActive = false
        isServiceAudioActive = false
        updateStatus()
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
        if (missing.isNotEmpty()) {
            addLog("Requesting permissions...")
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            if (hasPhoneStatePermission()) loadSimSelection()
            // Start the service process so WS cmd connects — SERVICE/TEST controlled by frontend
            startService()
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
                    runOnUiThread { updateStatus() }
                } else if (!validated && hasInternetConnection) {
                    hasInternetConnection = false
                    addLog("❌ No internet — stopping active audio")
                    runOnUiThread {
                        updateStatus()
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
                        updateStatus()
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
        if (instance?.get() == this) instance = null
    }

    fun updateMicSourceButton(useBrowser: Boolean) {
        micSourceToggleButton.text = if (useBrowser) "MIC: BROWSER" else "MIC: PHONE"
        micSourceToggleButton.setBackgroundColor(
            ContextCompat.getColor(this, if (useBrowser) R.color.brand_green else R.color.error_red)
        )
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
                updateStatus()
                return
            }
            hasSimAvailable = false
            addLog("❌ No SIM cards detected - GSM calling is unavailable")
            findViewById<View>(R.id.sim_selection_container).visibility = View.GONE
            updateStatus()
            return
        }

        hasSimAvailable = true
        updateStatus()

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
