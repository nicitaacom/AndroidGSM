package com.nicitaacom.androidgsm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    
    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        var instance: MainActivity? = null
        
        fun log(message: String) {
            instance?.addLog(message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        instance = this
        
        logTextView = findViewById(R.id.logTextView)
        scrollView = findViewById(R.id.scrollView)
        toggleButton = findViewById(R.id.toggleButton)
        statusTextView = findViewById(R.id.statusTextView)
        
        toggleButton.setOnClickListener {
            if (isServiceRunning) {
                stopService()
            } else {
                requestPermissionsAndStart()
            }
        }
        
        updateButtonState()

        addLog("App started")
        addLog("Android version: ${Build.VERSION.RELEASE}")
        addLog("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        
        checkServiceStatus()
    }
    
    private fun checkServiceStatus() {
        statusTextView.text = "Status: Ready"
        updateButtonState()
    }
    
    private fun updateButtonState() {
        if (isServiceRunning) {
            toggleButton.text = "STOP SERVICE"
            toggleButton.setBackgroundColor(resources.getColor(android.R.color.holo_red_dark))
            statusTextView.text = "Status: Active"
        } else {
            toggleButton.text = "START SERVICE"
            toggleButton.setBackgroundColor(resources.getColor(android.R.color.holo_green_dark))
            statusTextView.text = "Status: Inactive"
        }
    }

    private fun requestPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.WAKE_LOCK
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            addLog("Requesting ${missingPermissions.size} permissions...")
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        } else {
            startService()
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
        } catch (e: Exception) {
            addLog("ERROR starting service: ${e.message}")
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
        } catch (e: Exception) {
            addLog("ERROR stopping service: ${e.message}")
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            
            if (allGranted) {
                addLog("All permissions granted!")
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
            
            // Keep only last 500 lines
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
        instance = null
    }
}
