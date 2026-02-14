package com.nicitaacom.androidgsm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

        setContentView(android.R.layout.simple_list_item_1)

        val number = intent.getStringExtra("number")
        if (number.isNullOrEmpty()) {
            MainActivity.log("ERROR: CallInitiatorActivity - missing number")
            finish()
            return
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            MainActivity.log("ERROR: CALL_PHONE permission not granted")
            finish()
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }

            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val keyguardManager = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
                keyguardManager.requestDismissKeyguard(this, null)
            }

            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val selectedSubId = prefs.getInt("selected_sim", -1)

            val callIntent = Intent(Intent.ACTION_CALL, "tel:$number".toUri()).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK

                if (selectedSubId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    if (ActivityCompat.checkSelfPermission(this@CallInitiatorActivity, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                        try {
                            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                            val phoneAccounts = telecomManager.callCapablePhoneAccounts
                            val subMgr = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                            val subInfo = subMgr.getActiveSubscriptionInfo(selectedSubId)

                            if (subInfo != null) {
                                val targetAccount = phoneAccounts.find { account ->
                                    account.id.contains(subInfo.simSlotIndex.toString()) || account.id.contains(selectedSubId.toString())
                                }

                                if (targetAccount != null) {
                                    putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, targetAccount)
                                    MainActivity.log("Using SIM slot ${subInfo.simSlotIndex + 1} (SubId: $selectedSubId)")
                                }
                            }
                        } catch (e: Exception) {
                            MainActivity.log("WARNING: Error setting phone account: ${e.message}")
                        }
                    }
                } else if (selectedSubId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    putExtra("android.phone.extra.SLOT_ID", selectedSubId)
                    MainActivity.log("Using SubId: $selectedSubId (API < 24)")
                }
            }

            startActivity(callIntent)
            MainActivity.log("Call initiated automatically to $number")
            finish()

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