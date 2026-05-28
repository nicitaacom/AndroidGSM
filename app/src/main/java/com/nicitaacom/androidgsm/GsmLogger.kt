package com.nicitaacom.androidgsm

import android.util.Log

// Decouples domain classes from the UI layer.
// GsmService wires this to MainActivity.log; domain classes only receive the lambda.
typealias GsmLogger = (String) -> Unit

fun androidLog(tag: String): GsmLogger = { msg -> Log.d(tag, msg) }
