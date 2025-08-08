package com.limelight.nvstream;


import java.io.IOException;
import java.net.InetAddress;

import javax.crypto.SecretKey;

import org.xmlpull.v1.XmlPullParserException;

import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;


public class NvConnection {

    public NvConnection()
    {
    }
    

    public void stop() {
    }


    private int detectServerConnectionType() {
        return -1;
    }
    
    private boolean startApp() throws XmlPullParserException, IOException
    {
        return true;
    }


    public void start(final AudioRenderer audioRenderer, final VideoDecoderRenderer videoDecoderRenderer)
    {
    }
    
    public void sendMouseMove(final short deltaX, final short deltaY)
    {
    }

    public void sendMousePosition(short x, short y, short referenceWidth, short referenceHeight)
    {
    }

    public void sendMouseMoveAsMousePosition(short deltaX, short deltaY, short referenceWidth, short referenceHeight)
    {
    }

    public void sendMouseButtonDown(final byte mouseButton)
    {
    }
    
    public void sendMouseButtonUp(final byte mouseButton)
    {
    }
    
    public void sendControllerInput(final short controllerNumber,
            final short activeGamepadMask, final int buttonFlags,
            final byte leftTrigger, final byte rightTrigger,
            final short leftStickX, final short leftStickY,
            final short rightStickX, final short rightStickY)
    {
    }

    public void sendKeyboardInput(final short keyMap, final byte keyDirection, final byte modifier, final byte flags) {
    }
    
    public void sendMouseScroll(final byte scrollClicks) {
    }

    public void sendMouseHScroll(final byte scrollClicks) {
    }

    public void sendMouseHighResScroll(final short scrollAmount) {
    }

    public void sendMouseHighResHScroll(final short scrollAmount) {
    }

    public int sendTouchEvent(byte eventType, int pointerId, float x, float y, float pressureOrDistance,
                              float contactAreaMajor, float contactAreaMinor, short rotation) {
    }

    public int sendPenEvent(byte eventType, byte toolType, byte penButtons, float x, float y,
                            float pressureOrDistance, float contactAreaMajor, float contactAreaMinor,
                            short rotation, byte tilt) {
    }

    public int sendControllerArrivalEvent(byte controllerNumber, short activeGamepadMask, byte type,
                                          int supportedButtonFlags, short capabilities) {
    }

    public int sendControllerTouchEvent(byte controllerNumber, byte eventType, int pointerId,
                                        float x, float y, float pressure) {
    }

    public int sendControllerMotionEvent(byte controllerNumber, byte motionType,
                                         float x, float y, float z) {
    }

    public void sendControllerBatteryEvent(byte controllerNumber, byte batteryState, byte batteryPercentage) {
    }

    public static String findExternalAddressForMdns(String stunHostname, int stunPort) {
    }
}
