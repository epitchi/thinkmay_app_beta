package com.thinkmay.thinkmay_app_beta
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity: FlutterActivity() {
    private val CHANNEL = "com.thinkmay.thinkmay_app_beta/battery"

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler {
                call, result ->
                if (call.method == "getBatteryLevel") {
                    result.success("dumb as");

                } else {
                    result.notImplemented();
                }
            // Note: this method is invoked on the main thread.
            // TODO
        }
    }
}