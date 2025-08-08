package com.thinkmay.thinkmay_app_beta


import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.hardware.input.InputManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Rational
import android.view.Display
import android.view.InputDevice
import android.view.InputDevice.MotionRange
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.View.OnGenericMotionListener
import android.view.View.OnKeyListener
import android.view.View.OnTouchListener
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.limelight.LimeLog
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.binding.input.ControllerHandler
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.binding.input.capture.InputCaptureManager
import com.limelight.binding.input.capture.InputCaptureProvider
import com.limelight.binding.input.evdev.EvdevListener
import com.limelight.binding.input.touch.AbsoluteTouchContext
import com.limelight.binding.input.touch.RelativeTouchContext
import com.limelight.binding.input.touch.TouchContext
import com.limelight.binding.input.virtual_controller.VirtualController
import com.limelight.binding.video.CrashListener
import com.limelight.binding.video.MediaCodecDecoderRenderer
import com.limelight.binding.video.MediaCodecHelper
import com.limelight.binding.video.PerfOverlayListener
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.input.KeyboardPacket
import com.limelight.nvstream.input.MouseButtonPacket
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.GlPreferences
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.GameGestures
import com.limelight.ui.StreamView
import com.limelight.ui.StreamView.InputCallbacks
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class Game: AppCompatActivity(),
    SurfaceHolder.Callback,
    EvdevListener,
    GameGestures,
    OnTouchListener,
    OnKeyListener,
    InputCallbacks,
    View.OnSystemUiVisibilityChangeListener,
    PerfOverlayListener,
    OnGenericMotionListener {

    private var attemptedConnection = false
    private val desiredFrameRate: Float = 0f
    private var lastButtonState  = 0
    private var suppressPipRefCount = 0;
    private val touchContextMap : Array<TouchContext?> = arrayOfNulls(2)
    private var threeFingerDownTime : Long = 0

    private var controllerHandler : ControllerHandler? = null
    private var keyboardTranslator : KeyboardTranslator? = null
    private var virtualController : VirtualController? = null

    private var cursorVisible = false
    private var grabbedInput: Boolean = true

    private var modifierFlags = 0

    var waitingForAllModifiersUp: Boolean = false

    var specialKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN

    private var isHidingOverlays: Boolean = true
    companion object {
        const val REFERENCE_HORIZ_RES: Int = 1280
        const val REFERENCE_VERT_RES: Int = 720
        const val STYLUS_DOWN_DEAD_ZONE_DELAY: Int = 100
        const val STYLUS_DOWN_DEAD_ZONE_RADIUS: Int = 20
        const val STYLUS_UP_DEAD_ZONE_DELAY: Int = 150
        const val STYLUS_UP_DEAD_ZONE_RADIUS: Int = 50
        const val THREE_FINGER_TAP_THRESHOLD: Int = 300



    }

    private var connecting = false
    private var connected = false
    private var autoEnterPip = false

    private var surfaceCreated = false


    private var prefConfig: PreferenceConfiguration? = null
    private var tombstonePrefs : SharedPreferences? = null


    private var inputCaptureProvider : InputCaptureProvider? = null

    private lateinit var conn : NvConnection
    private var streamView : StreamView? = null
    private var lastAbsTouchUpTime: Long = 0
    private var lastAbsTouchDownTime: Long = 0
    private var lastAbsTouchUpX = 0f
    private var lastAbsTouchUpY = 0f
    private var lastAbsTouchDownX = 0f
    private var lastAbsTouchDownY = 0f

    private lateinit var performanceOverlayView : TextView

    private lateinit var notificationOverlayView : TextView

    var requestedNotificationOverlayVisibility: Int = View.GONE


    private var decoderRenderer : MediaCodecDecoderRenderer? = null

    private var appName : String? = null
    private var pcName : String? = null

    var desiredRefreshRate: Float = 0f


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

        window.decorView.setOnSystemUiVisibilityChangeListener(this)

        volumeControlStream = AudioManager.STREAM_MUSIC

        setContentView(R.layout.activity_game)

        prefConfig = PreferenceConfiguration.readPreferences(this)
        tombstonePrefs = this.getSharedPreferences("DecoderTombstone",0)

        setPreferredOrientationForCurrentDisplay()


        if (prefConfig?.stretchVideo == true || shouldIgnoreInsetsForResolution(
                prefConfig?.width ?: 0,
                prefConfig?.height ?: 0
            )) {
        }

        streamView = findViewById<StreamView>(R.id.surfaceView)
        streamView?.setOnGenericMotionListener(this)
        streamView?.setOnKeyListener(this)
        streamView?.setInputCallbacks(this)

        val backgroundTouchView = findViewById<View>(R.id.backgroundTouchView)
        backgroundTouchView.setOnTouchListener(this)

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
            streamView?.requestUnbufferedDispatch(
                InputDevice.SOURCE_CLASS_BUTTON or
                    InputDevice.SOURCE_CLASS_JOYSTICK or
                    InputDevice.SOURCE_CLASS_POINTER or
                    InputDevice.SOURCE_CLASS_POSITION or
                    InputDevice.SOURCE_CLASS_TRACKBALL)

            backgroundTouchView?.requestUnbufferedDispatch(
                InputDevice.SOURCE_CLASS_BUTTON or
                    InputDevice.SOURCE_CLASS_JOYSTICK or
                    InputDevice.SOURCE_CLASS_POINTER or
                    InputDevice.SOURCE_CLASS_POSITION or
                    InputDevice.SOURCE_CLASS_TRACKBALL )
        }

        inputCaptureProvider = InputCaptureManager.getInputCaptureProvider(this,this)

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
            streamView?.setOnCapturedPointerListener{
                view,motionEvent -> handleMotionEvent(view,motionEvent)
            }
        }

        val connMgr = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val glPrefs = GlPreferences.readPreferences(this)
        MediaCodecHelper.initialize(this,glPrefs.glRenderer)

        var willStreamHDR = false
        if (prefConfig?.enableHdr ?:true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val display = windowManager.defaultDisplay
                val hdrCaps = display.hdrCapabilities
                if (hdrCaps != null) {
                    for (hdrType in hdrCaps.supportedHdrTypes) {
                        if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                            willStreamHDR = true
                            break
                        }
                    }
                }
            }
        }

        val crashHandler = CrashListener() {
            fun notifyCrash(e: Exception) {

            }
        }


        decoderRenderer = tombstonePrefs?.getInt("CrashCount",4)?.let {
            MediaCodecDecoderRenderer(
                this,
                prefConfig,
                crashHandler,
                it,
                connMgr.isActiveNetworkMetered,
                willStreamHDR,
                glPrefs.glRenderer,
                this)
        }


        conn = NvConnection()
        controllerHandler = ControllerHandler(this,conn,this,prefConfig)
        keyboardTranslator = KeyboardTranslator()

        val inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(keyboardTranslator,null)

        for (i in 1..touchContextMap.size) {
            if (prefConfig?.touchscreenTrackpad == false) {
                touchContextMap[i] = AbsoluteTouchContext(conn,i,streamView)
            } else {
                touchContextMap[i] = RelativeTouchContext(conn,i,
                    REFERENCE_HORIZ_RES,
                    REFERENCE_VERT_RES,
                    streamView,
                    prefConfig)
            }
        }

        if (prefConfig?.onscreenController==true) {
            virtualController = VirtualController(controllerHandler,
                streamView?.parent as FrameLayout,
                this)
            virtualController?.refreshLayout()
            virtualController?.show()
        }


        streamView?.holder?.addCallback(this)
    }



    override fun surfaceCreated(holder: SurfaceHolder) {
        var desiredFrameRate = 0f
        this.surfaceCreated = true

        if (this.mayReduceRefreshRate()) {
            desiredFrameRate = 0f // preference conf
        } else {
            desiredFrameRate = this.desiredFrameRate
        }

        if  (Build.VERSION.SDK_INT > Build.VERSION_CODES.S) {
            holder.surface.setFrameRate(desiredFrameRate,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                Surface.CHANGE_FRAME_RATE_ALWAYS)
        } else if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R){
            holder.surface.setFrameRate(desiredFrameRate,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        }



    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        if (!surfaceCreated) {
            throw IllegalStateException("surface change before creation")
        }

        if (!attemptedConnection) {
            attemptedConnection = true

            decoderRenderer?.setRenderTarget(holder)
            val audioRenderer = AndroidAudioRenderer(this,false)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!surfaceCreated) {
            throw IllegalStateException("surface destroy before creation")
        }

        if (attemptedConnection) {
            decoderRenderer?.prepareForStop()
            if (connected) {
                stopConnection()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Set requested orientation for possible new screen size
        setPreferredOrientationForCurrentDisplay()

        if (virtualController != null) {
            // Refresh layout of OSC for possible new screen size
            virtualController!!.refreshLayout()
        }

        // Hide on-screen overlays in PiP mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isInPictureInPictureMode()) {
                isHidingOverlays = true

                if (virtualController != null) {
                    virtualController!!.hide()
                }

                performanceOverlayView.setVisibility(View.GONE)
                notificationOverlayView.setVisibility(View.GONE)

                // Disable sensors while in PiP mode
                controllerHandler!!.disableSensors()

                // Update GameManager state to indicate we're in PiP (still gaming, but interruptible)
//                UiHelper.notifyStreamEnteringPiP(this)
//                TODO: notifyStreamEnteringPiP
            } else {
                isHidingOverlays = false

                // Restore overlays to previous state when leaving PiP
                if (virtualController != null) {
                    virtualController!!.show()
                }

                if (prefConfig!!.enablePerfOverlay) {
                    performanceOverlayView.setVisibility(View.VISIBLE)
                }

                notificationOverlayView.setVisibility(requestedNotificationOverlayVisibility)

                // Enable sensors again after exiting PiP
                controllerHandler!!.enableSensors()

                // Update GameManager state to indicate we're out of PiP (gaming, non-interruptible)
//                UiHelper.notifyStreamExitingPiP(this)
//                TODO: notifyStreamExitingPiP
            }
        }
    }

    override fun onKey(
        v: View?,
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {
        when (event?.action) {
            KeyEvent.ACTION_DOWN -> return this.handleKeyDown(event)
            KeyEvent.ACTION_UP -> return this.handleKeyUp(event)
            KeyEvent.ACTION_MULTIPLE-> return this.handleKeyMultiple(event)
            else -> return false
        }
    }

//    private fun handleKeyMultiple(event: KeyEvent): Boolean {
//        return false
//    }

//    override fun handleKeyUp(event: KeyEvent): Boolean {
//        return false
//    }

//    override fun onTouch(v: View, event: MotionEvent): Boolean {
//        if (event?.action == MotionEvent.ACTION_DOWN) {
//            v?.requestUnbufferedDispatch(event)
//        }
//
//        return handleMotionEvent(v,event)
//    }

    fun stageStarting(stage : String) {
//        runOnUiThread(new Runnable() {
//            @Override
//            public void run() {
//                if (spinner != null) {
//                    spinner.setMessage(getResources().getString(R.string.conn_starting) + " " + stage);
//                }
//            }
//        });
        //TODO: SETMESSAGE
    }

    fun stageComplete(stage : String) {

    }

    fun updatePipAutoEnter() {
        if (!prefConfig!!.enablePip) {
            return
        }

        val autoEnter = connected && suppressPipRefCount === 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//            setPictureInPictureParams(getPictureInPictureParams(autoEnter))
            //TOTO: setPictureInPictureParams
        } else {
            autoEnterPip = autoEnter
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun getPictureInPictureParams(autoEnter : Boolean) : PictureInPictureParams {

        val builder =
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational(prefConfig!!.width, prefConfig!!.height))
                .setSourceRectHint(
                    Rect(
                        streamView!!.getLeft(), streamView!!.getTop(),
                        streamView!!.getRight(), streamView!!.getBottom()
                    )
                )


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter);
            builder.setSeamlessResizeEnabled(true);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (appName != null) {
                builder.setTitle(appName);
                if (pcName != null) {
                    builder.setSubtitle(pcName);
                }
            }
            else if (pcName != null) {
                builder.setTitle(pcName);
            }
        }

        return builder.build();
    }
    fun stopConnection() {
        if (connecting || connected) {
            connected = false
            connecting = connected
            updatePipAutoEnter()

            controllerHandler!!.stop()

            // Update GameManager state to indicate we're no longer in game
//            UiHelper.notifyStreamEnded(this)
            //TOTO: notifyStreamEnded

            // Stop may take a few hundred ms to do some network I/O to tell
            // the server we're going away and clean up. Let it run in a separate
            // thread to keep things smooth for the UI. Inside moonlight-common,
            // we prevent another thread from starting a connection before and
            // during the process of stopping this one.
            object : Thread() {
                override fun run() {
                    conn!!.stop()
                }
            }.start()
        }
    }

    fun stageFailed(stage: String,portFlags : Int, errorCode: Int){
        // Perform a connection test if the failure could be due to a blocked port
        // This does network I/O, so don't do it on the main thread.
//        var portTestResult = MoonBridge.testClientConnectivity(ServerHelper.CONNECTION_TEST_SERVER, 443, portFlags);
//
//        runOnUiThread(new Runnable() {
//            @Override
//            public void run() {
//                if (spinner != null) {
//                    spinner.dismiss();
//                    spinner = null;
//                }
//
//                if (!displayedFailureDialog) {
//                    displayedFailureDialog = true;
//                    LimeLog.severe(stage + " failed: " + errorCode);
//
//                    // If video initialization failed and the surface is still valid, display extra information for the user
//                    if (stage.contains("video") && streamView.getHolder().getSurface().isValid()) {
//                        Toast.makeText(Game.this, getResources().getText(R.string.video_decoder_init_failed), Toast.LENGTH_LONG).show();
//                    }
//
//                    String dialogText = getResources().getString(R.string.conn_error_msg) + " " + stage +" (error "+errorCode+")";
//
//                    if (portFlags != 0) {
//                        dialogText += "\n\n" + getResources().getString(R.string.check_ports_msg) + "\n" +
//                                MoonBridge.stringifyPortFlags(portFlags, "\n");
//                    }
//
//                    if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0)  {
//                        dialogText += "\n\n" + getResources().getString(R.string.nettest_text_blocked);
//                    }
//
//                    Dialog.displayDialog(Game.this, getResources().getString(R.string.conn_error_title), dialogText, true);
//                }
//            }
//        });

        //TODO: stageFailed
    }

    fun connectionStatusUpdate(connectionStatus : Int) {
//        runOnUiThread(new Runnable() {
//            @Override
//            public void run() {
//                if (prefConfig.disableWarnings) {
//                    return;
//                }
//
//                if (connectionStatus == MoonBridge.CONN_STATUS_POOR) {
//                    if (prefConfig.bitrate > 5000) {
//                        notificationOverlayView.setText(getResources().getString(R.string.slow_connection_msg));
//                    }
//                    else {
//                        notificationOverlayView.setText(getResources().getString(R.string.poor_connection_msg));
//                    }
//
//                    requestedNotificationOverlayVisibility = View.VISIBLE;
//                }
//                else if (connectionStatus == MoonBridge.CONN_STATUS_OKAY) {
//                    requestedNotificationOverlayVisibility = View.GONE;
//                }
//
//                if (!isHidingOverlays) {
//                    notificationOverlayView.setVisibility(requestedNotificationOverlayVisibility);
//                }
//            }
//        TODO: connectionStatusUpdate
        }

    fun hideSystemUi(delay: Long) {
        val h: Handler? = getWindow().getDecorView().getHandler()
//        if (h != null) {
//            h.removeCallbacks(hideSystemUi)
//            h.postDelayed(hideSystemUi, delay)
//        }
    }
    
    fun setInputGrabState(grab: Boolean) {
        // Grab/ungrab the mouse cursor
        if (grab) {
            inputCaptureProvider!!.enableCapture()

            // Enabling capture may hide the cursor again, so
            // we will need to show it again.
            if (cursorVisible) {
                inputCaptureProvider!!.showCursor()
            }
        } else {
            inputCaptureProvider!!.disableCapture()
        }

        // Grab/ungrab system keyboard shortcuts
        setMetaKeyCaptureState(grab)

        grabbedInput = grab
    }

    private val toggleGrab: Runnable = Runnable { setInputGrabState(!grabbedInput) }

    public fun setMetaKeyCaptureState(enabled : Boolean) {
        // This uses custom APIs present on some Samsung devices to allow capture of
        // meta key events while streaming.
        try {
            val  semWindowManager: Class<*> = Class.forName("com.samsung.android.view.SemWindowManager");
            val getInstanceMethod = semWindowManager.getMethod("getInstance");
            val manager = getInstanceMethod.invoke(null);

            if (manager != null) {
                val parameterTypes = arrayOfNulls<Class<*>>(2)
                parameterTypes[0] = ComponentName::class.java;
                parameterTypes[1] = Boolean::class.java;
                val requestMetaKeyEventMethod = semWindowManager.getDeclaredMethod("requestMetaKeyEvent",
                    parameterTypes as Class<*>?
                );
                requestMetaKeyEventMethod.invoke(manager, this.getComponentName(), enabled);
            }
            else {
                LimeLog.warning("SemWindowManager.getInstance() returned null");
            }
        } catch (e : ClassNotFoundException) {
            e.printStackTrace();
        } catch (e:NoSuchMethodException) {
            e.printStackTrace();
        } catch (e: InvocationTargetException) {
            e.printStackTrace();
        } catch (e:IllegalAccessException) {
            e.printStackTrace();
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        // PiP is only supported on Oreo and later, and we don't need to manually enter PiP on
        // Android S and later. On Android R, we will use onPictureInPictureRequested() instead.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (autoEnterPip) {
                try {
                    // This has thrown all sorts of weird exceptions on Samsung devices
                    // running Oreo. Just eat them and close gracefully on leave, rather
                    // than crashing.
                    enterPictureInPictureMode(getPictureInPictureParams(false))
                } catch (e: java.lang.Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    public override fun onPictureInPictureRequested(): Boolean {
        // Enter PiP when requested unless we're on Android 12 which supports auto-enter.
        if (autoEnterPip && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            enterPictureInPictureMode(getPictureInPictureParams(false))
        }
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        // We can't guarantee the state of modifiers keys which may have
        // lifted while focus was not on us. Clear the modifier state.
        this.modifierFlags = 0

        // With Android native pointer capture, capture is lost when focus is lost,
        // so it must be requested again when focus is regained.
        inputCaptureProvider!!.onWindowFocusChanged(hasFocus)
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)

        // In multi-window, we don't want to use the full-screen layout
        // flag. It will cause us to collide with the system UI.
        // This function will also be called for PiP so we can cover
        // that case here too.
        if (isInMultiWindowMode) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decoderRenderer!!.notifyVideoBackground()
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decoderRenderer!!.notifyVideoForeground()
        }

        // Correct the system UI visibility flags
        hideSystemUi(50)
    }

    public override fun onDestroy() {
        super.onDestroy()

//        if (controllerHandler != null) {
//            controllerHandler!!.destroy()
//        }
//        if (keyboardTranslator != null) {
//            val inputManager = getSystemService(INPUT_SERVICE) as InputManager
//            inputManager.unregisterInputDeviceListener(keyboardTranslator)
//        }
//
//        if (lowLatencyWifiLock != null) {
//            lowLatencyWifiLock.release()
//        }
//        if (highPerfWifiLock != null) {
//            highPerfWifiLock.release()
//        }
//
//        if (connectedToUsbDriverService) {
//            // Unbind from the discovery service
//            unbindService(usbDriverServiceConnection)
//        }

//        TODO:WifiManager

        // Destroy the capture provider
        inputCaptureProvider!!.destroy()
    }

    public override fun onPause() {
        if (isFinishing()) {
            // Stop any further input device notifications before we lose focus (and pointer capture)
            if (controllerHandler != null) {
                controllerHandler!!.stop()
            }

            // Ungrab input to prevent further input device notifications
            setInputGrabState(false)
        }

        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        TODO("Not yet implemented")
    }
    fun isRefreshRateEqualMatch(refreshRate: Float): Boolean {
        return refreshRate >= prefConfig!!.fps &&
                refreshRate <= prefConfig!!.fps + 3
    }


    fun isRefreshRateGoodMatch(refreshRate: Float): Boolean {
        return refreshRate >= prefConfig!!.fps &&
                Math.round(refreshRate) % prefConfig!!.fps <= 3
    }

    fun getModifierState(event : KeyEvent) : Byte {
        // Start with the global modifier state to ensure we cover the case
        // detailed in https://github.com/moonlight-stream/moonlight-android/issues/840
        var modifier = getModifierState();
        if (event.isShiftPressed()) {
            modifier = (modifier.toInt() or KeyboardPacket.MODIFIER_SHIFT.toInt()).toByte()
        }
        if (event.isCtrlPressed()) {
            modifier = (modifier.toInt() or KeyboardPacket.MODIFIER_CTRL.toInt()).toByte()
        }
        if (event.isAltPressed()) {
            modifier = (modifier.toInt() or KeyboardPacket.MODIFIER_ALT.toInt()).toByte()
        }
        if (event.isMetaPressed()) {
            modifier = (modifier.toInt() or KeyboardPacket.MODIFIER_META.toInt()).toByte()
        }
        return modifier;
    }

    private fun getModifierState(): Byte {
        return modifierFlags as Byte
    }
    override fun handleKeyDown(event : KeyEvent) : Boolean {
        // Pass-through virtual navigation keys
        if ((event.getFlags() and KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false;
        }

        // Handle a synthetic back button event that some Android OS versions
        // create as a result of a right-click. This event WILL repeat if
        // the right mouse button is held down, so we ignore those.
        var eventSource = event.getSource();
        if ((eventSource == InputDevice.SOURCE_MOUSE ||
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
                event.getKeyCode() == KeyEvent.KEYCODE_BACK) {

            // Send the right mouse button event if mouse back and forward
            // are disabled. If they are enabled, handleMotionEvent() will take
            // care of this.
            prefConfig?.mouseNavButtons?.let {
                if (!it) {
                    conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                }
            }

            // Always return true, otherwise the back press will be propagated
            // up to the parent and finish the activity.
            return true;
        }

        var handled = false;

        if (ControllerHandler.isGameControllerDevice(event.getDevice())) {
            // Always try the controller handler first, unless it's an alphanumeric keyboard device.
            // Otherwise, controller handler will eat keyboard d-pad events.
            handled = controllerHandler?.handleButtonDown(event) ?: true;
        }

        // Try the keyboard handler if it wasn't handled as a game controller
        if (!handled) {
            // Let this method take duplicate key down events
            if (handleSpecialKeys(event.getKeyCode(), true)) {
                return true;
            }

            // Pass through keyboard input if we're not grabbing
            if (!grabbedInput) {
                return false;
            }

            // We'll send it as a raw key event if we have a key mapping, otherwise we'll send it
            // as UTF-8 text (if it's a printable character).
            var translated: Short =
                keyboardTranslator?.translate(event.getKeyCode(), event.getDeviceId()) ?: 1.toShort();
            if (translated?.toInt() == 0) {
                // Make sure it has a valid Unicode representation and it's not a dead character
                // (which we don't support). If those are true, we can send it as UTF-8 text.
                //
                // NB: We need to be sure this happens before the getRepeatCount() check because
                // UTF-8 events don't auto-repeat on the host side.
                var unicodeChar = event.getUnicodeChar();
                if ((unicodeChar and KeyCharacterMap.COMBINING_ACCENT) == 0 && (unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK) != 0) {
//                    conn.sendUtf8Text(""+(char)unicodeChar);
//                    TODO: sendUtf8Text
                    return true;
                }

                return false;
            }

            // Eat repeat down events
            if (event.getRepeatCount() > 0) {
                return true;
            }

            conn.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, getModifierState(event),
                    if (keyboardTranslator?.hasNormalizedMapping(event.getKeyCode(), event.getDeviceId())
                            ?: false) 0 else MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
        }

        return true;
    }

    fun handleSpecialKeys(androidKeyCode: Int, down: Boolean): Boolean {
        var modifierMask = 0
        var nonModifierKeyCode = KeyEvent.KEYCODE_UNKNOWN

        if (androidKeyCode == KeyEvent.KEYCODE_CTRL_LEFT ||
            androidKeyCode == KeyEvent.KEYCODE_CTRL_RIGHT
        ) {
            modifierMask = KeyboardPacket.MODIFIER_CTRL.toInt()
        } else if (androidKeyCode == KeyEvent.KEYCODE_SHIFT_LEFT ||
            androidKeyCode == KeyEvent.KEYCODE_SHIFT_RIGHT
        ) {
            modifierMask = KeyboardPacket.MODIFIER_SHIFT.toInt()
        } else if (androidKeyCode == KeyEvent.KEYCODE_ALT_LEFT ||
            androidKeyCode == KeyEvent.KEYCODE_ALT_RIGHT
        ) {
            modifierMask = KeyboardPacket.MODIFIER_ALT.toInt()
        } else if (androidKeyCode == KeyEvent.KEYCODE_META_LEFT ||
            androidKeyCode == KeyEvent.KEYCODE_META_RIGHT
        ) {
            modifierMask = KeyboardPacket.MODIFIER_META.toInt()
        } else {
            nonModifierKeyCode = androidKeyCode
        }

        if (down) {
            this.modifierFlags = this.modifierFlags or modifierMask
        } else {
            this.modifierFlags = this.modifierFlags and modifierMask.inv()
        }

        // Handle the special combos on the key up
        if (waitingForAllModifiersUp || specialKeyCode !== KeyEvent.KEYCODE_UNKNOWN) {
            if (specialKeyCode === androidKeyCode) {
                // If this is a key up for the special key itself, eat that because the host never saw the original key down
                return true
            } else if (modifierFlags != 0) {
                // While we're waiting for modifiers to come up, eat all key downs and allow all key ups to pass
                return down
            } else {
                // When all modifiers are up, perform the special action
                when (specialKeyCode) {
                    KeyEvent.KEYCODE_Z -> {
                        val h = getWindow().getDecorView().getHandler()
                        if (h != null) {
                            h.postDelayed(toggleGrab, 250)
                        }
                    }

                    KeyEvent.KEYCODE_Q -> finish()
                    KeyEvent.KEYCODE_C -> {
                        if (!grabbedInput) {
                            inputCaptureProvider!!.enableCapture()
                            grabbedInput = true
                        }
                        cursorVisible = !cursorVisible
                        if (cursorVisible) {
                            inputCaptureProvider!!.showCursor()
                        } else {
                            inputCaptureProvider!!.hideCursor()
                        }
                    }

                    else -> {}
                }

                // Reset special key state
                specialKeyCode = KeyEvent.KEYCODE_UNKNOWN
                waitingForAllModifiersUp = false
            }
        } else if ((modifierFlags and (KeyboardPacket.MODIFIER_CTRL.toInt() or KeyboardPacket.MODIFIER_ALT.toInt() or KeyboardPacket.MODIFIER_SHIFT.toInt())) ==
            (KeyboardPacket.MODIFIER_CTRL.toInt() or KeyboardPacket.MODIFIER_ALT.toInt() or KeyboardPacket.MODIFIER_SHIFT.toInt()) &&
            (down && nonModifierKeyCode != KeyEvent.KEYCODE_UNKNOWN)
        ) {
            when (androidKeyCode) {
                KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_C -> {
                    // Remember that a special key combo was activated, so we can consume all key
                    // events until the modifiers come up
                    specialKeyCode = androidKeyCode
                    waitingForAllModifiersUp = true
                    return true
                }

                else ->                     // This isn't a special combo that we consume on the client side
                    return false
            }
        }

        // Not a special combo
        return false
    }

    public override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return handleKeyDown(event!!) || super.onKeyDown(keyCode, event)
    }

    public override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return handleKeyUp(event!!) || super.onKeyUp(keyCode, event)
    }

    public override fun handleKeyUp(event: KeyEvent): Boolean {
        // Pass-through virtual navigation keys
        if ((event.getFlags() and KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false
        }

        // Handle a synthetic back button event that some Android OS versions
        // create as a result of a right-click.
        val eventSource = event.getSource()
        if ((eventSource == InputDevice.SOURCE_MOUSE ||
                    eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
            event.getKeyCode() == KeyEvent.KEYCODE_BACK
        ) {
            // Send the right mouse button event if mouse back and forward
            // are disabled. If they are enabled, handleMotionEvent() will take
            // care of this.

            if (!prefConfig!!.mouseNavButtons) {
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
            }

            // Always return true, otherwise the back press will be propagated
            // up to the parent and finish the activity.
            return true
        }

        var handled = false
        if (ControllerHandler.isGameControllerDevice(event.getDevice())) {
            // Always try the controller handler first, unless it's an alphanumeric keyboard device.
            // Otherwise, controller handler will eat keyboard d-pad events.
            handled = controllerHandler!!.handleButtonUp(event)
        }

        // Try the keyboard handler if it wasn't handled as a game controller
        if (!handled) {
            if (handleSpecialKeys(event.getKeyCode(), false)) {
                return true
            }

            // Pass through keyboard input if we're not grabbing
            if (!grabbedInput) {
                return false
            }

            val translated = keyboardTranslator!!.translate(event.getKeyCode(), event.getDeviceId())
            if (translated.toInt() == 0) {
                // If we sent this event as UTF-8 on key down, also report that it was handled
                // when we get the key up event for it.
                val unicodeChar = event.getUnicodeChar()
                return (unicodeChar and KeyCharacterMap.COMBINING_ACCENT) == 0 && (unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK) != 0
            }

            conn.sendKeyboardInput(
                translated, KeyboardPacket.KEY_UP, getModifierState(event),
                if (keyboardTranslator!!.hasNormalizedMapping(
                        event.getKeyCode(),
                        event.getDeviceId()
                    )
                ) 0 else MoonBridge.SS_KBE_FLAG_NON_NORMALIZED
            )
        }

        return true
    }

    public override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent?): Boolean {
        return handleKeyMultiple(event!!) || super.onKeyMultiple(keyCode, repeatCount, event)
    }

    fun handleKeyMultiple(event: KeyEvent): Boolean {
        // We can receive keys from a software keyboard that don't correspond to any existing
        // KEYCODE value. Android will give those to us as an ACTION_MULTIPLE KeyEvent.
        //
        // Despite the fact that the Android docs say this is unused since API level 29, these
        // events are still sent as of Android 13 for the above case.
        //
        // For other cases of ACTION_MULTIPLE, we will not report those as handled so hopefully
        // they will be passed to us again as regular singular key events.
        if (event.getKeyCode() != KeyEvent.KEYCODE_UNKNOWN || event.getCharacters() == null) {
            return false
        }

//        conn.sendUtf8Text(event.getCharacters())
//        TODO: sendUtf8Text
        return true
    }

    override fun toggleKeyboard() {
        LimeLog.info("Toggling keyboard overlay");
        var inputManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputManager.toggleSoftInput(0, 0);
    }

    fun getLiTouchTypeFromEvent(event: MotionEvent): Byte {
        when (event.getActionMasked()) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> return MoonBridge.LI_TOUCH_EVENT_DOWN

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if ((event.getFlags() and MotionEvent.FLAG_CANCELED) != 0) {
                return MoonBridge.LI_TOUCH_EVENT_CANCEL
            } else {
                return MoonBridge.LI_TOUCH_EVENT_UP
            }

            MotionEvent.ACTION_MOVE -> return MoonBridge.LI_TOUCH_EVENT_MOVE

            MotionEvent.ACTION_CANCEL ->                 // ACTION_CANCEL applies to *all* pointers in the gesture, so it maps to CANCEL_ALL
                // rather than CANCEL. For a single pointer cancellation, that's indicated via
                // FLAG_CANCELED on a ACTION_POINTER_UP.
                // https://developer.android.com/develop/ui/views/touch-and-input/gestures/multi
                return MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL

            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> return MoonBridge.LI_TOUCH_EVENT_HOVER

            MotionEvent.ACTION_HOVER_EXIT -> return MoonBridge.LI_TOUCH_EVENT_HOVER_LEAVE

            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> return MoonBridge.LI_TOUCH_EVENT_BUTTON_ONLY

            else -> return -1
        }
    }

    fun getStreamViewRelativeNormalizedXY(
        view: View?,
        event: MotionEvent,
        pointerIndex: Int
    ): FloatArray? {
        var normalizedX = event.getX(pointerIndex)
        var normalizedY = event.getY(pointerIndex)

        // For the containing background view, we must subtract the origin
        // of the StreamView to get video-relative coordinates.
        if (view !== streamView) {
            normalizedX -= streamView!!.getX()
            normalizedY -= streamView!!.getY()
        }

        normalizedX = max(normalizedX, 0.0f)
        normalizedY = max(normalizedY, 0.0f)

        normalizedX = min(normalizedX, streamView!!.getWidth().toFloat())
        normalizedY = min(normalizedY, streamView!!.getHeight().toFloat())

        normalizedX /= streamView!!.getWidth().toFloat()
        normalizedY /= streamView!!.getHeight().toFloat()

        return floatArrayOf(normalizedX, normalizedY)
    }

    fun normalizeValueInRange(value: Float, range: MotionRange): Float {
        return (value - range.getMin()) / range.getRange()
    }

    fun getPressureOrDistance(event: MotionEvent, pointerIndex: Int): Float {
        val dev = event.getDevice()
        when (event.getActionMasked()) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT -> {
                // Hover events report distance
                if (dev != null) {
                    val distanceRange =
                        dev.getMotionRange(MotionEvent.AXIS_DISTANCE, event.getSource())
                    if (distanceRange != null) {
                        return normalizeValueInRange(
                            event.getAxisValue(
                                MotionEvent.AXIS_DISTANCE,
                                pointerIndex
                            ), distanceRange
                        )
                    }
                }
                return 0.0f
            }

            else ->                 // Other events report pressure
                return event.getPressure(pointerIndex)
        }
    }

    fun getRotationDegrees(event: MotionEvent, pointerIndex: Int): Short {
        val dev = event.getDevice()
        if (dev != null) {
            if (dev.getMotionRange(MotionEvent.AXIS_ORIENTATION, event.getSource()) != null) {
                var rotationDegrees =
                    Math.toDegrees(event.getOrientation(pointerIndex).toDouble()).toInt().toShort()
                if (rotationDegrees < 0) {
                    rotationDegrees = (rotationDegrees + 360).toShort()
                }
                return rotationDegrees
            }
        }
        return MoonBridge.LI_ROT_UNKNOWN
    }

    fun polarToCartesian(r: Float, theta: Float): FloatArray? {
        return floatArrayOf(
            (r * cos(theta.toDouble())).toFloat(),
            (r * sin(theta.toDouble())).toFloat()
        )
    }

    fun cartesianToR(point: FloatArray): Float {
        return sqrt(point[0].toDouble().pow(2.0) + point[1].toDouble().pow(2.0)).toFloat()
    }

    fun getStreamViewNormalizedContactArea(event: MotionEvent, pointerIndex: Int): FloatArray? {
        val orientation: Float

        // If the orientation is unknown, we'll just assume it's at a 45 degree angle and scale it by
        // X and Y scaling factors evenly.
        if (event.getDevice() == null || event.getDevice()
                .getMotionRange(MotionEvent.AXIS_ORIENTATION, event.getSource()) == null
        ) {
            orientation = (Math.PI / 4).toFloat()
        } else {
            orientation = event.getOrientation(pointerIndex)
        }

        val contactAreaMajor: Float
        val contactAreaMinor: Float
        when (event.getActionMasked()) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT -> {
                contactAreaMajor = event.getToolMajor(pointerIndex)
                contactAreaMinor = event.getToolMinor(pointerIndex)
            }

            else -> {
                contactAreaMajor = event.getTouchMajor(pointerIndex)
                contactAreaMinor = event.getTouchMinor(pointerIndex)
            }
        }

        // The contact area major axis is parallel to the orientation, so we simply convert
        // polar to cartesian coordinates using the orientation as theta.
        val contactAreaMajorCartesian: FloatArray =
            polarToCartesian(contactAreaMajor, orientation)!!

        // The contact area minor axis is perpendicular to the contact area major axis (and thus
        // the orientation), so rotate the orientation angle by 90 degrees.
        val contactAreaMinorCartesian: FloatArray =
            polarToCartesian(contactAreaMinor, (orientation + (Math.PI / 2)).toFloat())!!

        // Normalize the contact area to the stream view size
        contactAreaMajorCartesian[0] = min(
            abs(contactAreaMajorCartesian[0]),
            streamView!!.getWidth().toFloat()
        ) / streamView!!.getWidth()
        contactAreaMinorCartesian[0] = min(
            abs(contactAreaMinorCartesian[0]),
            streamView!!.getWidth().toFloat()
        ) / streamView!!.getWidth()
        contactAreaMajorCartesian[1] = min(
            abs(contactAreaMajorCartesian[1]),
            streamView!!.getHeight().toFloat()
        ) / streamView!!.getHeight()
        contactAreaMinorCartesian[1] = min(
            abs(contactAreaMinorCartesian[1]),
            streamView!!.getHeight().toFloat()
        ) / streamView!!.getHeight()

        // Convert the normalized values back into polar coordinates
        return floatArrayOf(
            cartesianToR(contactAreaMajorCartesian),
            cartesianToR(contactAreaMinorCartesian)
        )
    }

    fun sendPenEventForPointer(
        view: View?,
        event: MotionEvent,
        eventType: Byte,
        toolType: Byte,
        pointerIndex: Int
    ): Boolean {
        var penButtons: Byte = 0
        if ((event.getButtonState() and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) {
            penButtons = (penButtons.toInt() or MoonBridge.LI_PEN_BUTTON_PRIMARY.toInt()).toByte()
        }
        if ((event.getButtonState() and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0) {
            penButtons = (penButtons.toInt() or MoonBridge.LI_PEN_BUTTON_SECONDARY.toInt()).toByte()
        }

        var tiltDegrees = MoonBridge.LI_TILT_UNKNOWN
        val dev = event.getDevice()
        if (dev != null) {
            if (dev.getMotionRange(MotionEvent.AXIS_TILT, event.getSource()) != null) {
                tiltDegrees = Math.toDegrees(
                    event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex).toDouble()
                ).toInt().toByte()
            }
        }

        val normalizedCoords: FloatArray =
            getStreamViewRelativeNormalizedXY(view, event, pointerIndex)!!
        val normalizedContactArea: FloatArray =
            getStreamViewNormalizedContactArea(event, pointerIndex)!!
        return conn.sendPenEvent(
            eventType, toolType, penButtons,
            normalizedCoords[0], normalizedCoords[1],
            getPressureOrDistance(event, pointerIndex),
            normalizedContactArea[0], normalizedContactArea[1],
            getRotationDegrees(event, pointerIndex), tiltDegrees
        ) != MoonBridge.LI_ERR_UNSUPPORTED
    }

    fun convertToolTypeToStylusToolType(event: MotionEvent, pointerIndex: Int): Byte {
        when (event.getToolType(pointerIndex)) {
            MotionEvent.TOOL_TYPE_ERASER -> return MoonBridge.LI_TOOL_TYPE_ERASER
            MotionEvent.TOOL_TYPE_STYLUS -> return MoonBridge.LI_TOOL_TYPE_PEN
            else -> return MoonBridge.LI_TOOL_TYPE_UNKNOWN
        }
    }

    fun trySendPenEvent(view: View?, event: MotionEvent): Boolean {
        val eventType = getLiTouchTypeFromEvent(event)
        if (eventType < 0) {
            return false
        }

        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            // Move events may impact all active pointers
            var handledStylusEvent = false
            for (i in 0..<event.getPointerCount()) {
                val toolType = convertToolTypeToStylusToolType(event, i)
                if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN) {
                    // Not a stylus pointer, so skip it
                    continue
                } else {
                    // This pointer is a stylus, so we'll report that we handled this event
                    handledStylusEvent = true
                }

                if (!sendPenEventForPointer(view, event, eventType, toolType, i)) {
                    // Pen events aren't supported by the host
                    return false
                }
            }
            return handledStylusEvent
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            // Cancel impacts all active pointers
            return conn.sendPenEvent(
                MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, MoonBridge.LI_TOOL_TYPE_UNKNOWN, 0.toByte(),
                0f, 0f, 0f, 0f, 0f,
                MoonBridge.LI_ROT_UNKNOWN, MoonBridge.LI_TILT_UNKNOWN
            ) != MoonBridge.LI_ERR_UNSUPPORTED
        } else {
            // Up, Down, and Hover events are specific to the action index
            val toolType = convertToolTypeToStylusToolType(event, event.getActionIndex())
            if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN) {
                // Not a stylus event
                return false
            }
            return sendPenEventForPointer(view, event, eventType, toolType, event.getActionIndex())
        }
    }

    fun sendTouchEventForPointer(
        view: View?,
        event: MotionEvent,
        eventType: Byte,
        pointerIndex: Int
    ): Boolean {
        val normalizedCoords: FloatArray =
            getStreamViewRelativeNormalizedXY(view, event, pointerIndex)!!
        val normalizedContactArea: FloatArray =
            getStreamViewNormalizedContactArea(event, pointerIndex)!!
        return conn.sendTouchEvent(
            eventType, event.getPointerId(pointerIndex),
            normalizedCoords[0], normalizedCoords[1],
            getPressureOrDistance(event, pointerIndex),
            normalizedContactArea[0], normalizedContactArea[1],
            getRotationDegrees(event, pointerIndex)
        ) != MoonBridge.LI_ERR_UNSUPPORTED
    }

    fun trySendTouchEvent(view: View?, event: MotionEvent): Boolean {
        val eventType = getLiTouchTypeFromEvent(event)
        if (eventType < 0) {
            return false
        }

        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            // Move events may impact all active pointers
            for (i in 0..<event.getPointerCount()) {
                if (!sendTouchEventForPointer(view, event, eventType, i)) {
                    return false
                }
            }
            return true
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            // Cancel impacts all active pointers
            return conn.sendTouchEvent(
                MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, 0,
                0f, 0f, 0f, 0f, 0f,
                MoonBridge.LI_ROT_UNKNOWN
            ) != MoonBridge.LI_ERR_UNSUPPORTED
        } else {
            // Up, Down, and Hover events are specific to the action index
            return sendTouchEventForPointer(view, event, eventType, event.getActionIndex())
        }
    }

    fun getTouchContext(actionIndex: Int): TouchContext? {
        if (actionIndex < touchContextMap.size) {
            return touchContextMap[actionIndex]
        } else {
            return null
        }
    }

    fun updateMousePosition(touchedView: View?, event: MotionEvent) {
        // X and Y are already relative to the provided view object
        var eventX: Float
        var eventY: Float

        // For our StreamView itself, we can use the coordinates unmodified.
        if (touchedView === streamView) {
            eventX = event.getX(0)
            eventY = event.getY(0)
        } else {
            // For the containing background view, we must subtract the origin
            // of the StreamView to get video-relative coordinates.
            eventX = event.getX(0) - streamView!!.getX()
            eventY = event.getY(0) - streamView!!.getY()
        }

        if (event.getPointerCount() == 1 && event.getActionIndex() == 0 &&
            (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
                    event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS)
        ) {
            when (event.getActionMasked()) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_EXIT, MotionEvent.ACTION_HOVER_MOVE -> if (event.getEventTime() - lastAbsTouchUpTime <= STYLUS_UP_DEAD_ZONE_DELAY &&
                    sqrt(
                        (eventX - lastAbsTouchUpX).toDouble()
                            .pow(2.0) + (eventY - lastAbsTouchUpY).toDouble().pow(2.0)
                    ) <= STYLUS_UP_DEAD_ZONE_RADIUS
                ) {
                    // Enforce a small deadzone between touch up and hover or touch down to allow more precise double-clicking
                    return
                }

                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if (event.getEventTime() - lastAbsTouchDownTime <= STYLUS_DOWN_DEAD_ZONE_DELAY &&
                    sqrt(
                        (eventX - lastAbsTouchDownX).toDouble()
                            .pow(2.0) + (eventY - lastAbsTouchDownY).toDouble().pow(2.0)
                    ) <= STYLUS_DOWN_DEAD_ZONE_RADIUS
                ) {
                    // Enforce a small deadzone between touch down and move or touch up to allow more precise double-clicking
                    return
                }
            }
        }

        // We may get values slightly outside our view region on ACTION_HOVER_ENTER and ACTION_HOVER_EXIT.
        // Normalize these to the view size. We can't just drop them because we won't always get an event
        // right at the boundary of the view, so dropping them would result in our cursor never really
        // reaching the sides of the screen.
        eventX = min(max(eventX, 0f), streamView!!.getWidth().toFloat())
        eventY = min(max(eventY, 0f), streamView!!.getHeight().toFloat())

        conn.sendMousePosition(
            eventX.toInt().toShort(),
            eventY.toInt().toShort(),
            streamView!!.getWidth().toShort(),
            streamView!!.getHeight().toShort()
        )
    }

    fun handleMotionEvent(view: View?, event: MotionEvent): Boolean {
        // Pass through mouse/touch/joystick input if we're not grabbing
        if (!grabbedInput) {
            return false
        }

        val eventSource = event.getSource()
        val deviceSources = if (event.getDevice() != null) event.getDevice().getSources() else 0
        if ((eventSource and InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
            if (controllerHandler!!.handleMotionEvent(event)) {
                return true
            }
        } else if ((deviceSources and InputDevice.SOURCE_CLASS_JOYSTICK) != 0 && controllerHandler!!.tryHandleTouchpadEvent(
                event
            )
        ) {
            return true
        } else if ((eventSource and InputDevice.SOURCE_CLASS_POINTER) != 0 || (eventSource and InputDevice.SOURCE_CLASS_POSITION) != 0 || eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) {
            // This case is for mice and non-finger touch devices
            if (eventSource == InputDevice.SOURCE_MOUSE || (eventSource and InputDevice.SOURCE_CLASS_POSITION) != 0 ||  // SOURCE_TOUCHPAD
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE ||
                (event.getPointerCount() >= 1 &&
                        (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE || event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(
                            0
                        ) == MotionEvent.TOOL_TYPE_ERASER)) || eventSource == 12290
            )  // 12290 = Samsung DeX mode desktop mouse
            {
                var buttonState = event.getButtonState()
                var changedButtons = buttonState xor lastButtonState

                // The DeX touchpad on the Fold 4 sends proper right click events using BUTTON_SECONDARY,
                // but doesn't send BUTTON_PRIMARY for a regular click. Instead it sends ACTION_DOWN/UP,
                // so we need to fix that up to look like a sane input event to process it correctly.
                if (eventSource == 12290) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        buttonState = buttonState or MotionEvent.BUTTON_PRIMARY
                    } else if (event.getAction() == MotionEvent.ACTION_UP) {
                        buttonState = buttonState and MotionEvent.BUTTON_PRIMARY.inv()
                    } else {
                        // We may be faking the primary button down from a previous event,
                        // so be sure to add that bit back into the button state.
                        buttonState =
                            buttonState or (lastButtonState and MotionEvent.BUTTON_PRIMARY)
                    }

                    changedButtons = buttonState xor lastButtonState
                }

                // Ignore mouse input if we're not capturing from our input source
                if (!inputCaptureProvider!!.isCapturingActive()) {
                    // We return true here because otherwise the events may end up causing
                    // Android to synthesize d-pad events.
                    return true
                }

                // Always update the position before sending any button events. If we're
                // dealing with a stylus without hover support, our position might be
                // significantly different than before.
                if (inputCaptureProvider!!.eventHasRelativeMouseAxes(event)) {
                    // Send the deltas straight from the motion event
                    val deltaX = inputCaptureProvider!!.getRelativeAxisX(event).toInt().toShort()
                    val deltaY = inputCaptureProvider!!.getRelativeAxisY(event).toInt().toShort()

                    if (deltaX.toInt() != 0 || deltaY.toInt() != 0) {
                        if (prefConfig!!.absoluteMouseMode) {
                            // NB: view may be null, but we can unconditionally use streamView because we don't need to adjust
                            // relative axis deltas for the position of the streamView within the parent's coordinate system.
                            conn.sendMouseMoveAsMousePosition(
                                deltaX,
                                deltaY,
                                streamView!!.getWidth().toShort(),
                                streamView!!.getHeight().toShort()
                            )
                        } else {
                            conn.sendMouseMove(deltaX, deltaY)
                        }
                    }
                } else if ((eventSource and InputDevice.SOURCE_CLASS_POSITION) != 0) {
                    // If this input device is not associated with the view itself (like a trackpad),
                    // we'll convert the device-specific coordinates to use to send the cursor position.
                    // This really isn't ideal but it's probably better than nothing.
                    //
                    // Trackpad on newer versions of Android (Oreo and later) should be caught by the
                    // relative axes case above. If we get here, we're on an older version that doesn't
                    // support pointer capture.
                    val device = event.getDevice()
                    if (device != null) {
                        val xRange = device.getMotionRange(MotionEvent.AXIS_X, eventSource)
                        val yRange = device.getMotionRange(MotionEvent.AXIS_Y, eventSource)

                        // All touchpads coordinate planes should start at (0, 0)
                        if (xRange != null && yRange != null && xRange.getMin() == 0f && yRange.getMin() == 0f) {
                            val xMax = xRange.getMax().toInt()
                            val yMax = yRange.getMax().toInt()

                            // Touchpads must be smaller than (65535, 65535)
                            if (xMax <= Short.Companion.MAX_VALUE && yMax <= Short.Companion.MAX_VALUE) {
                                conn.sendMousePosition(
                                    event.getX().toInt().toShort(), event.getY().toInt().toShort(),
                                    xMax.toShort(), yMax.toShort()
                                )
                            }
                        }
                    }
                } else if (view != null && trySendPenEvent(view, event)) {
                    // If our host supports pen events, send it directly
                    return true
                } else if (view != null) {
                    // Otherwise send absolute position based on the view for SOURCE_CLASS_POINTER
                    updateMousePosition(view, event)
                }

                if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                    // Send the vertical scroll packet
                    conn.sendMouseHighResScroll(
                        (event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120).toInt().toShort()
                    )
                    conn.sendMouseHighResHScroll(
                        (event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120).toInt().toShort()
                    )
                }

                if ((changedButtons and MotionEvent.BUTTON_PRIMARY) != 0) {
                    if ((buttonState and MotionEvent.BUTTON_PRIMARY) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
                    } else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
                    }
                }

                // Mouse secondary or stylus primary is right click (stylus down is left click)
                if ((changedButtons and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
                    if ((buttonState and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
                    } else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
                    }
                }

                // Mouse tertiary or stylus secondary is middle click
                if ((changedButtons and (MotionEvent.BUTTON_TERTIARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
                    if ((buttonState and (MotionEvent.BUTTON_TERTIARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE)
                    } else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE)
                    }
                }

                if (prefConfig!!.mouseNavButtons) {
                    if ((changedButtons and MotionEvent.BUTTON_BACK) != 0) {
                        if ((buttonState and MotionEvent.BUTTON_BACK) != 0) {
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_X1)
                        } else {
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X1)
                        }
                    }

                    if ((changedButtons and MotionEvent.BUTTON_FORWARD) != 0) {
                        if ((buttonState and MotionEvent.BUTTON_FORWARD) != 0) {
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_X2)
                        } else {
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X2)
                        }
                    }
                }

                // Handle stylus presses
                if (event.getPointerCount() == 1 && event.getActionIndex() == 0) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                            lastAbsTouchDownTime = event.getEventTime()
                            lastAbsTouchDownX = event.getX(0)
                            lastAbsTouchDownY = event.getY(0)

                            // Stylus is left click
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
                        } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                            lastAbsTouchDownTime = event.getEventTime()
                            lastAbsTouchDownX = event.getX(0)
                            lastAbsTouchDownY = event.getY(0)

                            // Eraser is right click
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
                        }
                    } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                            lastAbsTouchUpTime = event.getEventTime()
                            lastAbsTouchUpX = event.getX(0)
                            lastAbsTouchUpY = event.getY(0)

                            // Stylus is left click
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
                        } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                            lastAbsTouchUpTime = event.getEventTime()
                            lastAbsTouchUpX = event.getX(0)
                            lastAbsTouchUpY = event.getY(0)

                            // Eraser is right click
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
                        }
                    }
                }

                lastButtonState = buttonState
            } else {
                if (virtualController != null &&
                    (virtualController!!.getControllerMode() == VirtualController.ControllerMode.MoveButtons ||
                            virtualController!!.getControllerMode() == VirtualController.ControllerMode.ResizeButtons)
                ) {
                    // Ignore presses when the virtual controller is being configured
                    return true
                }

                // If this is the parent view, we'll offset our coordinates to appear as if they
                // are relative to the StreamView like our StreamView touch events are.
                val xOffset: Float
                val yOffset: Float
                if (view !== streamView && !prefConfig!!.touchscreenTrackpad) {
                    xOffset = -streamView!!.getX()
                    yOffset = -streamView!!.getY()
                } else {
                    xOffset = 0f
                    yOffset = 0f
                }

                val actionIndex = event.getActionIndex()

                val eventX = (event.getX(actionIndex) + xOffset).toInt()
                val eventY = (event.getY(actionIndex) + yOffset).toInt()

                // Special handling for 3 finger gesture
                if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN &&
                    event.getPointerCount() == 3
                ) {
                    // Three fingers down
                    threeFingerDownTime = event.getEventTime()

                    // Cancel the first and second touches to avoid
                    // erroneous events
                    for (aTouchContext in touchContextMap) {
                        aTouchContext!!.cancelTouch()
                    }

                    return true
                }

                // TODO: Re-enable native touch when have a better solution for handling
                // cancelled touches from Android gestures and 3 finger taps to activate
                // the software keyboard.
                /*if (!prefConfig.touchscreenTrackpad && trySendTouchEvent(view, event)) {
                    // If this host supports touch events and absolute touch is enabled,
                    // send it directly as a touch event.
                    return true;
                }*/
                val context: TouchContext? = getTouchContext(actionIndex)
                if (context == null) {
                    return false
                }

                when (event.getActionMasked()) {
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_DOWN -> {
                        for (touchContext in touchContextMap) {
                            touchContext!!.setPointerCount(event.getPointerCount())
                        }
                        context.touchDownEvent(eventX, eventY, event.getEventTime(), true)
                    }

                    MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                        if (event.getPointerCount() == 1 &&
                            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || (event.getFlags() and MotionEvent.FLAG_CANCELED) == 0)
                        ) {
                            // All fingers up
                            if (event.getEventTime() - threeFingerDownTime < THREE_FINGER_TAP_THRESHOLD) {
                                // This is a 3 finger tap to bring up the keyboard
                                toggleKeyboard()
                                return true
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (event.getFlags() and MotionEvent.FLAG_CANCELED) != 0) {
                            context.cancelTouch()
                        } else {
                            context.touchUpEvent(eventX, eventY, event.getEventTime())
                        }

                        for (touchContext in touchContextMap) {
                            touchContext!!.setPointerCount(event.getPointerCount() - 1)
                        }
                        if (actionIndex == 0 && event.getPointerCount() > 1 && !context.isCancelled()) {
                            // The original secondary touch now becomes primary
                            context.touchDownEvent(
                                (event.getX(1) + xOffset).toInt(),
                                (event.getY(1) + yOffset).toInt(),
                                event.getEventTime(), false
                            )
                        }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        // ACTION_MOVE is special because it always has actionIndex == 0
                        // We'll call the move handlers for all indexes manually

                        // First process the historical events
                        var i = 0
                        while (i < event.getHistorySize()) {
                            for (aTouchContextMap in touchContextMap) {
                                if (aTouchContextMap!!.getActionIndex() < event.getPointerCount()) {
                                    aTouchContextMap.touchMoveEvent(
                                        (event.getHistoricalX(
                                            aTouchContextMap.getActionIndex(),
                                            i
                                        ) + xOffset).toInt(),
                                        (event.getHistoricalY(
                                            aTouchContextMap.getActionIndex(),
                                            i
                                        ) + yOffset).toInt(),
                                        event.getHistoricalEventTime(i)
                                    )
                                }
                            }
                            i++
                        }

                        // Now process the current values
                        for (aTouchContextMap in touchContextMap) {
                            if (aTouchContextMap!!.getActionIndex() < event.getPointerCount()) {
                                aTouchContextMap.touchMoveEvent(
                                    (event.getX(aTouchContextMap.getActionIndex()) + xOffset).toInt(),
                                    (event.getY(aTouchContextMap.getActionIndex()) + yOffset).toInt(),
                                    event.getEventTime()
                                )
                            }
                        }
                    }

                    MotionEvent.ACTION_CANCEL -> for (aTouchContext in touchContextMap) {
                        aTouchContext!!.cancelTouch()
                        aTouchContext.setPointerCount(0)
                    }

                    else -> return false
                }
            }

            // Handled a known source
            return true
        }

        // Unknown class
        return false
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        return handleMotionEvent(null, event!!) || super.onGenericMotionEvent(event)
    }

    public override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            // Tell the OS not to buffer input events for us
            //
            // NB: This is still needed even when we call the newer requestUnbufferedDispatch()!
            view.requestUnbufferedDispatch(event)
        }

        return handleMotionEvent(view, event)
    }

    fun connectionTerminated (errorCode : Int) {
        // Perform a connection test if the failure could be due to a blocked port
        // This does network I/O, so don't do it on the main thread.
//        var portFlags = MoonBridge.getPortFlagsFromTerminationErrorCode(errorCode);
//        var portTestResult = MoonBridge.testClientConnectivity(ServerHelper.CONNECTION_TEST_SERVER,443, portFlags);
//
//        runOnUiThread(new Runnable() {
//            @Override
//            public void run() {
//                // Let the display go to sleep now
//                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
//
//                // Stop processing controller input
//                controllerHandler.stop();
//
//                // Ungrab input
//                setInputGrabState(false);
//
//                if (!displayedFailureDialog) {
//                    displayedFailureDialog = true;
//                    LimeLog.severe("Connection terminated: " + errorCode);
//                    stopConnection();
//
//                    // Display the error dialog if it was an unexpected termination.
//                    // Otherwise, just finish the activity immediately.
//                    if (errorCode != MoonBridge.ML_ERROR_GRACEFUL_TERMINATION) {
//                        String message;
//
//                        if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0) {
//                            // If we got a blocked result, that supersedes any other error message
//                            message = getResources().getString(R.string.nettest_text_blocked);
//                        }
//                        else {
//                            switch (errorCode) {
//                                case MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC:
//                                    message = getResources().getString(R.string.no_video_received_error);
//                                    break;
//
//                                case MoonBridge.ML_ERROR_NO_VIDEO_FRAME:
//                                    message = getResources().getString(R.string.no_frame_received_error);
//                                    break;
//
//                                case MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION:
//                                case MoonBridge.ML_ERROR_PROTECTED_CONTENT:
//                                    message = getResources().getString(R.string.early_termination_error);
//                                    break;
//
//                                case MoonBridge.ML_ERROR_FRAME_CONVERSION:
//                                    message = getResources().getString(R.string.frame_conversion_error);
//                                    break;
//
//                                default:
//                                    String errorCodeString;
//                                    // We'll assume large errors are hex values
//                                    if (Math.abs(errorCode) > 1000) {
//                                        errorCodeString = Integer.toHexString(errorCode);
//                                    }
//                                    else {
//                                        errorCodeString = Integer.toString(errorCode);
//                                    }
//                                    message = getResources().getString(R.string.conn_terminated_msg) + "\n\n" +
//                                            getResources().getString(R.string.error_code_prefix) + " " + errorCodeString;
//                                    break;
//                            }
//                        }
//
//                        if (portFlags != 0) {
//                            message += "\n\n" + getResources().getString(R.string.check_ports_msg) + "\n" +
//                                    MoonBridge.stringifyPortFlags(portFlags, "\n");
//                        }
//
//                        Dialog.displayDialog(Game.this, getResources().getString(R.string.conn_terminated_title),
//                                message, true);
//                    }
//                    else {
//                        finish();
//                    }
//                }
//            }
//        });
//        TODO: connectionTerminated
    }

    fun connectionStarted() {
//        runOnUiThread(object : Runnable {
//            override fun run() {
//                if (spinner != null) {
//                    spinner.dismiss()
//                    spinner = null
//                }
//
//                connected = true
//                connecting = false
//                updatePipAutoEnter()
//
//                // Hide the mouse cursor now after a short delay.
//                // Doing it before dismissing the spinner seems to be undone
//                // when the spinner gets displayed. On Android Q, even now
//                // is too early to capture. We will delay a second to allow
//                // the spinner to dismiss before capturing.
//                val h = Handler()
//                h.postDelayed(object : Runnable {
//                    override fun run() {
//                        setInputGrabState(true)
//                    }
//                }, 500)
//
//                // Keep the display on
//                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
//
//                // Update GameManager state to indicate we're in game
//                UiHelper.notifyStreamConnected(this@Game)
//
//                hideSystemUi(1000)
//            }
//        })

        // Report this shortcut being used (off the main thread to prevent ANRs)
//        val computer: ComputerDetails = ComputerDetails()
//        computer.name = pcName
//        computer.uuid = this@Game.getIntent().getStringExtra(EXTRA_PC_UUID)
//        val shortcutHelper: ShortcutHelper = ShortcutHelper(this)
//        shortcutHelper.reportComputerShortcutUsed(computer)
//        if (appName != null) {
//            // This may be null if launched from the "Resume Session" PC context menu item
//            shortcutHelper.reportGameLaunched(computer, app)
//        }
//        TODO: connectionStarted
    }

    fun displayMessage(message: String?) {
        runOnUiThread(object : Runnable {
            override fun run() {
                Toast.makeText(this@Game, message, Toast.LENGTH_LONG).show()
            }
        })
    }

    fun displayTransientMessage(message: String?) {
        if (!prefConfig!!.disableWarnings) {
            runOnUiThread(object : Runnable {
                override fun run() {
                    Toast.makeText(this@Game, message, Toast.LENGTH_LONG).show()
                }
            })
        }
    }

    fun rumble(controllerNumber : Short, lowFreqMotor : Short, highFreqMotor : Short) {
//        LimeLog.info(String.format((Locale)null, "Rumble on gamepad %d: %04x %04x", controllerNumber, lowFreqMotor, highFreqMotor));
//        TODO: LimeLog
        controllerHandler?.handleRumble(controllerNumber, lowFreqMotor, highFreqMotor);
    }

    fun rumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {
        LimeLog.info(
            String.format(
                null as Locale?,
                "Rumble on gamepad triggers %d: %04x %04x",
                controllerNumber,
                leftTrigger,
                rightTrigger
            )
        )

        controllerHandler!!.handleRumbleTriggers(controllerNumber, leftTrigger, rightTrigger)
    }

    fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        LimeLog.info("Display HDR mode: " + (if (enabled) "enabled" else "disabled"))
        decoderRenderer!!.setHdrMode(enabled, hdrMetadata)
    }

    fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) {
        controllerHandler!!.handleSetMotionEventState(controllerNumber, motionType, reportRateHz)
    }


    fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) {
        controllerHandler!!.handleSetControllerLED(controllerNumber, r, g, b)
    }

    fun surfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
        check(surfaceCreated) { "Surface changed before creation!" }

        if (!attemptedConnection) {
            attemptedConnection = true

            // Update GameManager state to indicate we're "loading" while connecting
//            UiHelper.notifyStreamConnecting(this@Game)
//            TODO: notifyStreamConnecting

            decoderRenderer!!.setRenderTarget(holder)
//            conn.start(
//                AndroidAudioRenderer(this@Game, prefConfig!!.enableAudioFx),
//                decoderRenderer, this@Game
//            )
//            TODO: AndroidAudioRenderer
        }
    }



    override fun onGenericMotion(
        v: View?,
        event: MotionEvent?
    ): Boolean {
        TODO("Not yet implemented")
    }


    fun mayReduceRefreshRate(): Boolean {
        return false
    }

    fun prepareDisplayForRendering() : Float {
        var display = getWindowManager().getDefaultDisplay();
        var windowLayoutParams = getWindow().getAttributes();
        var displayRefreshRate : Float;

        // On M, we can explicitly set the optimal display mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var bestMode = display.getMode();
            var isNativeResolutionStream = PreferenceConfiguration.isNativeResolution(prefConfig!!.width, prefConfig!!.height);
            var refreshRateIsGood = isRefreshRateGoodMatch(bestMode.getRefreshRate());
            var refreshRateIsEqual = isRefreshRateEqualMatch(bestMode.getRefreshRate());

            LimeLog.info("Current display mode: "+bestMode.getPhysicalWidth()+"x"+
                    bestMode.getPhysicalHeight()+"x"+bestMode.getRefreshRate());

            for (candidate in display.getSupportedModes()) {
                val refreshRateReduced = candidate.getRefreshRate() < bestMode.getRefreshRate()
                val resolutionReduced =
                    candidate.getPhysicalWidth() < bestMode.getPhysicalWidth() ||
                            candidate.getPhysicalHeight() < bestMode.getPhysicalHeight()
                val resolutionFitsStream = candidate.getPhysicalWidth() >= prefConfig!!.width &&
                        candidate.getPhysicalHeight() >= prefConfig!!.height

                LimeLog.info(
                    "Examining display mode: " + candidate.getPhysicalWidth() + "x" +
                            candidate.getPhysicalHeight() + "x" + candidate.getRefreshRate()
                )

                if (candidate.getPhysicalWidth() > 4096 && prefConfig!!.width <= 4096) {
                    // Avoid resolutions options above 4K to be safe
                    continue
                }

                // On non-4K streams, we force the resolution to never change unless it's above
                // 60 FPS, which may require a resolution reduction due to HDMI bandwidth limitations,
                // or it's a native resolution stream.
                if (prefConfig!!.width < 3840 && prefConfig!!.fps <= 60 && !isNativeResolutionStream) {
                    if (display.getMode().getPhysicalWidth() !== candidate.getPhysicalWidth() ||
                        display.getMode().getPhysicalHeight() !== candidate.getPhysicalHeight()
                    ) {
                        continue
                    }
                }

                // Make sure the resolution doesn't regress unless if it's over 60 FPS
                // where we may need to reduce resolution to achieve the desired refresh rate.
                if (resolutionReduced && !(prefConfig!!.fps > 60 && resolutionFitsStream)) {
                    continue
                }

                if (mayReduceRefreshRate() && refreshRateIsEqual && !isRefreshRateEqualMatch(
                        candidate.getRefreshRate()
                    )
                ) {
                    // If we had an equal refresh rate and this one is not, skip it. In min latency
                    // mode, we want to always prefer the highest frame rate even though it may cause
                    // microstuttering.
                    continue
                } else if (refreshRateIsGood) {
                    // We've already got a good match, so if this one isn't also good, it's not
                    // worth considering at all.
                    if (!isRefreshRateGoodMatch(candidate.getRefreshRate())) {
                        continue
                    }

                    if (mayReduceRefreshRate()) {
                        // User asked for the lowest possible refresh rate, so don't raise it if we
                        // have a good match already
                        if (candidate.getRefreshRate() > bestMode.getRefreshRate()) {
                            continue
                        }
                    } else {
                        // User asked for the highest possible refresh rate, so don't reduce it if we
                        // have a good match already
                        if (refreshRateReduced) {
                            continue
                        }
                    }
                } else if (!isRefreshRateGoodMatch(candidate.getRefreshRate())) {
                    // We didn't have a good match and this match isn't good either, so just don't
                    // reduce the refresh rate.
                    if (refreshRateReduced) {
                        continue
                    }
                } else {
                    // We didn't have a good match and this match is good. Prefer this refresh rate
                    // even if it reduces the refresh rate. Lowering the refresh rate can be beneficial
                    // when streaming a 60 FPS stream on a 90 Hz device. We want to select 60 Hz to
                    // match the frame rate even if the active display mode is 90 Hz.
                }

                bestMode = candidate
                refreshRateIsGood = isRefreshRateGoodMatch(candidate.getRefreshRate())
                refreshRateIsEqual = isRefreshRateEqualMatch(candidate.getRefreshRate())
            }

            LimeLog.info("Best display mode: "+bestMode.getPhysicalWidth()+"x"+
                    bestMode.getPhysicalHeight()+"x"+bestMode.getRefreshRate());

            // Only apply new window layout parameters if we've actually changed the display mode
            if (display.getMode().getModeId() != bestMode.getModeId()) {
                // If we only changed refresh rate and we're on an OS that supports Surface.setFrameRate()
                // use that instead of using preferredDisplayModeId to avoid the possibility of triggering
                // bugs that can cause the system to switch from 4K60 to 4K24 on Chromecast 4K.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        display.getMode().getPhysicalWidth() != bestMode.getPhysicalWidth() ||
                        display.getMode().getPhysicalHeight() != bestMode.getPhysicalHeight()) {
                    // Apply the display mode change
                    windowLayoutParams.preferredDisplayModeId = bestMode.getModeId();
                    getWindow().setAttributes(windowLayoutParams);
                }
                else {
                    LimeLog.info("Using setFrameRate() instead of preferredDisplayModeId due to matching resolution");
                }
            }
            else {
                LimeLog.info("Current display mode is already the best display mode");
            }

            displayRefreshRate = bestMode.getRefreshRate();
        }
        // On L, we can at least tell the OS that we want a refresh rate
        else {
            var bestRefreshRate = display.getRefreshRate();
            for (candidate in display.getSupportedRefreshRates()) {
                LimeLog.info("Examining refresh rate: "+candidate);

                if (candidate > bestRefreshRate) {
                    // Ensure the frame rate stays around 60 Hz for <= 60 FPS streams
                    if (prefConfig!!.fps <= 60) {
                        if (candidate >= 63) {
                            continue;
                        }
                    }

                    bestRefreshRate = candidate;
                }
            }

            LimeLog.info("Selected refresh rate: "+bestRefreshRate);
            windowLayoutParams.preferredRefreshRate = bestRefreshRate;
            displayRefreshRate = bestRefreshRate;

            // Apply the refresh rate change
            getWindow().setAttributes(windowLayoutParams);
        }

        // Until Marshmallow, we can't ask for a 4K display mode, so we'll
        // need to hint the OS to provide one.
        var aspectRatioMatch = false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // We'll calculate whether we need to scale by aspect ratio. If not, we'll use
            // setFixedSize so we can handle 4K properly. The only known devices that have
            // >= 4K screens have exactly 4K screens, so we'll be able to hit this good path
            // on these devices. On Marshmallow, we can start changing to 4K manually but no
            // 4K devices run 6.0 at the moment.
            var screenSize = Point(0, 0);
            display.getSize(screenSize);

            var screenAspectRatio = (screenSize.y) / screenSize.x;
            var streamAspectRatio = (prefConfig!!.height) / prefConfig!!.width;
            if (Math.abs(screenAspectRatio - streamAspectRatio) < 0.001) {
                LimeLog.info("Stream has compatible aspect ratio with output display");
                aspectRatioMatch = true;
            }
        }

        if (prefConfig!!.stretchVideo || aspectRatioMatch) {
            // Set the surface to the size of the video
            streamView!!.getHolder().setFixedSize(prefConfig!!.width, prefConfig!!.height);
        }
        else {
            // Set the surface to scale based on the aspect ratio of the stream
            streamView!!.setDesiredAspectRatio((prefConfig!!.width / prefConfig!!.height).toDouble());
        }

        // Set the desired refresh rate that will get passed into setFrameRate() later
        desiredRefreshRate = displayRefreshRate;

        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
                getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            // TVs may take a few moments to switch refresh rates, and we can probably assume
            // it will be eventually activated.
            // TODO: Improve this
            return displayRefreshRate;
        }
        else {
            // Use the lower of the current refresh rate and the selected refresh rate.
            // The preferred refresh rate may not actually be applied (ex: Battery Saver mode).
            return Math.min(getWindowManager().getDefaultDisplay().getRefreshRate(), displayRefreshRate);
        }
    }

    override fun mouseMove(deltaX: Int, deltaY: Int) {
        TODO("Not yet implemented")
    }

    override fun mouseButtonEvent(buttonId: Int, down: Boolean) {
        TODO("Not yet implemented")
    }

    override fun mouseVScroll(amount: Byte) {
        TODO("Not yet implemented")
    }

    override fun mouseHScroll(amount: Byte) {
        TODO("Not yet implemented")
    }

    override fun keyboardEvent(buttonDown: Boolean, keyCode: Short) {
        TODO("Not yet implemented")
    }

//    override fun toggleKeyboard() {
//        TODO("Not yet implemented")
//    }

    override fun onSystemUiVisibilityChange(visibility: Int) {
        TODO("Not yet implemented")
    }


    fun setPreferredOrientationForCurrentDisplay() {
        val display = windowManager.defaultDisplay
        if (PreferenceConfiguration.isSquarishScreen(display)) {
            var desiredOrientation = Configuration.ORIENTATION_UNDEFINED

            if (prefConfig?.onscreenController == true) {
                desiredOrientation = Configuration.ORIENTATION_LANDSCAPE
            }

        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        }
    }

    fun shouldIgnoreInsetsForResolution(width: Int, height: Int): Boolean {

        return false
    }
    
    fun handleMotionEvent(view: View, event: MotionEvent) : Boolean {

        return false
    }

    override fun onPerfUpdate(text: String?) {
        TODO("Not yet implemented")
    }

    fun onUsbPermissionPromptStarting() {
        // Disable PiP auto-enter while the USB permission prompt is on-screen. This prevents
        // us from entering PiP while the user is interacting with the OS permission dialog.
        suppressPipRefCount++
        updatePipAutoEnter()
    }

    fun onUsbPermissionPromptCompleted() {
        suppressPipRefCount--
        updatePipAutoEnter()
    }

    public override fun onKey(view: View?, keyCode: Int, keyEvent: KeyEvent): Boolean {
        when (keyEvent.getAction()) {
            KeyEvent.ACTION_DOWN -> return handleKeyDown(keyEvent)
            KeyEvent.ACTION_UP -> return handleKeyUp(keyEvent)
            KeyEvent.ACTION_MULTIPLE -> return handleKeyMultiple(keyEvent)
            else -> return false
        }
    }
}