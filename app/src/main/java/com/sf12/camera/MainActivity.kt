package com.sf12.camera

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.ContentUris
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
import android.graphics.ImageFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
    private lateinit var topGradientView: View
    private lateinit var bottomGradientView: View
    private lateinit var quickControls: LinearLayout
    private lateinit var zoomRow: LinearLayout
    private lateinit var modeScroll: HorizontalScrollView
    private lateinit var modeRow: LinearLayout
    private lateinit var shutterRow: LinearLayout
    private lateinit var statusHint: TextView

    private lateinit var settingsButton: CameraIconButton
    private lateinit var flashButton: CameraIconButton
    private lateinit var aspectButton: CameraIconButton
    private lateinit var timerButton: CameraIconButton
    private lateinit var motionPhotoButton: CameraIconButton
    private lateinit var filterButton: CameraIconButton
    private lateinit var galleryThumbnail: ImageView
    private lateinit var galleryPlaceholder: CameraIconButton
    private lateinit var shutterButton: FrameLayout
    private lateinit var modeLabels: Map<String, TextView>
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraCharacteristics: CameraCharacteristics? = null
    private var cameraId: String? = null
    private var previewSize: Size? = null
    private var previewSurface: Surface? = null
    private var backgroundThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lensFacing = CameraCharacteristics.LENS_FACING_BACK
    private var activeTab = "Basic"
    private var activeMode = "Photo"
    private var activePrimaryMode = "Photo"
    private var flashMode = 0 // 0 = Auto, 1 = On, 2 = Off
    private var timerSeconds = 0
    private var aspectRatio = "3:4"
    private var zoomRatio = 1.0f
    private var zoomLevels = listOf(1.0f)
    private var selectedEffectMode = CaptureRequest.CONTROL_EFFECT_MODE_OFF
    private var gestureHadMultiplePointers = false
    private val primaryModes = listOf("Portrait", "Photo", "Video", "More")
    private var lastPhotoUri: Uri? = null
    private var lastPhotoMimeType: String = "image/jpeg"
    private var openingCamera = false
    private var capturePending = false

    private val permissionRequestCode = 710
    private val galleryPermissionRequestCode = 711
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

        val preferences = getSharedPreferences("camera_settings", MODE_PRIVATE)
        activeTab = preferences.getString("camera_profile", "Basic") ?: "Basic"
        if (activeTab !in listOf("Basic", "Pro", "Advanced", "Astrophotography")) {
            activeTab = "Basic"
        }
        aspectRatio = preferences.getString("photo_ratio", "3:4") ?: "3:4"
        buildCameraUi()
    }

    override fun onResume() {
        super.onResume()
        startCameraThread()
        if (::galleryThumbnail.isInitialized && hasGalleryReadPermission()) {
            loadLatestCameraThumbnail()
        }
        if (hasCameraPermission()) {
            if (previewView.isAvailable) openCamera()
            if (hasGalleryReadPermission()) {
                if (::galleryThumbnail.isInitialized) loadLatestCameraThumbnail()
            } else {
                val asked = getSharedPreferences("camera_settings", MODE_PRIVATE)
                    .getBoolean("gallery_read_permission_asked", false)
                if (!asked) requestGalleryReadPermission()
            }
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

        topGradientView = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xCC000000.toInt(), 0x55000000, Color.TRANSPARENT)
            )
        }
        root.addView(
            topGradientView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(190),
                Gravity.TOP
            )
        )

        bottomGradientView = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xE6000000.toInt(), 0x77000000, Color.TRANSPARENT)
            )
        }
        root.addView(
            bottomGradientView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(300),
                Gravity.BOTTOM
            )
        )

        topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), 0)
        }

        quickControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        settingsButton = iconControl("settings", "Settings") { showSettings() }
        flashButton = iconControl("flash", "Flash auto") { cycleFlash() }
        timerButton = iconControl("timer", "Timer off") { cycleTimer() }
        aspectButton = iconControl("ratio", "Photo size") { showAspectRatioPicker() }
        motionPhotoButton = iconControl("motion", "Motion Photo") { showMotionPhotoInfo() }
        filterButton = iconControl("filters", "Filters") { showFilterPicker() }

        listOf(
            settingsButton,
            flashButton,
            timerButton,
            aspectButton,
            motionPhotoButton,
            filterButton
        ).forEach { control ->
            quickControls.addView(control, weightedControl())
        }
        topPanel.addView(
            quickControls,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )
        root.addView(
            topPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), 0, dp(18), dp(26))
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
            setPadding(0, 0, 0, dp(10))
        }
        bottomPanel.addView(zoomRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
        ))

        modeScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            isFillViewport = true
        }
        modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val labels = mutableMapOf<String, TextView>()
        primaryModes.forEach { mode ->
            val label = TextView(this).apply {
                text = mode.uppercase(Locale.ROOT)
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(0xFFBDBDBD.toInt())
                setPadding(dp(18), dp(9), dp(18), dp(9))
                isClickable = true
                isFocusable = true
                setOnClickListener { selectMode(mode) }
            }
            modeRow.addView(
                label,
                LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
            )
            labels[mode] = label
        }
        modeLabels = labels
        modeScroll.addView(
            modeRow,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        bottomPanel.addView(
            modeScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)
            ).apply {
                bottomMargin = dp(16)
            }
        )

        shutterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val gallerySlot = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Open gallery"
            setOnClickListener { openLastPhoto() }
        }
        galleryThumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = oval(0xFF303030.toInt())
            clipToOutline = true
            setImageDrawable(textDrawablePlaceholder())
            isClickable = false
        }
        gallerySlot.addView(
            galleryThumbnail,
            FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER)
        )
        galleryPlaceholder = CameraIconButton(this, "gallery") { }.apply {
            contentDescription = "Most recent camera photo"
            isClickable = false
            isFocusable = false
        }
        gallerySlot.addView(
            galleryPlaceholder,
            FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER)
        )
        shutterRow.addView(gallerySlot, LinearLayout.LayoutParams(0, dp(82), 1f))

        shutterButton = FrameLayout(this).apply {
            background = ovalStroke(Color.WHITE, dp(3))
            isClickable = true
            isFocusable = true
            contentDescription = "Take photo"
            setOnClickListener { onShutterPressed() }
        }
        val shutterInner = View(this).apply { background = oval(Color.WHITE) }
        shutterButton.addView(
            shutterInner,
            FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER)
        )
        shutterRow.addView(shutterButton, LinearLayout.LayoutParams(dp(82), dp(82)))

        val switchContainer = FrameLayout(this)
        val switchSlot = FrameLayout(this).apply {
            background = oval(0x55333333)
            isClickable = true
            isFocusable = true
            contentDescription = "Switch camera"
            setOnClickListener { switchCamera() }
        }
        val switchIcon = TextView(this).apply {
            text = "↻"
            textSize = 31f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        switchSlot.addView(
            switchIcon,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        switchContainer.addView(
            switchSlot,
            FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER)
        )
        shutterRow.addView(
            switchContainer,
            LinearLayout.LayoutParams(0, dp(82), 1f)
        )
        bottomPanel.addView(
            shutterRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(82)
            )
        )

        statusHint = TextView(this).apply {
            textSize = 10f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(0, dp(9), 0, 0)
            visibility = View.GONE
        }
        bottomPanel.addView(
            statusHint,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        scaleGestureDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val bounds = zoomBounds()
                    val nextZoom = (zoomRatio * detector.scaleFactor)
                        .coerceIn(bounds.first, bounds.second)
                    if (abs(nextZoom - zoomRatio) > 0.002f) {
                        zoomRatio = nextZoom
                        startPreviewRepeating()
                    }
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    setZoomButtons()
                }
            }
        )
        gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onFling(
                    first: MotionEvent?,
                    last: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (first == null || gestureHadMultiplePointers ||
                        first.pointerCount > 1 || last.pointerCount > 1
                    ) return false

                    val deltaX = last.x - first.x
                    val deltaY = last.y - first.y
                    if (abs(deltaX) > abs(deltaY) && abs(deltaX) > dp(65) &&
                        abs(velocityX) > dp(180)
                    ) {
                        val current = primaryModes.indexOf(activePrimaryMode).coerceAtLeast(0)
                        val direction = if (deltaX > 0) 1 else -1
                        val next = (current + direction + primaryModes.size) % primaryModes.size
                        selectMode(primaryModes[next])
                        return true
                    }
                    if (abs(deltaY) > abs(deltaX) && abs(deltaY) > dp(75) &&
                        abs(velocityY) > dp(180)
                    ) {
                        switchCamera()
                        return true
                    }
                    return false
                }
            }
        )
        previewView.setOnTouchListener { _, event ->
            if (event.pointerCount > 1) gestureHadMultiplePointers = true
            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                gestureHadMultiplePointers = false
            }
            true
        }

        selectMode("Photo")
        updateFlashButton()
        updateTimerButton()
        updateFilterButton()
        setZoomButtons()
        updateRatioChrome()
        selectTab(activeTab)
        setContentView(root)
    }

    private fun selectTab(name: String) {
        val profiles = listOf("Basic", "Pro", "Advanced", "Astrophotography")
        if (name !in profiles) return

        activeTab = name
        getSharedPreferences("camera_settings", MODE_PRIVATE)
            .edit()
            .putString("camera_profile", name)
            .apply()

        val basic = name == "Basic"
        quickControls.visibility = if (basic) View.VISIBLE else View.INVISIBLE
        zoomRow.visibility = if (basic) View.VISIBLE else View.INVISIBLE
        modeScroll.visibility = if (basic) View.VISIBLE else View.INVISIBLE
        shutterRow.visibility = if (basic) View.VISIBLE else View.INVISIBLE
        statusHint.visibility = if (basic) View.GONE else View.VISIBLE
        statusHint.text = when (name) {
            "Pro" -> "PRO CONTROLS · COMING SOON"
            "Advanced" -> "ADVANCED CONTROLS · COMING SOON"
            "Astrophotography" -> "ASTROPHOTOGRAPHY · COMING SOON"
            else -> ""
        }

        if (!basic) {
            Toast.makeText(this, "$name mode is planned for a later build.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSettings() {
        val profiles = arrayOf("Basic", "Pro", "Advanced", "Astrophotography")
        AlertDialog.Builder(this)
            .setTitle("Camera settings")
            .setSingleChoiceItems(profiles, profiles.indexOf(activeTab)) { dialog, which ->
                selectTab(profiles[which])
                dialog.dismiss()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun selectMode(mode: String) {
        if (mode !in primaryModes) return
        activePrimaryMode = mode
        if (mode == "More") {
            updatePrimaryModeLabels()
            showMoreModes()
            return
        }

        activeMode = mode
        updatePrimaryModeLabels()
        if (mode != "Photo") {
            Toast.makeText(this, "$mode mode is not implemented yet.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePrimaryModeLabels() {
        if (!::modeLabels.isInitialized) return
        modeLabels.forEach { (label, view) ->
            val selected = label == activePrimaryMode
            view.setTextColor(if (selected) Color.WHITE else 0xFFBDBDBD.toInt())
            view.setTypeface(
                null,
                if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
            view.background = if (selected) rounded(0xFF303030.toInt(), 100) else null
        }
    }

    private fun showMoreModes() {
        val modes = mutableListOf(
            "Night",
            "Single Take",
            "Food",
            "Panorama"
        )
        val characteristics = cameraCharacteristics
        val streamMap = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val maximumHighSpeedFps = streamMap?.highSpeedVideoFpsRanges
            ?.maxOfOrNull { it.upper } ?: 0
        if (maximumHighSpeedFps >= 480) modes.add("Super Slow-Mo")
        if (maximumHighSpeedFps >= 120) modes.add("Slow-Mo")
        modes.add("Hyperlapse")
        modes.add("Portrait Video")

        AlertDialog.Builder(this)
            .setTitle("More camera modes")
            .setItems(modes.toTypedArray()) { _, which ->
                activeMode = modes[which]
                activePrimaryMode = "More"
                updatePrimaryModeLabels()
                Toast.makeText(
                    this,
                    "${activeMode} is not implemented in this build yet.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showMotionPhotoInfo() {
        AlertDialog.Builder(this)
            .setTitle("Motion Photo")
            .setMessage("Motion Photo capture is not implemented yet. The control is shown here to match the Samsung Camera layout.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun availableEffects(): List<Pair<Int, String>> {
        val supported = cameraCharacteristics
            ?.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS)
            ?.toSet().orEmpty()
        return listOf(
            CaptureRequest.CONTROL_EFFECT_MODE_OFF to "Original",
            CaptureRequest.CONTROL_EFFECT_MODE_MONO to "Mono",
            CaptureRequest.CONTROL_EFFECT_MODE_NEGATIVE to "Negative",
            CaptureRequest.CONTROL_EFFECT_MODE_SOLARIZE to "Solarize",
            CaptureRequest.CONTROL_EFFECT_MODE_SEPIA to "Sepia",
            CaptureRequest.CONTROL_EFFECT_MODE_POSTERIZE to "Posterize",
            CaptureRequest.CONTROL_EFFECT_MODE_AQUA to "Aqua"
        ).filter { it.first == CaptureRequest.CONTROL_EFFECT_MODE_OFF || supported.contains(it.first) }
    }

    private fun showFilterPicker() {
        val effects = availableEffects()
        if (effects.size <= 1) {
            Toast.makeText(this, "This camera does not report additional hardware filters.", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = effects.map { it.second }.toTypedArray()
        val selectedIndex = effects.indexOfFirst { it.first == selectedEffectMode }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Filters")
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                selectedEffectMode = effects[which].first
                updateFilterButton()
                startPreviewRepeating()
                dialog.dismiss()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun updateFilterButton() {
        if (!::filterButton.isInitialized) return
        val effect = availableEffects().firstOrNull { it.first == selectedEffectMode }?.second ?: "Original"
        filterButton.contentDescription = "Filter: $effect"
        filterButton.invalidate()
    }

    private fun applyFilter(builder: CaptureRequest.Builder) {
        val supported = cameraCharacteristics
            ?.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS)
            ?.contains(selectedEffectMode) == true
        if (supported || selectedEffectMode == CaptureRequest.CONTROL_EFFECT_MODE_OFF) {
            builder.set(CaptureRequest.CONTROL_EFFECT_MODE, selectedEffectMode)
        }
    }

    private fun iconControl(
        icon: String,
        description: String,
        action: () -> Unit
    ): CameraIconButton {
        return CameraIconButton(this, icon, action).apply {
            contentDescription = description
        }
    }

    private fun weightedControl() = LinearLayout.LayoutParams(0, dp(52), 1f)

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
        flashButton.contentDescription = when (flashMode) {
            0 -> "Flash auto"
            1 -> "Flash on"
            else -> "Flash off"
        }
        flashButton.invalidate()
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
        timerButton.contentDescription = when (timerSeconds) {
            0 -> "Timer off"
            else -> "Timer ${timerSeconds} seconds"
        }
        timerButton.invalidate()
    }

    private fun showAspectRatioPicker() {
        val choices = listOf("3:4", "9:16", "1:1", "Full")
        AlertDialog.Builder(this)
            .setTitle("Photo size")
            .setSingleChoiceItems(choices.toTypedArray(), choices.indexOf(aspectRatio)) { dialog, which ->
                val selected = choices[which]
                if (selected != aspectRatio) {
                    aspectRatio = selected
                    getSharedPreferences("camera_settings", MODE_PRIVATE)
                        .edit().putString("photo_ratio", selected).apply()
                    updateRatioChrome()
                    recreateImageReaderAndSession()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateRatioChrome() {
        val isFull = aspectRatio == "Full"
        val panelColor = if (isFull) Color.TRANSPARENT else 0xCC000000.toInt()
        if (::topPanel.isInitialized) topPanel.setBackgroundColor(panelColor)
        if (::bottomPanel.isInitialized) bottomPanel.setBackgroundColor(panelColor)
        if (::topGradientView.isInitialized) {
            topGradientView.visibility = if (isFull) View.GONE else View.VISIBLE
        }
        if (::bottomGradientView.isInitialized) {
            bottomGradientView.visibility = if (isFull) View.GONE else View.VISIBLE
        }
        if (::aspectButton.isInitialized) {
            aspectButton.contentDescription = "Photo size: $aspectRatio"
            aspectButton.invalidate()
        }
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
                applyFilter(this)
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

    private fun zoomBounds(): Pair<Float, Float> {
        val characteristics = cameraCharacteristics ?: return 1f to 1f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null) {
                return range.lower.coerceAtLeast(0.1f) to range.upper.coerceAtLeast(1f)
            }
        }
        val maxZoom = characteristics
            .get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            ?.coerceAtLeast(1f) ?: 1f
        return 1f to maxZoom
    }

    private fun setZoomButtons() {
        if (!::zoomRow.isInitialized) return
        zoomRow.removeAllViews()

        val bounds = zoomBounds()
        val minZoom = bounds.first
        val maxZoom = bounds.second
        val levels = mutableListOf<Float>()

        fun addLevel(value: Float) {
            if (value < minZoom - 0.015f || value > maxZoom + 0.015f) return
            if (levels.none { abs(it - value) < 0.02f }) {
                levels.add(value.coerceIn(minZoom, maxZoom))
            }
        }

        if (minZoom < 0.98f) addLevel(minZoom)
        listOf(0.5f, 0.9f, 1f, 2f, 4f, 8f).forEach { addLevel(it) }
        if (levels.none { abs(it - maxZoom) < 0.02f }) addLevel(maxZoom)
        if (levels.isEmpty()) addLevel(1f)
        zoomLevels = levels.sorted()
        zoomRatio = zoomRatio.coerceIn(minZoom, maxZoom)

        zoomLevels.forEach { level ->
            val selected = abs(zoomRatio - level) < 0.08f
            val label = if (abs(level - level.toInt()) < 0.01f) {
                "${level.toInt()}×"
            } else {
                "${String.format(Locale.US, "%.1f", level)}×"
            }
            val chip = TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (selected) Color.BLACK else Color.WHITE)
                background = if (selected) oval(Color.WHITE) else null
                isClickable = true
                isFocusable = true
                contentDescription = "Zoom $label"
                setOnClickListener { setZoom(level) }
            }
            val diameter = if (selected) dp(44) else dp(38)
            zoomRow.addView(
                chip,
                LinearLayout.LayoutParams(diameter, dp(46)).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                }
            )
        }
    }

    private fun setZoom(value: Float) {
        val bounds = zoomBounds()
        zoomRatio = value.coerceIn(bounds.first, bounds.second)
        setZoomButtons()
        startPreviewRepeating()
    }

    private fun showGalleryThumbnail(uri: Uri) {
        if (!::galleryThumbnail.isInitialized) return
        galleryThumbnail.setImageURI(uri)
        galleryPlaceholder.visibility = View.GONE
    }

    private fun loadLatestCameraThumbnail() {
        Thread {
            var latestUri: Uri? = null
            try {
                val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.MIME_TYPE
                )
                val selection: String
                val selectionArgs: Array<String>
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
                    selectionArgs = arrayOf(
                        Environment.DIRECTORY_DCIM + "/Camera/%",
                        Environment.DIRECTORY_PICTURES + "/Camera/%"
                    )
                } else {
                    selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"
                    selectionArgs = arrayOf("Camera")
                }

                contentResolver.query(
                    collection,
                    projection,
                    selection,
                    selectionArgs,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(
                            cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        )
                        latestUri = ContentUris.withAppendedId(collection, id)
                        val mimeIndex = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                        if (mimeIndex >= 0 && !cursor.isNull(mimeIndex)) {
                            lastPhotoMimeType = cursor.getString(mimeIndex)
                        }
                    }
                }
            } catch (_: Exception) {
                // The gallery placeholder remains visible if the media index cannot be queried.
            }

            latestUri?.let { uri ->
                mainHandler.post {
                    if (!isFinishing && ::galleryThumbnail.isInitialized) {
                        lastPhotoUri = uri
                        showGalleryThumbnail(uri)
                    }
                }
            }
        }.start()
    }

    private fun openLastPhoto() {
        if (!hasGalleryReadPermission() && lastPhotoUri == null) {
            requestGalleryReadPermission()
            Toast.makeText(this, "Allow photo access to open your latest camera shot.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = lastPhotoUri
        if (uri != null) {
            val samsungGalleryIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, lastPhotoMimeType)
                setPackage("com.sec.android.gallery3d")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(samsungGalleryIntent)
                return
            } catch (_: Exception) {
                // Samsung Gallery is not installed or cannot handle this image.
            }

            try {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, lastPhotoMimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
                return
            } catch (_: Exception) {
                // Continue to the Gallery app home screen fallback.
            }
        }

        val samsungGalleryHome = packageManager.getLaunchIntentForPackage("com.sec.android.gallery3d")
        if (samsungGalleryHome != null) {
            try {
                startActivity(samsungGalleryHome)
                return
            } catch (_: Exception) {
                // Use Android's generic gallery category below.
            }
        }

        try {
            startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)
            )
        } catch (_: Exception) {
            Toast.makeText(this, "No gallery app is available.", Toast.LENGTH_SHORT).show()
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
            val candidates = cameraManager.cameraIdList.mapNotNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                if (characteristics.get(CameraCharacteristics.LENS_FACING) == facing) {
                    id to characteristics
                } else {
                    null
                }
            }
            if (candidates.isEmpty()) return null
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) return candidates.first().first

            val logicalMultiCamera = candidates.firstOrNull { (_, characteristics) ->
                val capabilities = characteristics.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
                )
                capabilities?.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                ) == true
            }
            logicalMultiCamera?.first ?: candidates.maxByOrNull { (_, characteristics) ->
                val ratioRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                } else null
                if (ratioRange != null) {
                    ratioRange.upper - ratioRange.lower
                } else {
                    characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                }
            }?.first
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
                    runOnUiThread {
                        setZoomButtons()
                        updateFlashButton()
                    }
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

        val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        previewSize = previewSizes
            .filter { it.width >= 1280 && it.height >= 720 }
            .minByOrNull { it.width.toLong() * it.height.toLong() }
            ?: previewSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
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
            captureSession?.close()
            captureSession = null
            previewSurface?.release()
            previewSurface = Surface(texture)
            configurePreviewTransform(previewView.width, previewView.height)
            device.createCaptureSession(
                listOf(previewSurface!!, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice !== device) {
                            session.close()
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
            val surface = previewSurface ?: return
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                applyFilter(this)
                applyZoom(this)
            }
            session.setRepeatingRequest(request.build(), null, cameraHandler)
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
            lastPhotoMimeType = "image/jpeg"
            runOnUiThread {
                showGalleryThumbnail(uri)
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
            previewSurface?.release()
            previewSurface = null
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
        val legacyWriteGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        return cameraGranted && legacyWriteGranted
    }

    private fun galleryReadPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun hasGalleryReadPermission(): Boolean {
        return checkSelfPermission(galleryReadPermission()) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (!hasGalleryReadPermission()) {
            permissions.add(galleryReadPermission())
            getSharedPreferences("camera_settings", MODE_PRIVATE)
                .edit().putBoolean("gallery_read_permission_asked", true).apply()
        }
        requestPermissions(permissions.toTypedArray(), permissionRequestCode)
    }

    private fun requestGalleryReadPermission() {
        getSharedPreferences("camera_settings", MODE_PRIVATE)
            .edit().putBoolean("gallery_read_permission_asked", true).apply()
        requestPermissions(
            arrayOf(galleryReadPermission()),
            galleryPermissionRequestCode
        )
    }

    @Deprecated("Deprecated in Android API; retained for minSdk compatibility.")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            if (hasCameraPermission()) {
                if (previewView.isAvailable) openCamera()
            } else {
                Toast.makeText(this, "Camera permission is required to use the viewfinder.", Toast.LENGTH_LONG).show()
            }
            if (hasGalleryReadPermission() && ::galleryThumbnail.isInitialized) {
                loadLatestCameraThumbnail()
            }
        } else if (requestCode == galleryPermissionRequestCode) {
            if (hasGalleryReadPermission() && ::galleryThumbnail.isInitialized) {
                loadLatestCameraThumbnail()
            }
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (hasCameraPermission()) openCamera()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        configurePreviewTransform(width, height)
    }

    private fun configurePreviewTransform(viewWidth: Int, viewHeight: Int) {
        val size = previewSize ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        val rotation = windowManager.defaultDisplay.rotation
        val matrix = android.graphics.Matrix()
        val viewRect = android.graphics.RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = android.graphics.RectF(
            0f, 0f, size.height.toFloat(), size.width.toFloat()
        )
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, android.graphics.Matrix.ScaleToFit.FILL)
            val scale = max(
                viewHeight.toFloat() / bufferRect.height(),
                viewWidth.toFloat() / bufferRect.width()
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90f * (rotation - 2), centerX, centerY)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, centerX, centerY)
        }
        previewView.setTransform(matrix)
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

    private inner class CameraIconButton(
        context: Context,
        private val icon: String,
        private val onPressed: () -> Unit
    ) : View(context) {

        private val fillPaint = android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG
        ).apply {
            color = Color.WHITE
            style = android.graphics.Paint.Style.FILL
        }

        init {
            isClickable = true
            isFocusable = true
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            val out = android.util.TypedValue()
            if (context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless,
                    out,
                    true
                )
            ) {
                setBackgroundResource(out.resourceId)
            }
            setOnClickListener { onPressed() }
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val size = dp(24).toFloat()
            canvas.save()
            canvas.translate((width - size) / 2f, (height - size) / 2f)
            canvas.scale(size / 24f, size / 24f)
            fillPaint.color = Color.WHITE
            when (icon) {
                "settings" -> drawSettings(canvas)
                "flash" -> drawFlash(canvas)
                "timer" -> drawTimer(canvas)
                "ratio" -> drawRatio(canvas)
                "motion" -> drawMotion(canvas)
                "filters" -> drawFilters(canvas)
                "gallery" -> drawGallery(canvas)
            }
            canvas.restore()
        }

        private fun drawSettings(canvas: android.graphics.Canvas) {
            val gear = android.graphics.Path().apply {
                fillType = android.graphics.Path.FillType.EVEN_ODD
                for (i in 0 until 32) {
                    val angle = Math.toRadians(-90.0 + i * 11.25)
                    val radius = if (i % 4 == 1 || i % 4 == 2) 10.7f else 8.6f
                    val x = 12f + kotlin.math.cos(angle).toFloat() * radius
                    val y = 12f + kotlin.math.sin(angle).toFloat() * radius
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
                addCircle(12f, 12f, 3.2f, android.graphics.Path.Direction.CW)
            }
            canvas.drawPath(gear, fillPaint)
        }

        private fun drawFlash(canvas: android.graphics.Canvas) {
            val bolt = android.graphics.Path().apply {
                moveTo(13.5f, 1.5f)
                lineTo(5.2f, 13.2f)
                lineTo(10.1f, 13.2f)
                lineTo(9.1f, 22.5f)
                lineTo(18.8f, 9.3f)
                lineTo(13.6f, 9.3f)
                close()
            }
            canvas.drawPath(bolt, fillPaint)
            if (flashMode == 2) {
                val slash = android.graphics.Paint(fillPaint).apply {
                    color = Color.BLACK
                    strokeWidth = 2.3f
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    style = android.graphics.Paint.Style.STROKE
                }
                canvas.drawLine(4f, 20f, 20f, 4f, slash)
            }
        }

        private fun drawTimer(canvas: android.graphics.Canvas) {
            val ring = android.graphics.Path().apply {
                fillType = android.graphics.Path.FillType.EVEN_ODD
                addCircle(12f, 13f, 10f, android.graphics.Path.Direction.CW)
                addCircle(12f, 13f, 6.8f, android.graphics.Path.Direction.CCW)
            }
            canvas.drawPath(ring, fillPaint)
            val hands = android.graphics.Path().apply {
                moveTo(10.8f, 6.7f)
                lineTo(13.2f, 6.7f)
                lineTo(13.2f, 12.2f)
                lineTo(17.1f, 14.4f)
                lineTo(15.9f, 16.5f)
                lineTo(10.8f, 13.7f)
                close()
            }
            canvas.drawPath(hands, fillPaint)
            if (timerSeconds > 0) {
                canvas.drawCircle(19.4f, 19.2f, 3.4f, fillPaint)
            }
        }

        private fun drawRatio(canvas: android.graphics.Canvas) {
            canvas.drawRoundRect(
                android.graphics.RectF(2f, 4f, 22f, 20f),
                4f, 4f, fillPaint
            )
            val textPaint = android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG
            ).apply {
                color = Color.BLACK
                style = android.graphics.Paint.Style.FILL
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = when (aspectRatio) {
                    "Full" -> 6.1f
                    "9:16" -> 6.8f
                    else -> 7.5f
                }
                typeface = android.graphics.Typeface.create(
                    "sans-serif-medium",
                    android.graphics.Typeface.NORMAL
                )
            }
            val text = if (aspectRatio == "Full") "FULL" else aspectRatio
            canvas.drawText(text, 12f, 14.7f, textPaint)
        }

        private fun drawMotion(canvas: android.graphics.Canvas) {
            canvas.drawRoundRect(
                android.graphics.RectF(2f, 4f, 22f, 20f),
                4f, 4f, fillPaint
            )
            val cutout = android.graphics.Paint(fillPaint).apply { color = Color.BLACK }
            val play = android.graphics.Path().apply {
                moveTo(10f, 7.7f)
                lineTo(16.4f, 12f)
                lineTo(10f, 16.3f)
                close()
            }
            canvas.drawPath(play, cutout)
            canvas.drawCircle(5.2f, 6.8f, 1.1f, cutout)
        }

        private fun drawFilters(canvas: android.graphics.Canvas) {
            val wand = android.graphics.Path().apply {
                moveTo(4.2f, 18.2f)
                lineTo(17.9f, 4.5f)
                lineTo(20.2f, 6.8f)
                lineTo(6.5f, 20.5f)
                close()
            }
            canvas.drawPath(wand, fillPaint)
            drawSparkle(canvas, 7f, 5.7f, 3.1f)
            drawSparkle(canvas, 18.8f, 16.8f, 3.7f)
            canvas.drawCircle(17.5f, 3.2f, 1.2f, fillPaint)
        }

        private fun drawSparkle(
            canvas: android.graphics.Canvas,
            centerX: Float,
            centerY: Float,
            radius: Float
        ) {
            val path = android.graphics.Path().apply {
                moveTo(centerX, centerY - radius)
                lineTo(centerX + radius * 0.28f, centerY - radius * 0.28f)
                lineTo(centerX + radius, centerY)
                lineTo(centerX + radius * 0.28f, centerY + radius * 0.28f)
                lineTo(centerX, centerY + radius)
                lineTo(centerX - radius * 0.28f, centerY + radius * 0.28f)
                lineTo(centerX - radius, centerY)
                lineTo(centerX - radius * 0.28f, centerY - radius * 0.28f)
                close()
            }
            canvas.drawPath(path, fillPaint)
        }

        private fun drawGallery(canvas: android.graphics.Canvas) {
            canvas.drawRoundRect(
                android.graphics.RectF(2.5f, 3.5f, 21.5f, 20.5f),
                3f, 3f, fillPaint
            )
            val dark = android.graphics.Paint(fillPaint).apply { color = 0xFF303030.toInt() }
            canvas.drawCircle(8f, 9f, 1.8f, dark)
            val mountain = android.graphics.Path().apply {
                moveTo(4f, 18f)
                lineTo(9.2f, 11.5f)
                lineTo(12.3f, 15.1f)
                lineTo(15.4f, 11.6f)
                lineTo(20f, 18f)
                close()
            }
            canvas.drawPath(mountain, dark)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
}
