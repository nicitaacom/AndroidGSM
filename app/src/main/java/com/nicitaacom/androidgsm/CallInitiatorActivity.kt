package com.nicitaacom.androidgsm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri

class CallInitiatorActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val number = intent.getStringExtra("number")
        if (number.isNullOrEmpty()) {
            MainActivity.log("ERROR: CallInitiatorActivity - missing number")
            finish()
            return
        }

        // 1. check CALL_PHONE permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            MainActivity.log("ERROR: CALL_PHONE permission not granted")
            finish()
            return
        }

        try {
            // 2. show when locked (API 27+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }

            // 3. keep screen on + dismiss keyguard
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)

            // 4. dismiss keyguard if needed (API 26+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val keyguardManager = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
                keyguardManager.requestDismissKeyguard(this, null)
            }

            // 5. start call with delay to ensure activity is ready
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val callIntent = Intent(Intent.ACTION_CALL, "tel:$number".toUri()).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK

                        // Add selected SIM subscription ID
                        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                        val selectedSubId = prefs.getInt("selected_sim", -1)

                        if (selectedSubId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            // For API 24+, use PhoneAccountHandle
                            if (ActivityCompat.checkSelfPermission(this@CallInitiatorActivity, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                                try {
                                    val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                                    val phoneAccounts = telecomManager.callCapablePhoneAccounts

                                    // Find the phone account that matches our subscription ID
                                    val subMgr = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                                    val subInfo = subMgr.getActiveSubscriptionInfo(selectedSubId)

                                    if (subInfo != null) {
                                        val targetAccount = phoneAccounts.find { account ->
                                            // Match by SIM slot index or subId
                                            account.id.contains(subInfo.simSlotIndex.toString()) || account.id.contains(selectedSubId.toString())
                                        }

                                        if (targetAccount != null) {
                                            putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, targetAccount)
                                            MainActivity.log("Using SIM slot ${subInfo.simSlotIndex + 1} (SubId: $selectedSubId)")
                                        } else {
                                            MainActivity.log("WARNING: Could not find PhoneAccount for SubId $selectedSubId")
                                        }
                                    }
                                } catch (e: Exception) {
                                    MainActivity.log("WARNING: Error setting phone account: ${e.message}")
                                }
                            }
                        } else if (selectedSubId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                            // For API 22-23, use subscription ID directly
                            putExtra("android.phone.extra.SLOT_ID", selectedSubId)
                            MainActivity.log("Using SubId: $selectedSubId (API < 24)")
                        } else {
                            MainActivity.log("No SIM selected or single SIM device - using default")
                        }
                    }
                    startActivity(callIntent)
                    MainActivity.log("Call initiated automatically to $number")
                } catch (error: Exception) {
                    MainActivity.log("ERROR starting call: ${error.message}")
                    error.printStackTrace()
                }

                // 6. close after call starts
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val bringIntent = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }
                        startActivity(bringIntent)
                        MainActivity.log("Brought MainActivity to front from CallInitiator")
                    } catch (e: Exception) {
                        MainActivity.log("Error bringing MainActivity to front from CallInitiator: ${e.message}")
                    }
                    finish()
                }, 1000)
            }, 300)

        } catch (error: Exception) {
            MainActivity.log("ERROR in CallInitiatorActivity: ${error.message}")
            error.printStackTrace()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        MainActivity.log("CallInitiatorActivity destroyed")
    }
}