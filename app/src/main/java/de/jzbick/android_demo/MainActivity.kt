package de.jzbick.android_demo

import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import de.jzbick.android_demo.renderer.AugmentedImageRenderer
import de.jzbick.android_demo.renderer.BackgroundRenderer
import de.jzbick.android_demo.util.DisplayRotationHelper
import java.io.IOException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10


class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer, DisplayManager.DisplayListener {

    private val TAG = this::class.java.simpleName

    private var session: Session? = null
    private var markerIndex: Int? = null
    private var surfaceView: GLSurfaceView? = null
    private var fitToScan: ImageView? = null
    private val backgroundRenderer = BackgroundRenderer()
    private val augmentedImageRenderer = AugmentedImageRenderer()

    private var displayRotationHelper: DisplayRotationHelper? = null

    private var installRequested: Boolean = false

    private var augmentedImageMap: Map<Int, Pair<AugmentedImage, Anchor>> = mutableMapOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            CameraPermissionHelper.requestCameraPermission(this)
        }

        displayRotationHelper = DisplayRotationHelper(this);

        surfaceView = findViewById(R.id.surfaceview)

        session = Session(this)
        val imageDatabase = AugmentedImageDatabase(session)
        val config = Config(session)


        surfaceView?.preserveEGLContextOnPause = true
        surfaceView?.setEGLContextClientVersion(2)
        surfaceView?.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView?.setRenderer(this)
        surfaceView?.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceView?.setWillNotDraw(false)

        fitToScan = findViewById(R.id.image_view_fit_to_scan)

        fitToScan?.setImageBitmap(BitmapFactory.decodeStream(this.assets.open("fit_to_scan.png")))

        val bitmap = assets.open("default.jpg").use { BitmapFactory.decodeStream(it) }
        markerIndex = imageDatabase.addImage("marker", bitmap)

        config.augmentedImageDatabase = imageDatabase

        config.focusMode = Config.FocusMode.AUTO

        session!!.configure(config)

        installRequested = false
    }

    override fun onResume() {
        super.onResume()

        var message: String? = null

        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> {
                }
            }
        } catch (e: Exception) {
            message = when (e) {
                is UnavailableArcoreNotInstalledException, is UnavailableUserDeclinedInstallationException -> "Please install ARCore"
                is UnavailableApkTooOldException -> "Please update ARCore"
                is UnavailableSdkTooOldException -> "Please update this app"
                else -> "This device does not support AR"
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            Log.e(TAG, "Exception thrown", e)
        }

        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Toast.makeText(this, "Camera not available", Toast.LENGTH_LONG).show()
        }

        surfaceView?.onResume()
        displayRotationHelper?.onResume()

        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            CameraPermissionHelper.requestCameraPermission(this)
            return
        }
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            displayRotationHelper?.onPause()
            surfaceView?.onPause()
            session?.pause()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
                .show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }
    }

    override fun onSurfaceCreated(p0: GL10?, p1: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        try {
            println("SurfaceCreated")
            backgroundRenderer.createOnGlThread(this)
            augmentedImageRenderer.createOnGlThread(this)
        } catch (e: IOException) {
            Log.e(TAG, e.stackTraceToString())
        }
    }

    override fun onSurfaceChanged(p0: GL10?, width: Int, height: Int) {
        displayRotationHelper?.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(p0: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)

        if (session == null) return

        displayRotationHelper?.updateSessionIfNeeded(session!!)

        try {

            session?.setCameraTextureName(backgroundRenderer.cameraTextureId)

            val frame = session?.update()
            val camera = frame?.camera

            if (frame != null) {

                backgroundRenderer.draw(frame)

                val projmtx = FloatArray(16)
                camera?.getProjectionMatrix(projmtx, 0, 0.1F, 100.0F)

                val viewmtx = FloatArray(16)
                camera?.getViewMatrix(viewmtx, 0)

                val colorCorrectionRgba = FloatArray(4)
                frame.lightEstimate.getColorCorrection(colorCorrectionRgba, 0)

                trackImage(frame, viewmtx, projmtx, colorCorrectionRgba)
            }
        } catch (e: Throwable) {
            Log.e(this::class.java.simpleName, e.stackTraceToString())
        }
    }

    override fun onDisplayAdded(p0: Int) {
        TODO("Not yet implemented")
    }

    override fun onDisplayRemoved(p0: Int) {
        TODO("Not yet implemented")
    }

    override fun onDisplayChanged(p0: Int) {
        TODO("Not yet implemented")
    }

    override fun onDestroy() {
        if (session !== null) {
            session?.close()
            session = null
        }
        super.onDestroy()
    }

    private fun trackImage(frame: Frame?, projmtx: FloatArray, viewmtx: FloatArray, colorCorrectionRgba: FloatArray) {
        val updatedAugmentedImages = frame?.getUpdatedTrackables(AugmentedImage::class.java)

        if (updatedAugmentedImages != null) {
            for (augmentedImage in updatedAugmentedImages) {
                when (augmentedImage.trackingState) {
                    TrackingState.PAUSED -> {
                        this.runOnUiThread {
                            Toast.makeText(this, "Found image", Toast.LENGTH_SHORT).show()
                            fitToScan?.visibility = View.GONE
                        }
                    }

                    TrackingState.TRACKING -> {
                        if (!augmentedImageMap.containsKey(augmentedImage.index)) {
                            val centerPoseAnchor: Anchor = augmentedImage.createAnchor(augmentedImage.centerPose)
                            augmentedImageMap = augmentedImageMap.plus(Pair(augmentedImage.index, Pair(augmentedImage, centerPoseAnchor)))
                        }
                    }

                    TrackingState.STOPPED -> {
                        if (augmentedImageMap.containsKey(augmentedImage.index)) {
                            augmentedImageMap = augmentedImageMap.minus(augmentedImage.index)
                        }
                    }

                    else -> {
                    }
                }
            }

            for (pair: Pair<AugmentedImage, Anchor> in augmentedImageMap.values) {
                val augmentedImage: AugmentedImage = pair.first
                val center: Anchor = pair.second

                if (augmentedImage.trackingState == TrackingState.TRACKING) {
                    augmentedImageRenderer.draw(viewmtx, projmtx, augmentedImage, center, colorCorrectionRgba)
                }
            }
        }
    }
}

