package com.thinkmay.thinkmay_app_beta


import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.preference.Preference
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.View.OnGenericMotionListener
import android.view.View.OnKeyListener
import android.view.View.OnTouchListener
import android.view.Surface

import androidx.appcompat.app.AppCompatActivity

import com.limelight.binding.input.ControllerHandler
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.binding.input.touch.TouchContext
import com.limelight.binding.input.virtual_controller.VirtualController
import com.limelight.binding.video.MediaCodecDecoderRenderer
import com.limelight.ui.StreamView
import com.limelight.ui.StreamView.InputCallbacks
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.preferences.PreferenceConfiguration

class SecondActivity: AppCompatActivity(), SurfaceHolder.Callback, OnTouchListener, OnKeyListener, InputCallbacks, OnGenericMotionListener {

    private var attemptedConnection = false
    private val desiredFrameRate: Float = 0f
    private val lastButtonState  = 0
    private val touchContextMap : Array<TouchContext?> = arrayOfNulls(2)
    private val threeFingerDownTime : Long = 0

    private val controllerHandler : ControllerHandler? = null
    private val keyboardTranslator : KeyboardTranslator? = null
    private val virtualController : VirtualController? = null

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
    

    private val streamView : StreamView? = null
    private val lastAbsTouchUpTime: Long = 0
    private val lastAbsTouchDownTime: Long = 0
    private val lastAbsTouchUpX = 0f
    private val lastAbsTouchUpY = 0f
    private val lastAbsTouchDownX = 0f
    private val lastAbsTouchDownY = 0f

    private val decoderRenderer : MediaCodecDecoderRenderer? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_second)
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
}