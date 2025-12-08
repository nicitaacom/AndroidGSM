package com.gsm.gateway

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.gsm.gateway.service.GatewayService

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        startGatewayService()
    }

    private fun startGatewayService() {
        val serviceIntent = Intent(this, GatewayService::class.java)
        startService(serviceIntent)
    }
}