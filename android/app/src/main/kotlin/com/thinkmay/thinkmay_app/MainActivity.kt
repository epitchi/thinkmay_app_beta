package com.thinkmay.thinkmay_app
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

import android.content.Intent;

class MainActivity: FlutterActivity() {
    private val CHANNEL = "com.thinkmay.thinkmay_app/battery"

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler {
                call, result ->
                if (call.method == "getBatteryLevel") {
                    val intent = Intent(this, SecondActivity::class.java)
                    startActivity(intent);
                    result.success("dumb as");
                } else {
                    result.notImplemented();
                }
        }
    }
}