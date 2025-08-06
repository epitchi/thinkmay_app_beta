package com.thinkmay.thinkmay_app_beta


import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.hardware.input.InputManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.preference.Preference
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.View.OnGenericMotionListener
import android.view.View.OnKeyListener
import android.view.View.OnTouchListener
import android.view.Surface
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout

import androidx.appcompat.app.AppCompatActivity

import com.limelight.binding.input.ControllerHandler
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.binding.input.touch.TouchContext
import com.limelight.binding.input.virtual_controller.VirtualController
import com.limelight.binding.video.MediaCodecDecoderRenderer
import com.limelight.ui.StreamView
import com.limelight.ui.StreamView.InputCallbacks
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.binding.input.capture.InputCaptureManager
import com.limelight.binding.input.capture.InputCaptureProvider
import com.limelight.binding.input.evdev.EvdevListener
import com.limelight.binding.input.touch.AbsoluteTouchContext
import com.limelight.binding.input.touch.RelativeTouchContext
import com.limelight.binding.video.CrashListener
import com.limelight.binding.video.MediaCodecHelper
import com.limelight.binding.video.PerfOverlayListener
import com.limelight.nvstream.NvConnection
import com.limelight.preferences.GlPreferences
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.GameGestures

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
    private val lastButtonState  = 0
    private val touchContextMap : Array<TouchContext?> = arrayOfNulls(2)
    private val threeFingerDownTime : Long = 0

    private var controllerHandler : ControllerHandler? = null
    private var keyboardTranslator : KeyboardTranslator? = null
    private var virtualController : VirtualController? = null

    companion object {
        const val REFERENCE_HORIZ_RES: Int = 1280
        const val REFERENCE_VERT_RES: Int = 720
        const val STYLUS_DOWN_DEAD_ZONE_DELAY: Int = 100
        const val STYLUS_DOWN_DEAD_ZONE_RADIUS: Int = 20
        const val STYLUS_UP_DEAD_ZONE_DELAY: Int = 150
        const val STYLUS_UP_DEAD_ZONE_RADIUS: Int = 50
        const val THREE_FINGER_TAP_THRESHOLD: Int = 300



    }

    private val connecting = false
    private val connected = false
    private val autoEnterPip = false
    private var surfaceCreated = false


    private var preferenceConfiguration: PreferenceConfiguration? = null
    private var tombstonePrefs : SharedPreferences? = null


    private var inputCaptureProvider : InputCaptureProvider? = null

    private var conn : NvConnection? = null
    private var streamView : StreamView? = null
    private val lastAbsTouchUpTime: Long = 0
    private val lastAbsTouchDownTime: Long = 0
    private val lastAbsTouchUpX = 0f
    private val lastAbsTouchUpY = 0f
    private val lastAbsTouchDownX = 0f
    private val lastAbsTouchDownY = 0f

    private var decoderRenderer : MediaCodecDecoderRenderer? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

        window.decorView.setOnSystemUiVisibilityChangeListener(this)

        volumeControlStream = AudioManager.STREAM_MUSIC

        setContentView(R.layout.activity_game)

        preferenceConfiguration = PreferenceConfiguration.readPreferences(this)
        tombstonePrefs = this.getSharedPreferences("DecoderTombstone",0)

        setPreferredOrientationForCurrentDisplay()


        if (preferenceConfiguration.stretchVideo || shouldIgnoreInsetsForResolution(
                preferenceConfiguration.width,
                preferenceConfiguration.height)) {
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
        if (preferenceConfiguration.enableHdr) {
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
                preferenceConfiguration,
                crashHandler,
                it,
                connMgr.isActiveNetworkMetered,
                willStreamHDR,
                glPrefs.glRenderer,
                this)
        }


        conn = NvConnection()
        controllerHandler = ControllerHandler(this,conn,this,preferenceConfiguration)
        keyboardTranslator = KeyboardTranslator()

        val inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(keyboardTranslator,null)

        for (i in 1..touchContextMap.size) {
            if (preferenceConfiguration?.touchscreenTrackpad == false) {
                touchContextMap[i] = AbsoluteTouchContext(conn,i,streamView)
            } else {
                touchContextMap[i] = RelativeTouchContext(conn,i,
                    REFERENCE_HORIZ_RES,
                    REFERENCE_VERT_RES,
                    streamView,
                    preferenceConfiguration)
            }
        }

        if (preferenceConfiguration?.onscreenController==true) {
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

    private fun handleKeyMultiple(event: KeyEvent): Boolean {
        return false
    }

    override fun handleKeyUp(event: KeyEvent): Boolean {
        return false
    }

    override fun handleKeyDown(event: KeyEvent): Boolean {
        return false
    }

    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        TODO("Not yet implemented")
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

    fun stopConnection() {

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

    override fun toggleKeyboard() {
        TODO("Not yet implemented")
    }

    override fun onSystemUiVisibilityChange(visibility: Int) {
        TODO("Not yet implemented")
    }


    fun setPreferredOrientationForCurrentDisplay() {
        val display = windowManager.defaultDisplay
        if (PreferenceConfiguration.isSquarishScreen(display)) {

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
}