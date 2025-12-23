package com.nicitaacom.androidgsm

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
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
    private lateinit var statusTextView: TextView
    private var isServiceRunning = false

    private val logBuffer = StringBuilder()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var originalBrightness = -1f
    private var originalScreenTimeout: Long = -1

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private var instance: WeakReference<MainActivity>? = null

        fun log(message: String) {
            instance?.get()?.addLog(message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        instance = WeakReference(this)

        logTextView = findViewById(R.id.logTextView)
        scrollView = findViewById(R.id.scrollView)
        toggleButton = findViewById(R.id.toggleButton)
        statusTextView = findViewById(R.id.statusTextView)

        val versionTextView: TextView = findViewById(R.id.versionTextView)
        versionTextView.text = "outreach-tool.com | v.${BuildConfig.VERSION_NAME}"

        toggleButton.setOnClickListener {
            if (isServiceRunning) stopService() else requestPermissionsAndStart()
        }

        updateButtonState()

        addLog("App started")
        addLog("Android version: ${Build.VERSION.RELEASE}")
        addLog("Device: ${Build.MANUFACTURER} ${Build.MODEL}")

        checkServiceStatus()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            loadSimSelection()
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        addLog("Screen will stay on while app is active")
    }

    override fun onResume() {
        super.onResume()
        if (isServiceRunning) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val params = window.attributes
            originalBrightness = if (params.screenBrightness < 0f) 0.5f else params.screenBrightness
            params.screenBrightness = 0.01f
            window.attributes = params
            addLog("Screen kept on and dimmed for continuous operation")

            // Set screen timeout if not set and permission granted
            if (originalScreenTimeout == -1L && Settings.System.canWrite(this)) {
                try {
                    originalScreenTimeout = Settings.System.getLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
                    Settings.System.putLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, Int.MAX_VALUE.toLong())
                    addLog("Set screen timeout to maximum to prevent disable")
                } catch (e: Exception) {
                    addLog("Error setting screen timeout: ${e.message}")
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (isServiceRunning) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (originalBrightness != -1f) {
                val params = window.attributes
                params.screenBrightness = originalBrightness
                window.attributes = params
            }
            addLog("Restored normal screen brightness")
        }
    }

    private fun checkServiceStatus() {
        statusTextView.text = "Status: Ready"
        updateButtonState()
    }

    private fun updateButtonState() {
        toggleButton.text = if (isServiceRunning) "STOP SERVICE" else "START SERVICE"
        toggleButton.setBackgroundColor(ContextCompat.getColor(this, if (isServiceRunning) R.color.error_red else R.color.brand_green))
        statusTextView.text = if (isServiceRunning) "Status: Active" else "Status: Inactive"
    }

    private fun requestPermissionsAndStart() {
        val permissions = mutableListOf<String>().apply {
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_PHONE_STATE)
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

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            addLog("Requesting battery optimization exemption...")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            }
            startActivity(intent)
        }

        // Request write settings if needed
        if (!Settings.System.canWrite(this)) {
            addLog("Requesting write settings permission to control screen timeout...")
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = "package:$packageName".toUri()
            }
            startActivity(intent)
        }

        val missingPermissions = permissions.filter { permission: String ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            addLog("Requesting ${missingPermissions.size} permissions...")
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            startService()
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

    private fun startService() {
        try {
            addLog("Starting GSM Gateway Service...")
            val intent = Intent(this, GsmService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            isServiceRunning = true
            updateButtonState()
            addLog("Service started successfully!")
        } catch (error: Exception) {
            addLog("ERROR starting service: ${error.message}")
        }
    }

    private fun stopService() {
        try {
            addLog("Stopping GSM Gateway Service...")
            val intent = Intent(this, GsmService::class.java)
            stopService(intent)

            isServiceRunning = false
            updateButtonState()
            addLog("Service stopped successfully!")

            // Restore screen timeout
            if (originalScreenTimeout != -1L && Settings.System.canWrite(this)) {
                try {
                    Settings.System.putLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, originalScreenTimeout)
                    addLog("Restored original screen timeout")
                } catch (e: Exception) {
                    addLog("Error restoring screen timeout: ${e.message}")
                }
                originalScreenTimeout = -1
            }
        } catch (error: Exception) {
            addLog("ERROR stopping service: ${error.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                addLog("All permissions granted!")
                requestBatteryOptimizationExemption()
                loadSimSelection()
                startService()
            } else {
                addLog("ERROR: Some permissions were denied")
                val deniedPermissions = permissions.filterIndexed { index, _ ->
                    grantResults[index] != PackageManager.PERMISSION_GRANTED
                }
                addLog("Denied: ${deniedPermissions.joinToString()}")
            }
        }
    }

    fun addLog(message: String) {
        runOnUiThread {
            val timestamp = dateFormat.format(Date())
            val logEntry = "[$timestamp] $message\n"
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

    override fun onDestroy() {
        super.onDestroy()
        if (instance?.get() == this) {
            instance = null
        }
    }

    private fun loadSimSelection() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            addLog("⚠️ READ_PHONE_STATE permission missing - SIM selection disabled")
            return
        }

        val subMgr = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val subs = subMgr.activeSubscriptionInfoList ?: emptyList()

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