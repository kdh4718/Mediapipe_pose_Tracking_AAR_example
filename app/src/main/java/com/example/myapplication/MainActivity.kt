/*
 * Copyright 2020 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.myapplication

import android.annotation.SuppressLint
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.google.mediapipe.components.CameraHelper.CameraFacing
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList
import com.example.myapplication.PoseLandMark
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark
import com.example.myapplication.MainActivity
import android.view.SurfaceView
import com.google.mediapipe.glutil.EglManager
import com.google.mediapipe.components.FrameProcessor
import com.google.mediapipe.components.ExternalTextureConverter
import android.content.pm.ApplicationInfo
import com.google.mediapipe.components.CameraXPreviewHelper
import android.os.Bundle
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import com.google.mediapipe.framework.AndroidAssetUtil
import com.google.mediapipe.framework.PacketCallback
import com.google.mediapipe.framework.PacketGetter
import android.view.SurfaceHolder
import android.view.View
import com.google.protobuf.InvalidProtocolBufferException
import com.example.myapplication.R
import com.google.mediapipe.components.CameraHelper.OnCameraStartedListener
import android.view.ViewGroup
import android.widget.TextView
import com.google.mediapipe.components.PermissionHelper
import com.google.mediapipe.framework.Packet
import org.w3c.dom.Text

import android.speech.tts.TextToSpeech
import android.util.TypedValue
import java.util.*
import kotlin.collections.ArrayList

/**
 * Main activity of MediaPipe example apps.
 */

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val BINARY_GRAPH_NAME = "pose_tracking_gpu.binarypb"
        private const val INPUT_VIDEO_STREAM_NAME = "input_video"
        private const val OUTPUT_VIDEO_STREAM_NAME = "output_video"
        private const val OUTPUT_LANDMARKS_STREAM_NAME = "pose_landmarks"
        private const val NUM_HANDS = 2
        private val CAMERA_FACING = CameraFacing.BACK
        private var tts : TextToSpeech? = null  // tts ??????

        var wrongLineLandMarkLeftx : ArrayList<Float> = ArrayList()
        var wrongLineLandMarkLefty : ArrayList<Float> = ArrayList()
        var wrongLineLandMarkRightx : ArrayList<Float> = ArrayList()
        var wrongLineLandMarkRighty : ArrayList<Float> = ArrayList()


        // Flips the camera-preview frames vertically before sending them into FrameProcessor to be
        // processed in a MediaPipe graph, and flips the processed frames back when they are displayed.
        // This is needed because OpenGL represents images assuming the image origin is at the bottom-left
        // corner, whereas MediaPipe in general assumes the image origin is at top-left.
        private const val FLIP_FRAMES_VERTICALLY = true

        //?????? ???????????? landmark??? ????????? ???????????? ??? ??????.
        //[0.0 , 1.0] ?????? normazlized ??? coordinate -> image width, height
        private fun getPoseLandmarksDebugString(poseLandmarks: NormalizedLandmarkList): String {
            val poseLandmarkStr = """
                 Pose landmarks: ${poseLandmarks.landmarkCount}
                 
                 """.trimIndent()
            val poseMarkers = ArrayList<PoseLandMark>()
            var landmarkIndex = 0
            for (landmark in poseLandmarks.landmarkList) {
                val marker = PoseLandMark(landmark.x, landmark.y, landmark.visibility)
                //          poseLandmarkStr += "\tLandmark ["+ landmarkIndex+ "]: ("+ (landmark.getX()*720)+ ", "+ (landmark.getY()*1280)+ ", "+ landmark.getVisibility()+ ")\n";
                ++landmarkIndex
                poseMarkers.add(marker)
            }
            // Get Angle of Positions
            val rightAngle = getAngle(poseMarkers[16], poseMarkers[14], poseMarkers[12])
            val leftAngle = getAngle(poseMarkers[15], poseMarkers[13], poseMarkers[11])
            val rightKnee = getAngle(poseMarkers[24], poseMarkers[26], poseMarkers[28])
            val leftKnee = getAngle(poseMarkers[23], poseMarkers[25], poseMarkers[27])
            val rightShoulder = getAngle(poseMarkers[14], poseMarkers[12], poseMarkers[24])
            val leftShoulder = getAngle(poseMarkers[13], poseMarkers[11], poseMarkers[23])
            Log.v(
                TAG, """
     ======Degree Of Position]======
     rightAngle :$rightAngle
     leftAngle :$leftAngle
     rightHip :$rightKnee
     leftHip :$leftKnee
     rightShoulder :$rightShoulder
     leftShoulder :$leftShoulder
     
     """.trimIndent()
            )
            return poseLandmarkStr
            /*
           16 ?????? ?????? 14 ?????? ????????? 12 ?????? ?????? --> ????????? ??????
           15 ?????? ?????? 13 ?????? ????????? 11 ?????? ?????? --> ???  ??? ??????
           24 ?????? ?????? 26 ?????? ??????   28 ?????? ?????? --> ???????????? ??????
           23 ?????? ?????? 25 ?????? ??????   27 ?????? ?????? --> ??? ?????? ??????
           14 ?????? ?????? 12 ?????? ??????   24 ?????? ?????? --> ?????? ???????????? ??????
           13 ???   ?????? 11 ???  ??????   23  ???  ?????? --> ?????? ???????????? ??????
        */
        }

        fun getAngle(
            firstPoint: PoseLandMark,
            midPoint: PoseLandMark,
            lastPoint: PoseLandMark
        ): Double {
            var result = Math.toDegrees(
                Math.atan2(
                    (lastPoint.y - midPoint.y).toDouble(),
                    (lastPoint.x - midPoint.x).toDouble()
                )
                        - Math.atan2(
                    (firstPoint.y - midPoint.y).toDouble(),
                    (firstPoint.x - midPoint.x).toDouble()
                )
            )
            result = Math.abs(result) // Angle should never be negative
            if (result > 180) {
                result = 360.0 - result // Always get the acute representation of the angle
            }
            return result
        }

        init {
            // Load all native libraries needed by the app.
            System.loadLibrary("mediapipe_jni")
            System.loadLibrary("opencv_java3")
        }
    }

    // {@link SurfaceTexture} where the camera-preview frames can be accessed.
    private var previewFrameTexture: SurfaceTexture? = null

    // {@link SurfaceView} that displays the camera-preview frames processed by a MediaPipe graph.
    private var previewDisplayView: SurfaceView? = null

    // Creates and manages an {@link EGLContext}.
    private var eglManager: EglManager? = null

    // Sends camera-preview frames into a MediaPipe graph for processing, and displays the processed
    // frames onto a {@link Surface}.
    private var processor: FrameProcessor? = null

    // Converts the GL_TEXTURE_EXTERNAL_OES texture from Android camera into a regular texture to be
    // consumed by {@link FrameProcessor} and the underlying MediaPipe graph.
    private var converter: ExternalTextureConverter? = null

    // ApplicationInfo for retrieving metadata defined in the manifest.
    // ?????? 0 ???
    private var applicationInf: ApplicationInfo? = null

    // Handles camera access via the {@link CameraX} Jetpack support library.
    private var cameraHelper: CameraXPreviewHelper? = null


    private var cnt = 0


    @SuppressLint("WrongCall")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(contentViewLayoutResId)
        previewDisplayView = SurfaceView(this)
        val main_tv_work = findViewById<TextView>(R.id.main_tv_work)
        val main_tv_result = findViewById<TextView>(R.id.main_tv_result)
        val main_tv_state = findViewById<TextView>(R.id.main_tv_state)

        setSpeak()


        setupPreviewDisplayView()
        try {
            applicationInf =
                packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Cannot find application info: $e")
        }
        // Initialize asset manager so that MediaPipe native libraries can access the app assets, e.g.,
        // binary graphs.
        AndroidAssetUtil.initializeNativeAssetManager(this)
        eglManager = EglManager(null)
        processor = FrameProcessor(
            this,
            eglManager!!.nativeContext,
            BINARY_GRAPH_NAME,
            INPUT_VIDEO_STREAM_NAME,
            OUTPUT_VIDEO_STREAM_NAME
        )
        processor!!
            .videoSurfaceOutput
            .setFlipY(FLIP_FRAMES_VERTICALLY)

        // To show verbose logging, run:
        // adb shell setprop log.tag.MainActivity VERBOSE
//        if (Log.isLoggable(TAG, Log.VERBOSE)) {
        processor!!.addPacketCallback(
            OUTPUT_LANDMARKS_STREAM_NAME
        ) { packet: Packet ->
            Log.v(TAG, "Received Pose landmarks packet.")
            try {
//                        NormalizedLandmarkList poseLandmarks = PacketGetter.getProto(packet, NormalizedLandmarkList.class);
                val landmarksRaw = PacketGetter.getProtoBytes(packet)
                val poseLandmarks = NormalizedLandmarkList.parseFrom(landmarksRaw)
                Log.v(
                    TAG,
                    "[TS:" + packet.timestamp + "] " + getPoseLandmarksDebugString(poseLandmarks)
                )
                val srh = previewDisplayView!!.holder


//                  -- this line cannot Running --
//                    Canvas canvas = null
//                    try {
//                        canvas= srh.lockCanvas();
//                        synchronized(srh){
//                            Paint paint = new Paint();
//                            paint.setColor(Color.RED);
//                            canvas.drawCircle(10.0f,10.0f,10.0f,paint);
//                        }
//                    }finally{
//                        if(canvas != null){
//                            srh.unlockCanvasAndPost(canvas);
//                        }
//                    }
////                    processor.getVideoSurfaceOutput().setSurface(srh.getSurface());

                val poseMarkers = ArrayList<PoseLandMark>()
                var state = "down"
                var landmarkIndex = 0
                for (landmark in poseLandmarks.landmarkList) {
                    val marker = PoseLandMark(landmark.x, landmark.y, landmark.visibility)
                    //          poseLandmarkStr += "\tLandmark ["+ landmarkIndex+ "]: ("+ (landmark.getX()*720)+ ", "+ (landmark.getY()*1280)+ ", "+ landmark.getVisibility()+ ")\n";
                    ++landmarkIndex
                    poseMarkers.add(marker)
                }
                /* ?????? ???
                * val angle = getAngle(poseMarkers[11], poseMarkers[13], poseMarkers[15])

                if (angle > 160) {
                    state = "down"
                }
                if (angle < 30 && state == "down") {
                    state = "up"
                    cnt += 1
                }
                */
                // ?????????
                val angle1 = getAngle(poseMarkers[14], poseMarkers[12], poseMarkers[24])
                val angle2 = getAngle(poseMarkers[13], poseMarkers[11], poseMarkers[23])
                //var state1 = 0

                if(angle1 < 40 && angle2 < 40 && (state =="up" || state=="high")){
                    state="down"

                }
                if(angle1 < 40 && angle2 < 40 && state=="middle"){
                    state="down"
                    speak("?????? ?????? ????????????")
                }
                if(angle1 >= 40 && angle2 >= 40 && state=="down"){
                    Handler(Looper.getMainLooper()).postDelayed({
                        if(wrongLineLandMarkLeftx.size==0){
                            wrongLineLandMarkLeftx.add(poseMarkers[12].x)
                            wrongLineLandMarkLeftx.add(poseMarkers[14].x)
                            wrongLineLandMarkLeftx.add(poseMarkers[16].x)

                            wrongLineLandMarkLefty.add(poseMarkers[12].y)
                            wrongLineLandMarkLefty.add(poseMarkers[14].y)
                            wrongLineLandMarkLefty.add(poseMarkers[16].y)

                            wrongLineLandMarkRightx.add(poseMarkers[11].x)
                            wrongLineLandMarkRightx.add(poseMarkers[13].x)
                            wrongLineLandMarkRightx.add(poseMarkers[15].x)

                            wrongLineLandMarkRighty.add(poseMarkers[11].y)
                            wrongLineLandMarkRighty.add(poseMarkers[13].y)
                            wrongLineLandMarkRighty.add(poseMarkers[15].y)
                        }
                    }, 200)
                    wrongLineLandMarkLeftx.clear()
                    wrongLineLandMarkLefty.clear()
                    wrongLineLandMarkRightx.clear()
                    wrongLineLandMarkRighty.clear()
                    state = "middle"
                }
                if(angle1 > 90 && angle2 > 90 && state=="middle"){
                    state = "up"
                    wrongLineLandMarkLeftx.clear()
                    wrongLineLandMarkLefty.clear()
                    wrongLineLandMarkRightx.clear()
                    wrongLineLandMarkRighty.clear()

                    cnt += 1
                }
                if(angle1 > 110 && angle2 > 110 && state=="up"){
                    state = "high"
                    cnt -= 1
                    speak("?????? ?????? ????????????")
                }
                //val draw = onDraw(this)

                //draw.onDraw(canvas, poseLandmarks.landmarkList[12].x, poseLandmarks.landmarkList[12].y, poseLandmarks.landmarkList[11].x, poseLandmarks.landmarkList[11].y)
                runOnUiThread {
                    //setContentView(onDraw(this))
                    // ?????? ?????? ?????? ?????? ??? ??????
                    addContentView(onDraw(this), ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    main_tv_work.text = "?????????"
                    main_tv_result.text = state
                    //main_tv_result.text = poseLandmarks.landmarkList[12].z.toString()
                    main_tv_state.text = cnt.toString()
                }
            } catch (exception: InvalidProtocolBufferException) {
                Log.e(TAG, "failed to get proto.", exception)
            }
        }
        /*processor.addPacketCallback(
                "throttled_input_video_cpu",
                (packet) ->{
                    Log.d("Raw Image","Receive image with ts: "+packet.getTimestamp());
                    Bitmap image = AndroidPacketGetter.getBitmapFromRgba(packet);
                }
        );*/PermissionHelper.checkAndRequestCameraPermissions(this)
    }
    // ???????????? ?????? ????????? ?????? ????????? ??????
    fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
    }
    
    // ?????? ??? ?????????
    public class onDraw(context: Context) : View(context){
        override fun onDraw(canvas: Canvas?) {
            super.onDraw(canvas)
            var whitePaint: Paint
            whitePaint = Paint()
            // whitePaint.strokeWidth = STROKE_WIDTH
            //Log.e("draw", startX.toString())
            whitePaint.color = Color.RED
            whitePaint.strokeWidth = 50F
            val height = resources.displayMetrics.heightPixels
            val width = resources.displayMetrics.widthPixels

            if(wrongLineLandMarkLeftx.size != 0){
                Handler(Looper.getMainLooper()).postDelayed({
                    canvas?.drawLine(wrongLineLandMarkLeftx[0]*width, wrongLineLandMarkLefty[0]*height, wrongLineLandMarkLeftx[1]*width, wrongLineLandMarkLefty[1]*height, whitePaint)
                    canvas?.drawLine(wrongLineLandMarkLeftx[1]*width, wrongLineLandMarkLefty[1]*height, wrongLineLandMarkLeftx[2]*width, wrongLineLandMarkLefty[2]*height, whitePaint)
                }, 200)


                invalidate()
                Log.e("line", (wrongLineLandMarkLeftx[0]*width).toString())
            }

        }
    }


    // tts ??????
    fun speak(string: String){
            // ?????? ??? ??????
        tts?.setPitch(0.25f)
            // string: ????????? ?????? ??????    TextToSpeech.QUEUE_ADD: ????????? ?????? ??? ?????? ??????
        tts?.speak(string, TextToSpeech.QUEUE_ADD, null)
            // 1: ????????? ??????
        tts?.playSilentUtterance(750, TextToSpeech.QUEUE_ADD, null)

    }

    // tts ?????? ?????????
    fun setSpeak(){

        tts = TextToSpeech(this, TextToSpeech.OnInitListener {
            if(it == TextToSpeech.SUCCESS){
                val result = tts!!.setLanguage(Locale.KOREAN)
                if(result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED){
                    Log.e("TTS", "??????????????? ???????????? ????????????")
                    return@OnInitListener
                }
            }
        })
    }

    // Used to obtain the content view for this application. If you are extending this class, and
    // have a custom layout, override this method and return the custom layout.
    protected val contentViewLayoutResId: Int
        protected get() = R.layout.activity_main

    override fun onResume() {
        super.onResume()
        converter = ExternalTextureConverter(
            eglManager!!.context, 2
        )
        converter!!.setFlipY(FLIP_FRAMES_VERTICALLY)
        converter!!.setConsumer(processor)
        if (PermissionHelper.cameraPermissionsGranted(this)) {
            startCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        converter!!.close()

        // Hide preview display until we re-open the camera again.
        previewDisplayView!!.visibility = View.GONE
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PermissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    protected fun onCameraStarted(surfaceTexture: SurfaceTexture?) {
        previewFrameTexture = surfaceTexture

        // Make the display view visible to start showing the preview. This triggers the
        // SurfaceHolder.Callback added to (the holder of) previewDisplayView.
        previewDisplayView!!.visibility = View.VISIBLE
    }

    protected fun cameraTargetResolution(): Size? {
        return null // No preference and let the camera (helper) decide.
    }

    fun startCamera() {
        cameraHelper = CameraXPreviewHelper()
        cameraHelper!!.setOnCameraStartedListener { surfaceTexture: SurfaceTexture? ->
            onCameraStarted(
                surfaceTexture
            )
        }
        val cameraFacing = CameraFacing.FRONT
        cameraHelper!!.startCamera(
            this, cameraFacing,  /*unusedSurfaceTexture=*/null, cameraTargetResolution()
        )
    }

    protected fun computeViewSize(width: Int, height: Int): Size {
        return Size(width, height)
    }

    protected fun onPreviewDisplaySurfaceChanged(
        holder: SurfaceHolder?, format: Int, width: Int, height: Int
    ) {
        // (Re-)Compute the ideal size of the camera-preview display (the area that the
        // camera-preview frames get rendered onto, potentially with scaling and rotation)
        // based on the size of the SurfaceView that contains the display.
        val viewSize = computeViewSize(width, height)
        val displaySize = cameraHelper!!.computeDisplaySizeFromViewSize(viewSize)
        val isCameraRotated = cameraHelper!!.isCameraRotated

        //displaySize.getHeight(); ????????? ??????????????? ???????????? ??????
        //displaySize.getWidth();

        // Connect the converter to the camera-preview frames as its input (via
        // previewFrameTexture), and configure the output width and height as the computed
        // display size.
        converter!!.setSurfaceTextureAndAttachToGLContext(
            previewFrameTexture,
            if (isCameraRotated) displaySize.height else displaySize.width,
            if (isCameraRotated) displaySize.width else displaySize.height
        )
    }

    private fun setupPreviewDisplayView() {
        previewDisplayView!!.visibility = View.GONE
        val viewGroup = findViewById<ViewGroup>(R.id.preview_display_layout)
        viewGroup.addView(previewDisplayView)
        previewDisplayView!!
            .getHolder()
            .addCallback(

                object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        processor!!.videoSurfaceOutput.setSurface(holder.surface)
                        Log.d("Surface", "Surface Created")
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int
                    ) {
                        onPreviewDisplaySurfaceChanged(holder, format, width, height)
                        // ???????????? width , height ??? 720,1280
                        Log.d("Surface", "Surface Changed")
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        processor!!.videoSurfaceOutput.setSurface(null)
                        Log.d("Surface", "Surface destroy")
                    }
                })
    }
}