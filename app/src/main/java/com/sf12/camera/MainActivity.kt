package com.sf12.camera

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

class MainActivity : Activity(), TextureView.SurfaceTextureListener {

    private lateinit var root: FrameLayout
    private lateinit var previewView: TextureView
    private lateinit var topPanel: LinearLayout
    private lateinit var bottomPanel: LinearLayout
    private lateinit var quickControls: LinearLayout
    private lateinit var zoomRow: LinearLayout
    private lateinit var modeRow: LinearLayout
    private lateinit var shutterRow: LinearLayout

    private lateinit var flashButton: TextView
    private lateinit var aspectButton: TextView
    private lateinit var timerButton: TextView
    private lateinit var galleryThumbnail: ImageView
    private lateinit var shutterButton: FrameLayout
    private lateinit var tabLabels: Map<String, TextView>
    private lateinit var tabIndicators: Map<String, View>

    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraCharacteristics: CameraCharacteristics? = null
    private var cameraId: String? = null
    private var previewSize: Size? = null
    private var backgroundThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private val mainHandler = Handler(mainLooper)

    private var lensFacing = CameraCharacteristics.LENS_FACING_BACK
    private var activeTab = "Basic"
    private var activeMode = "Photo"
    private var flashMode = 0 // 0 = Auto, 1 = On, 2 = Off
    private var timerSeconds = 0
    private var aspectRatio = "3:4"
    private var zoomRatio = 1.0f
    private var zoomLevels = listOf(1.0f)
    private var lastPhotoUri: Uri? = null
    private var openingCamera = false
    private var capturePending = false

    private val permissionRequestCode = 710
    private val timerRunnable = Runnable { captureStillPhoto() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        buildCameraUi()
    }

    override fun onResume() {
        super.onResume()
        startCameraThread()
        if (hasCameraPermission()) {
            if (previewView.isAvailable) openCamera()
        } else {
            requestCameraPermissions()
        }
    }

    override fun onPause() {
        mainHandler.removeCallbacks(timerRunnable)
        capturePending = false
        closeCamera()
        stopCameraThread()
        super.onPause()
    }

    private fun buildCameraUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        previewView = TextureView(this).apply {
            surfaceTextureListener = this@MainActivity
        }
        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val topGradient = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xCC000000.toInt(), 0x55000000, Color.TRANSPARENT)
            )
        }
        root.addView(
            topGradient,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(190),
                Gravity.TOP
            )
        )

        val bottomGradient = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xE6000000.toInt(), 0x77000000, Color.TRANSPARENT)
            )
        }
        root.addView(
            bottomGradient,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(300),
                Gravity.BOTTOM
            )
        )

        topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), 0)
        }
        root.addView(
            topPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val labels = mutableMapOf<String, TextView>()
        val indicators = mutableMapOf<String, View>()
        listOf("Basic", "Pro", "Advanced").forEach { name ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(8), dp(10), dp(9))
                isClickable = true
                isFocusable = true
            }
            val label = TextView(this).apply {
                text = name
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val indicator = View(this).apply {
                background = rounded(Color.WHITE, 100)
            }
            item.addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(22)
            ))
            val indicatorParams = LinearLayout.LayoutParams(dp(24), dp(3)).apply {
                topMargin = dp(5)
            }
            item.addView(indicator, indicatorParams)
            item.setOnClickListener { selectTab(name) }
            tabs.addView(item, LinearLayout.LayoutParams(0, dp(43), 1f))
            labels[name] = label
            indicators[name] = indicator
        }
        tabLabels = labels
        tabIndicators = indicators
        topPanel.addView(tabs)

        quickControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(8))
        }
        flashButton = quickControl("⚡  AUTO") { cycleFlash() }
        aspectButton = quickControl("3:4") { showAspectRatioPicker() }
        timerButton = quickControl("TIMER  OFF") { cycleTimer() }
        quickControls.addView(flashButton, weightedControl())
        quickControls.addView(aspectButton, weightedControl())
        quickControls.addView(timerButton, weightedControl())
        topPanel.addView(quickControls)

        bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), 0, dp(18), dp(25))
        }
        root.addView(
            bottomPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        bottomPanel.addView(zoomRow)

        modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(17))
        }
        listOf("Video", "Portrait", "Photo", "Night").forEach { mode ->
            val label = TextView(this).apply {
                text = mode.uppercase(Locale.ROOT)
                textSize = 11f
                gravity = Gravity.CENTER
                setTypeface(null, if (mode == "Photo") android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setTextColor(if (mode == "Photo") Color.WHITE else 0xFFB5B5B5.toInt())
                setPadding(dp(7), dp(7), dp(7), dp(7))
                isClickable = true
                setOnClickListener { selectMode(mode) }
            }
            modeRow.addView(label, LinearLayout.LayoutParams(0, dp(34), 1f))
        }
        bottomPanel.addView(modeRow)

        shutterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        galleryThumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0x553FFFFFF, 12)
            setImageDrawable(textDrawablePlaceholder())
            isClickable = true
            setOnClickListener { openLastPhoto() }
        }
        shutterRow.addView(galleryThumbnail, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            weight = 1f
            gravity = Gravity.CENTER_VERTICAL
        })

        shutterButton = FrameLayout(this).apply {
            background = ovalStroke(Color.WHITE, dp(3))
            isClickable = true
            isFocusable = true
            setOnClickListener { onShutterPressed() }
        }
        val shutterInner = View(this).apply {
            background = oval(Color.WHITE)
        }
        shutterButton.addView(
            shutterInner,
            FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER)
        )
        shutterRow.addView(shutterButton, LinearLayout.LayoutParams(dp(78), dp(78)).apply {
            gravity = Gravity.CENTER
        })

        val switchButton = TextView(this).apply {
            text = "↻"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isClickable = true
            setOnClickListener { switchCamera() }
        }
        shutterRow.addView(switchButton, LinearLayout.LayoutParams(0, dp(58), 1f))
        bottomPanel.addView(shutterRow)

        val hint = TextView(this).apply {
            text = "CAMERA"
            textSize = 9f
            letterSpacing = 0.18f
            gravity = Gravity.CENTER
            setTextColor(0x99FFFFFF.toInt())
            setPadding(0, dp(8), 0, 0)
        }
        bottomPanel.addView(hint)

        selectTab("Basic")
        updateFlashButton()
        updateTimerButton()
        setZoomButtons()
        setContentView(root)
    }

    private fun selectTab(name: String) {
        activeTab = name
        tabLabels.forEach { (tab, label) ->
            label.setTextColor(if (tab == name) Color.WHITE else 0xFF9B9B9B.toInt())
            tabIndicators[tab]?.visibility = if (tab == name) View.VISIBLE else View.INVISIBLE
        }
        val basic = name == "Basic"
        quickControls.visibility = if (basic) View.VISIBLE else View.INVISIBLE
        zoomRow.visibility = if (basic) View.VISIBLE else View.INVISIBLE
        modeRow.visibility = if (basic) View.VISIBLE else View.INVISIBLE
        shutterRow.visibility = if (basic) View.VISIBLE else View.INVISIBLE
        val hint = bottomPanel.getChildAt(bottomPanel.childCount - 1) as? TextView
        hint?.text = if (basic) "CAMERA" else "$name MODE  ·  COMING NEXT"
        if (!basic) {
            Toast.makeText(this, "$name mode is planned for a later build.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectMode(mode: String) {
        if (mode != "Photo") {
            Toast.makeText(this, "$mode mode is planned for a later build.", Toast.LENGTH_SHORT).show()
            return
        }
        activeMode = mode
    }

    private fun quickControl(text: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = rounded(0x55202020, 100, 0x44FFFFFF)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
    }

    private fun weightedControl() = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
        marginStart = dp(4)
        marginEnd = dp(4)
    }

    private fun cycleFlash() {
        val available = cameraCharacteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        if (!available) {
            Toast.makeText(this, "Flash is not available on this camera.", Toast.LENGTH_SHORT).show()
            return
        }
        flashMode = (flashMode + 1) % 3
        updateFlashButton()
        startPreviewRepeating()
    }

    private fun updateFlashButton() {
        if (!::flashButton.isInitialized) return
        val labels = listOf("⚡  AUTO", "⚡  ON", "⚡  OFF")
        flashButton.text = labels[flashMode]
    }

    private fun cycleTimer() {
        timerSeconds = when (timerSeconds) {
            0 -> 3
            3 -> 10
            else -> 0
        }
        updateTimerButton()
    }

    private fun updateTimerButton() {
        if (!::timerButton.isInitialized) return
        timerButton.text = when (timerSeconds) {
            0 -> "TIMER  OFF"
            else -> "TIMER  ${timerSeconds}s"
        }
    }

    private fun showAspectRatioPicker() {
        val choices = listOf("3:4", "9:16", "1:1", "Full")
        AlertDialog.Builder(this)
            .setTitle("Photo ratio")
            .setSingleChoiceItems(choices.toTypedArray(), choices.indexOf(aspectRatio)) { dialog, which ->
                val selected = choices[which]
                if (selected != aspectRatio) {
                    aspectRatio = selected
                    aspectButton.text = selected
                    recreateImageReaderAndSession()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onShutterPressed() {
        if (activeTab != "Basic" || activeMode != "Photo") return
        if (capturePending) return
        val delay = timerSeconds
        if (delay > 0) {
            capturePending = true
            Toast.makeText(this, "Photo in ${delay}s", Toast.LENGTH_SHORT).show()
            mainHandler.postDelayed(timerRunnable, delay * 1000L)
        } else {
            captureStillPhoto()
        }
    }

    private fun captureStillPhoto() {
        capturePending = false
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                setFlashForRequest(this)
                applyZoom(this)
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
            }
            session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: android.hardware.camera2.TotalCaptureResult
                ) {
                    startPreviewRepeating()
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            Toast.makeText(this, "Capture failed: ${e.reason}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setFlashForRequest(builder: CaptureRequest.Builder) {
        val available = cameraCharacteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        if (!available || lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            return
        }
        builder.set(
            CaptureRequest.CONTROL_AE_MODE,
            when (flashMode) {
                0 -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                1 -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                else -> CaptureRequest.CONTROL_AE_MODE_ON
            }
        )
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        val characteristics = cameraCharacteristics ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range: Range<Float>? = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio.coerceIn(range.lower, range.upper))
                return
            }
        }
        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val zoom = zoomRatio.coerceIn(1f, maxZoom)
        val cropWidth = (activeArray.width() / zoom).toInt()
        val cropHeight = (activeArray.height() / zoom).toInt()
        val left = activeArray.centerX() - cropWidth / 2
        val top = activeArray.centerY() - cropHeight / 2
        builder.set(
            CaptureRequest.SCALER_CROP_REGION,
            android.graphics.Rect(left, top, left + cropWidth, top + cropHeight)
        )
    }

    private fun setZoomButtons() {
        if (!::zoomRow.isInitialized) return
        zoomRow.removeAllViews()
        val characteristics = cameraCharacteristics
        var minZoom = 1f
        var maxZoom = 1f
        if (characteristics != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (range != null) {
                    minZoom = range.lower.coerceAtMost(1f)
                    maxZoom = range.upper.coerceAtLeast(1f)
                } else {
                    maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                }
            } else {
                maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            }
        }
        val levels = mutableListOf<Float>()
        if (minZoom <= 0.55f) levels.add(0.5f)
        levels.add(1f)
        if (maxZoom >= 1.9f) levels.add(2f)
        if (maxZoom >= 3.8f) levels.add(4f)
        zoomLevels = levels.distinct()
        if (zoomRatio !in minZoom..maxZoom) zoomRatio = 1f
        zoomLevels.forEach { level ->
            val chip = TextView(this).apply {
                text = if (level == 1f) "1×" else "${level}×"
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(13), dp(8), dp(13), dp(8))
                setTextColor(if (abs(zoomRatio - level) < 0.01f) Color.WHITE else 0xFFDDDDDD.toInt())
                background = rounded(
                    if (abs(zoomRatio - level) < 0.01f) 0xAA263142.toInt() else 0x55202020,
                    100,
                    if (abs(zoomRatio - level) < 0.01f) 0x99FFFFFF.toInt() else 0x44FFFFFF
                )
                isClickable = true
                setOnClickListener { setZoom(level) }
            }
            zoomRow.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)
            ).apply {
                marginStart = dp(5)
                marginEnd = dp(5)
            })
        }
    }

    private fun setZoom(value: Float) {
        zoomRatio = value
        setZoomButtons()
        startPreviewRepeating()
    }

    private fun openLastPhoto() {
        val uri = lastPhotoUri
        if (uri == null) {
            Toast.makeText(this, "Your captured photos will appear here.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            Toast.makeText(this, "No photo viewer is available.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchCamera() {
        val targetFacing = if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        val targetId = findCameraId(targetFacing)
        if (targetId == null) {
            Toast.makeText(this, "That camera is not available.", Toast.LENGTH_SHORT).show()
            return
        }
        lensFacing = targetFacing
        closeCamera()
        cameraId = targetId
        if (previewView.isAvailable) openCamera()
    }

    private fun findCameraId(facing: Int): String? {
        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
            }
        } catch (_: CameraAccessException) {
            null
        }
    }

    private fun startCameraThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("CameraCaptureThread").also { it.start() }
        cameraHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopCameraThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        backgroundThread = null
        cameraHandler = null
    }

    private fun openCamera() {
        if (openingCamera || cameraDevice != null || !hasCameraPermission()) return
        if (cameraHandler == null) startCameraThread()
        val id = cameraId ?: findCameraId(lensFacing)
        if (id == null) {
            Toast.makeText(this, "No compatible camera was found.", Toast.LENGTH_LONG).show()
            return
        }
        cameraId = id
        openingCamera = true
        try {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    openingCamera = false
                    cameraDevice = camera
                    cameraCharacteristics = cameraManager.getCameraCharacteristics(id)
                    setZoomButtons()
                    updateFlashButton()
                    createImageReaderAndSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    openingCamera = false
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    openingCamera = false
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Camera error ($error).", Toast.LENGTH_LONG).show()
                    }
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            openingCamera = false
            Toast.makeText(this, "Unable to open camera: ${e.reason}", Toast.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            openingCamera = false
            requestCameraPermissions()
        }
    }

    private fun createImageReaderAndSession() {
        val characteristics = cameraCharacteristics ?: return
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        if (jpegSizes.isEmpty()) {
            runOnUiThread {
                Toast.makeText(this, "JPEG capture is not supported by this camera.", Toast.LENGTH_LONG).show()
            }
            return
        }

        val desiredRatio = when (aspectRatio) {
            "9:16" -> 16f / 9f
            "1:1" -> 1f
            else -> 4f / 3f
        }
        val selectedSize = if (aspectRatio == "Full") {
            jpegSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
        } else {
            val closeRatios = jpegSizes.filter {
                abs(it.width.toFloat() / it.height.toFloat() - desiredRatio) < 0.035f
            }
            (closeRatios.ifEmpty { jpegSizes }).maxByOrNull { it.width.toLong() * it.height.toLong() }
        } ?: jpegSizes[0]

        previewSize = map.getOutputSizes(SurfaceTexture::class.java)
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
        imageReader?.close()
        imageReader = ImageReader.newInstance(
            selectedSize.width,
            selectedSize.height,
            ImageFormat.JPEG,
            2
        ).also { reader ->
            reader.setOnImageAvailableListener({ source ->
                val image = source.acquireNextImage() ?: return@setOnImageAvailableListener
                val bytes = try {
                    val buffer: ByteBuffer = image.planes[0].buffer
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                } finally {
                    image.close()
                }
                saveJpeg(bytes)
            }, cameraHandler)
        }
        configurePreviewSession()
    }

    private fun recreateImageReaderAndSession() {
        if (cameraDevice == null) return
        createImageReaderAndSession()
    }

    private fun configurePreviewSession() {
        val device = cameraDevice ?: return
        val texture = previewView.surfaceTexture ?: return
        val reader = imageReader ?: return
        try {
            val size = previewSize
            if (size != null) texture.setDefaultBufferSize(size.width, size.height)
            val previewSurface = Surface(texture)
            captureSession?.close()
            captureSession = null
            device.createCaptureSession(
                listOf(previewSurface, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice !== device) {
                            session.close()
                            previewSurface.release()
                            return
                        }
                        captureSession = session
                        startPreviewRepeating()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Unable to configure camera preview.", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                cameraHandler
            )
        } catch (e: CameraAccessException) {
            Toast.makeText(this, "Camera setup failed: ${e.reason}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startPreviewRepeating() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val texture = previewView.surfaceTexture ?: return
        try {
            val surface = Surface(texture)
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                applyZoom(this)
            }
            session.setRepeatingRequest(request.build(), null, cameraHandler)
            surface.release()
        } catch (e: CameraAccessException) {
            runOnUiThread {
                Toast.makeText(this, "Preview error: ${e.reason}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun jpegOrientation(): Int {
        val characteristics = cameraCharacteristics ?: return 0
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val rotation = windowManager.defaultDisplay.rotation
        val deviceDegrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + deviceDegrees) % 360
        } else {
            (sensorOrientation - deviceDegrees + 360) % 360
        }
    }

    private fun saveJpeg(bytes: ByteArray) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Camera_$timestamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        var savedUri: Uri? = null
        try {
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Unable to create image entry")
            savedUri = uri
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: throw IllegalStateException("Unable to open image output")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            lastPhotoUri = uri
            runOnUiThread {
                galleryThumbnail.setImageURI(uri)
                Toast.makeText(this, "Photo saved to Pictures/Camera", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            savedUri?.let { contentResolver.delete(it, null, null) }
            runOnUiThread {
                Toast.makeText(this, "Couldn't save photo: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (_: Exception) {
            // Camera resources are already being released.
        }
        openingCamera = false
    }

    private fun hasCameraPermission(): Boolean {
        val cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val storageGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        return cameraGranted && storageGranted
    }

    private fun requestCameraPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        requestPermissions(permissions.toTypedArray(), permissionRequestCode)
    }

    @Deprecated("Deprecated in Android API; retained for minSdk compatibility.")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (previewView.isAvailable) openCamera()
            } else {
                Toast.makeText(this, "Camera permission is required to use the viewfinder.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (hasCameraPermission()) openCamera()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // The camera stream remains active; the viewfinder scales to the new bounds.
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun rounded(
        color: Int,
        radius: Int,
        strokeColor: Int? = null
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius.toFloat()).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun ovalStroke(color: Int, strokeWidth: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0x22000000)
        setStroke(strokeWidth, color)
    }

    private fun textDrawablePlaceholder(): GradientDrawable = GradientDrawable().apply {
        setColor(0x66555555)
        cornerRadius = dp(8).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
}
