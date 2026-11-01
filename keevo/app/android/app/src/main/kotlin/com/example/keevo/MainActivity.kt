package com.example.keevo

import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // Story 16.11 — readable device label for « Appareils connectés »
        // (« Xiaomi Redmi Note 12 · Android 14 »). No identifier, no permission.
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "keevo/device")
            .setMethodCallHandler { call, result ->
                if (call.method == "label") {
                    val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
                    val model = Build.MODEL.orEmpty()
                    val device = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
                    result.success("$device · Android ${Build.VERSION.RELEASE}")
                } else {
                    result.notImplemented()
                }
            }
    }
}
