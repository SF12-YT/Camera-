package com.sf12.camera

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.ContentUris
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.graphics.ImageFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.GestureDetector
import android.view.DragEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

class MainActivity : Activity(), TextureView.SurfaceTextureListener {

    private lateinit var root: FrameLayout
    private lateinit var previewView: TextureView
    private lateinit var focusIndicator: View
    private lateinit var topPanel: LinearLayout
    private lateinit var bottomPanel: LinearLayout
    private lateinit var topGradientView: View
    private lateinit var bottomGradientView: View
    private lateinit var expandedOptionsRow: LinearLayout
    private lateinit var quickControls: LinearLayout
    private lateinit var zoomRow: HorizontalScrollView
    private lateinit var zoomChipsRow: LinearLayout
    private lateinit var modeScroll: HorizontalScrollView
    private lateinit var modeRow: LinearLayout
    private lateinit var shutterRow: LinearLayout
    private lateinit var statusHint: TextView
    private var captureOverlay: FrameLayout? = null
    private var captureHiddenViewStates: List<Pair<View, Int>>? = null
    private var captureFrozenPreview: Bitmap? = null
    private var captureFrozenPreviewView: ImageView? = null

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
    private var focusMeteringRegion: MeteringRectangle? = null
    private data class PendingStillCapture(
        val id: Long,
        val portrait: Boolean,
        val rotation: Int,
        val focusPoints: List<Pair<Float, Float>>
    )
    @Volatile private var pendingStillCapture: PendingStillCapture? = null
    @Volatile private var portraitProcessing = false
    private var captureSequenceId = 0L
    private var captureTimeoutRunnable: Runnable? = null
    @Volatile private var portraitFocusX = 0.5f
    @Volatile private var portraitFocusY = 0.42f
    @Volatile private var portraitFocusPoints = emptyList<Pair<Float, Float>>()
    private var portraitHasUserSelection = false
    private var previewSize: Size? = null
    private var previewSurface: Surface? = null
    private var backgroundThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideFocusIndicatorRunnable = Runnable {
        if (::focusIndicator.isInitialized) {
            focusIndicator.animate()
                .alpha(0f)
                .setDuration(220L)
                .withEndAction { focusIndicator.visibility = View.GONE }
                .start()
        }
    }

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
    private var expandedOptionsKind: String? = null
    private var gestureHadMultiplePointers = false
    private var gestureStartedInPreviewArea = false
    private var primaryModes = mutableListOf("Portrait", "Photo", "Video", "More")
    private var moreModes = mutableListOf(
        "Single Take", "Night", "Food", "Panorama",
        "Hyperlapse", "Portrait Video"
    )
    private var moreModesOverlay: FrameLayout? = null
    private var moreModesTaskbarTouchProxy: View? = null
    private var modeEditing = false
    private var settingsOverlay: FrameLayout? = null
    private data class ModeDragPayload(val mode: String, val sourceGroup: String)
    private data class CameraStorageLocation(
        val volumeName: String,
        val label: String,
        val isRemovable: Boolean,
        val isAvailable: Boolean = true,
        val status: String = ""
    )

    private data class CameraGalleryItem(
        val uri: Uri,
        val mimeType: String,
        val dateTaken: Long,
        val filePath: String?,
        val orientation: Int
    )
    private data class PortraitCameraSupport(
        val available: Boolean,
        val route: String? = null,
        val logicalCameraId: String? = null,
        val physicalCameraIds: List<String> = emptyList(),
        val concurrentCameraIds: List<String> = emptyList(),
        val reason: String = ""
    )

    private var rearPortraitCameraSupport = PortraitCameraSupport(
        available = false,
        reason = "Rear camera capabilities have not been checked."
    )

    private val knownCameraModes = listOf(
        "Portrait", "Photo", "Video", "Night", "Single Take", "Food",
        "Panorama", "Super Slow-Mo", "Slow-Mo", "Hyperlapse", "Portrait Video"
    )
    @Volatile private var cameraCapabilityReport = "Open the camera preview to generate a hardware capability report."
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

        preferences.getString("primary_camera_modes", null)?.let { saved ->
            val configured = saved.split("|")
                .filter { it in knownCameraModes }
                .distinct()
                .toMutableList()
            if (configured.isNotEmpty()) {
                primaryModes = (configured + "More").toMutableList()
            }
        }
        primaryModes.remove("Pro")
        preferences.getString("more_camera_modes", null)?.let { saved ->
            moreModes = saved.split("|")
                .filter { it in knownCameraModes && it !in primaryModes }
                .distinct()
                .toMutableList()
        } ?: run {
            moreModes = knownCameraModes
                .filter { it !in primaryModes && it != "Super Slow-Mo" && it != "Slow-Mo" }
                .toMutableList()
        }
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

    @Deprecated("Deprecated in Android API; retained for back compatibility.")
    override fun onBackPressed() {
        if (settingsOverlay != null) {
            closeSettings()
            return
        }
        if (moreModesOverlay != null) {
            if (modeEditing) {
                modeEditing = false
                showMoreModes()
            } else {
                closeMoreModes()
            }
            return
        }
        if (expandedOptionsKind != null) {
            closeExpandedOptions()
            return
        }
        super.onBackPressed()
    }

    override fun onPause() {
        mainHandler.removeCallbacks(timerRunnable)
        capturePending = false
        dismissCaptureOverlay(animate = false)
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
        focusIndicator = View(this).apply {
            background = rounded(Color.TRANSPARENT, 9, 0xFFFFD54F.toInt())
            visibility = View.GONE
            alpha = 1f
            contentDescription = "Focus point"
            elevation = dp(8).toFloat()
        }
        root.addView(
            focusIndicator,
            FrameLayout.LayoutParams(dp(72), dp(72))
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

        expandedOptionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(12), 0, dp(12), 0)
        }
        topPanel.addView(
            expandedOptionsRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
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

        zoomRow = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            isFillViewport = true
            setPadding(0, 0, 0, dp(10))
        }
        zoomChipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        zoomRow.addView(
            zoomChipsRow,
            HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.MATCH_PARENT
            )
        )
        bottomPanel.addView(zoomRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
        ))

        modeScroll = object : HorizontalScrollView(this) {
            private var gestureStartX = 0f
            private var watchEdgeDuringFling = false
            private var edgeBouncePlayed = false

            init {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                isFillViewport = false
            }

            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    gestureStartX = event.x
                    edgeBouncePlayed = false
                    watchEdgeDuringFling = true
                }
                return super.onInterceptTouchEvent(event)
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    gestureStartX = event.x
                    edgeBouncePlayed = false
                    watchEdgeDuringFling = true
                }
                val result = super.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    val fingerDelta = event.x - gestureStartX
                    val maxScroll = (getChildAt(0)?.width ?: 0) - width
                    if (!edgeBouncePlayed && maxScroll > 0) {
                        if (scrollX <= dp(2) && fingerDelta > dp(12)) {
                            playEdgeBounce(1)
                        } else if (scrollX >= maxScroll - dp(2) && fingerDelta < -dp(12)) {
                            playEdgeBounce(-1)
                        }
                    }
                    postDelayed({ watchEdgeDuringFling = false }, 650)
                }
                return result
            }

            override fun onScrollChanged(
                left: Int,
                top: Int,
                oldLeft: Int,
                oldTop: Int
            ) {
                super.onScrollChanged(left, top, oldLeft, oldTop)
                if (!watchEdgeDuringFling || edgeBouncePlayed) return
                val maxScroll = (getChildAt(0)?.width ?: 0) - width
                if (maxScroll <= 0) return
                if (left <= 0 && oldLeft > left) {
                    playEdgeBounce(1)
                } else if (left >= maxScroll && oldLeft < left) {
                    playEdgeBounce(-1)
                }
            }

            private fun playEdgeBounce(direction: Int) {
                if (edgeBouncePlayed) return
                edgeBouncePlayed = true
                modeRow.animate().cancel()
                modeRow.translationX = direction * dp(8).toFloat()
                modeRow.animate()
                    .translationX(0f)
                    .setDuration(340L)
                    .setInterpolator(OvershootInterpolator(1.25f))
                    .start()
            }
        }
        modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        modeScroll.addView(
            modeRow,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        rebuildModeStrip()
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
            setOnClickListener { openStockCameraAlbum() }
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
            background = null
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
            isClickable = false
        }
        val switchIcon = CameraIconButton(this, "flip") { switchCamera() }.apply {
            contentDescription = "Switch camera"
            background = null
        }
        switchSlot.addView(
            switchIcon,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
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
                override fun onDown(event: MotionEvent): Boolean {
                    gestureStartedInPreviewArea = isPreviewGesturePoint(event.x, event.y)
                    return gestureStartedInPreviewArea
                }

                override fun onSingleTapUp(event: MotionEvent): Boolean {
                    if (!gestureStartedInPreviewArea ||
                        !isPreviewGesturePoint(event.x, event.y) ||
                        gestureHadMultiplePointers || event.pointerCount > 1
                    ) return false
                    focusAtPoint(event.x, event.y)
                    return true
                }

                override fun onLongPress(event: MotionEvent) {
                    if (gestureStartedInPreviewArea &&
                        isPreviewGesturePoint(event.x, event.y) &&
                        !gestureHadMultiplePointers &&
                        activeMode == "Portrait"
                    ) {
                        focusAtPoint(event.x, event.y, addPortraitSubject = true)
                    }
                }

                override fun onFling(
                    first: MotionEvent?,
                    last: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (first == null || !gestureStartedInPreviewArea ||
                        !isPreviewGesturePoint(first.x, first.y) ||
                        !isPreviewGesturePoint(last.x, last.y) ||
                        gestureHadMultiplePointers ||
                        first.pointerCount > 1 || last.pointerCount > 1
                    ) return false

                    val deltaX = last.x - first.x
                    val deltaY = last.y - first.y
                    if (abs(deltaX) > abs(deltaY) && abs(deltaX) > dp(65) &&
                        abs(velocityX) > dp(180)
                    ) {
                        val current = primaryModes.indexOf(activePrimaryMode).coerceAtLeast(0)
                        val direction = if (deltaX > 0) -1 else 1
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
            if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                expandedOptionsKind != null
            ) {
                closeExpandedOptions()
            }
            if (event.pointerCount > 1) gestureHadMultiplePointers = true
            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                gestureHadMultiplePointers = false
                gestureStartedInPreviewArea = false
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
        quickControls.visibility = View.VISIBLE
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
        closeSettings()
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xFF101010.toInt())
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(18))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isClickable = true
            setOnClickListener { closeSettings() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(54)))
        header.addView(TextView(this).apply {
            text = "Camera settings"
            textSize = 21f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create(
                "sans-serif-medium",
                android.graphics.Typeface.NORMAL
            )
        }, LinearLayout.LayoutParams(0, dp(54), 1f))
        page.addView(header)

        val scroll = android.widget.ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(20))
        }

        content.addView(TextView(this).apply {
            text = "CAMERA PROFILE"
            textSize = 11f
            letterSpacing = 0.14f
            setTextColor(0xFFFFD54F.toInt())
            typeface = android.graphics.Typeface.create(
                "sans-serif-medium",
                android.graphics.Typeface.NORMAL
            )
            setPadding(dp(2), dp(12), 0, dp(10))
        })
        content.addView(TextView(this).apply {
            text = "Choose the camera control profile."
            textSize = 13f
            setTextColor(0xFFBDBDBD.toInt())
            setPadding(dp(2), 0, 0, dp(12))
        })

        val profiles = listOf(
            "Basic" to "Everyday camera controls",
            "Pro" to "Manual shooting controls",
            "Advanced" to "Additional camera controls",
            "Astrophotography" to "Long-exposure shooting"
        )
        profiles.chunked(2).forEach { profileRow ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            profileRow.forEachIndexed { index, profile ->
                val selected = activeTab == profile.first
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(12), dp(12), dp(12))
                    background = rounded(
                        if (selected) 0xFF29251A.toInt() else 0xFF202020.toInt(),
                        13,
                        if (selected) 0xFFFFD54F.toInt() else 0xFF383838.toInt()
                    )
                    isClickable = true
                    isFocusable = true
                    addView(TextView(this@MainActivity).apply {
                        text = profile.first.uppercase(Locale.ROOT)
                        textSize = 13f
                        letterSpacing = 0.04f
                        setTextColor(if (selected) 0xFFFFD54F.toInt() else Color.WHITE)
                        typeface = android.graphics.Typeface.create(
                            "sans-serif-medium",
                            android.graphics.Typeface.NORMAL
                        )
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = profile.second
                        textSize = 11f
                        setTextColor(0xFFBDBDBD.toInt())
                        setPadding(0, dp(5), 0, 0)
                    })
                    if (profile.first != "Basic") {
                        addView(TextView(this@MainActivity).apply {
                            text = "PLANNED"
                            textSize = 9f
                            letterSpacing = 0.08f
                            setTextColor(0xFFAAAAAA.toInt())
                            setPadding(0, dp(8), 0, 0)
                        })
                    }
                    setOnClickListener {
                        selectTab(profile.first)
                        showSettings()
                    }
                }
                row.addView(
                    card,
                    LinearLayout.LayoutParams(0, dp(112), 1f).apply {
                        if (index == 0) marginEnd = dp(8) else marginStart = dp(8)
                        bottomMargin = dp(10)
                    }
                )
            }
            content.addView(row)
        }

        content.addView(TextView(this).apply {
            text = "DEVICE CAPABILITIES"
            textSize = 11f
            letterSpacing = 0.14f
            setTextColor(0xFFFFD54F.toInt())
            typeface = android.graphics.Typeface.create(
                "sans-serif-medium",
                android.graphics.Typeface.NORMAL
            )
            setPadding(dp(2), dp(22), 0, dp(10))
        })
        val capabilityCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(0xFF202020.toInt(), 13, 0xFF383838.toInt())
            addView(TextView(this@MainActivity).apply {
                text = "Camera hardware report"
                textSize = 14f
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.create(
                    "sans-serif-medium",
                    android.graphics.Typeface.NORMAL
                )
            })
            addView(TextView(this@MainActivity).apply {
                text = "Inspect Camera2 IDs, physical cameras, depth outputs and tested stream combinations."
                textSize = 12f
                setTextColor(0xFFBDBDBD.toInt())
                setPadding(0, dp(5), 0, dp(10))
            })
            addView(TextView(this@MainActivity).apply {
                text = "VIEW CAPABILITY REPORT"
                textSize = 12f
                letterSpacing = 0.05f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFD54F.toInt())
                background = rounded(0xFF302B1C.toInt(), 9, 0xFF70602A.toInt())
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
                setOnClickListener { showCameraCapabilityReport() }
            })
        }
        content.addView(capabilityCard)

        content.addView(TextView(this).apply {
            text = "STORAGE"
            textSize = 11f
            letterSpacing = 0.14f
            setTextColor(0xFFFFD54F.toInt())
            typeface = android.graphics.Typeface.create(
                "sans-serif-medium",
                android.graphics.Typeface.NORMAL
            )
            setPadding(dp(2), dp(22), 0, dp(10))
        })
        val storagePreferences = getSharedPreferences("camera_settings", MODE_PRIVATE)
        val storageLocations = availableCameraStorageLocations()
        val sdLocation = storageLocations.firstOrNull { it.isRemovable }
        val storageOptions = listOf("Internal Storage", "SD Card")

        val storageSpinner = android.widget.Spinner(this, android.widget.Spinner.MODE_DROPDOWN).apply {
            background = rounded(0xFF202020.toInt(), 12, 0xFF444444.toInt())
            setPopupBackgroundDrawable(rounded(0xFF202020.toInt(), 10))
            dropDownVerticalOffset = dp(4)
            dropDownWidth = FrameLayout.LayoutParams.MATCH_PARENT
            setPadding(dp(12), 0, dp(12), 0)
        }
        val storageAdapter = object : android.widget.ArrayAdapter<String>(
            this,
            0,
            storageOptions
        ) {
            private fun itemView(position: Int, dropdown: Boolean): TextView {
                return TextView(this@MainActivity).apply {
                    text = storageOptions[position]
                    textSize = 14f
                    gravity = Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                    setTextColor(Color.WHITE)
                    setPadding(dp(14), if (dropdown) dp(13) else dp(12), dp(14), if (dropdown) dp(13) else dp(12))
                    if (dropdown) setBackgroundColor(0xFF202020.toInt())
                }
            }

            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return itemView(position, false)
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return itemView(position, true)
            }
        }
        storageSpinner.adapter = storageAdapter

        val selectedStorage = selectedCameraStorageLocation()
        storageSpinner.setSelection(if (selectedStorage?.isRemovable == true) 1 else 0)
        var ignoreInitialStorageSelection = true
        val storageSummary = TextView(this).apply {
            text = "Photos are saved in DCIM/Camera."
            textSize = 12f
            setTextColor(0xFFBDBDBD.toInt())
            setPadding(dp(2), dp(7), 0, 0)
        }
        storageSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit

            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (ignoreInitialStorageSelection) {
                    ignoreInitialStorageSelection = false
                    return
                }
                val location = if (position == 0) {
                    storageLocations.firstOrNull { !it.isRemovable }
                } else {
                    sdLocation
                }
                if (location == null || !location.isAvailable) {
                    Toast.makeText(
                        this@MainActivity,
                        "SD Card is not currently available.",
                        Toast.LENGTH_LONG
                    ).show()
                    storageSpinner.setSelection(0)
                    return
                }
                storagePreferences.edit()
                    .putString("camera_storage_volume", location.volumeName)
                    .apply()
                loadLatestCameraThumbnail()
                storageSummary.text = "Photos are saved to " + storageOptions[position] + "/DCIM/Camera."
            }
        }

        val storageCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(0xFF202020.toInt(), 13, 0xFF383838.toInt())
            addView(TextView(this@MainActivity).apply {
                text = "Save photos to"
                textSize = 14f
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.create(
                    "sans-serif-medium",
                    android.graphics.Typeface.NORMAL
                )
                setPadding(0, 0, 0, dp(8))
            })
            addView(
                storageSpinner,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48)
                )
            )
            addView(storageSummary)
        }
        content.addView(storageCard)

        scroll.addView(content)
        page.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        overlay.addView(
            page,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        settingsOverlay = overlay
    }

    private fun showCameraCapabilityReport() {
        val reportText = TextView(this).apply {
            text = cameraCapabilityReport
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val scroll = android.widget.ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                reportText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle("Camera capability report")
            .setView(scroll)
            .setPositiveButton("Copy report") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("Camera capability report", cameraCapabilityReport)
                )
                Toast.makeText(this, "Capability report copied.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun closeSettings() {
        settingsOverlay?.let { root.removeView(it) }
        settingsOverlay = null
    }

    private fun availableCameraStorageLocations(): List<CameraStorageLocation> {
        val locations = mutableListOf(
            CameraStorageLocation(
                MediaStore.VOLUME_EXTERNAL_PRIMARY,
                "Internal storage",
                false,
                true,
                "Ready"
            )
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return locations

        val storageManager = getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val mediaStoreNames = try {
            MediaStore.getExternalVolumeNames(this)
        } catch (_: Exception) {
            emptySet<String>()
        }
        var removableVolumesFound = false

        storageManager?.storageVolumes?.forEachIndexed { index, volume ->
            if (volume.isPrimary || !volume.isRemovable) return@forEachIndexed
            removableVolumesFound = true
            val state = volume.state
            val mounted = state == Environment.MEDIA_MOUNTED
            val readOnly = state == Environment.MEDIA_MOUNTED_READ_ONLY
            val platformVolumeName = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    volume.mediaStoreVolumeName
                } else {
                    volume.uuid?.lowercase(Locale.US)
                }
            } catch (_: Exception) {
                volume.uuid?.lowercase(Locale.US)
            }
            val matchingMediaStoreName = platformVolumeName?.let { candidate ->
                mediaStoreNames.firstOrNull { it.equals(candidate, ignoreCase = true) }
            }
            val volumeName = matchingMediaStoreName ?: platformVolumeName
            val description = try {
                volume.getDescription(this).trim()
            } catch (_: Exception) {
                "SD card"
            }.ifBlank { "SD card" }

            val isIndexed = !volumeName.isNullOrBlank() &&
                (matchingMediaStoreName != null ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && platformVolumeName != null))
            val isAvailable = mounted && isIndexed && !volumeName.isNullOrBlank()
            val status = when {
                readOnly -> "Mounted read-only"
                !mounted -> state.replace('_', ' ').lowercase(Locale.ROOT)
                    .replaceFirstChar { it.uppercase(Locale.ROOT) }
                !isIndexed -> "Detected, but not indexed by MediaStore"
                else -> "Ready"
            }
            val safeVolumeName = volumeName?.takeIf { it.isNotBlank() }
                ?: "__unavailable_sd_$index"
            if (locations.none { it.volumeName == safeVolumeName }) {
                locations.add(
                    CameraStorageLocation(
                        safeVolumeName,
                        description,
                        true,
                        isAvailable,
                        status
                    )
                )
            }
        }

        if (!removableVolumesFound) {
            locations.add(
                CameraStorageLocation(
                    "__no_sd_card__",
                    "SD card",
                    false,
                    false,
                    "No removable SD card detected"
                )
            )
        }
        return locations
    }

    private fun selectedCameraStorageLocation(): CameraStorageLocation? {
        val selectedVolume = getSharedPreferences("camera_settings", MODE_PRIVATE)
            .getString("camera_storage_volume", MediaStore.VOLUME_EXTERNAL_PRIMARY)
            ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
        return availableCameraStorageLocations()
            .firstOrNull { it.isAvailable && it.volumeName == selectedVolume }
    }

    private fun saveCameraModeConfiguration() {
        getSharedPreferences("camera_settings", MODE_PRIVATE)
            .edit()
            .putString("primary_camera_modes", primaryModes.filter { it != "More" }.joinToString("|"))
            .putString("more_camera_modes", moreModes.joinToString("|"))
            .apply()
    }

    private fun refreshDeviceSpecificModes() {
        // Portrait is always available as a single-camera mode. This detection
        // only records whether a future dual-camera enhancement can be offered.
        if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
            val openedId = cameraId
            rearPortraitCameraSupport = if (openedId != null) {
                detectRearPortraitCameraSupport(openedId)
            } else {
                PortraitCameraSupport(
                    available = false,
                    reason = "The rear camera is not currently open."
                )
            }
        }

        val streamMap = cameraCharacteristics
            ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val maximumHighSpeedFps = streamMap?.highSpeedVideoFpsRanges
            ?.maxOfOrNull { it.upper } ?: 0
        val isGalaxyS10e = Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
            Build.MODEL.startsWith("SM-G970", ignoreCase = true)
        val supportsSuperSlowMo = maximumHighSpeedFps >= 480 || isGalaxyS10e
        val supportsSlowMo = maximumHighSpeedFps >= 120 || isGalaxyS10e

        if (!supportsSuperSlowMo) {
            primaryModes.remove("Super Slow-Mo")
            moreModes.remove("Super Slow-Mo")
        }
        if (!supportsSlowMo) {
            primaryModes.remove("Slow-Mo")
            moreModes.remove("Slow-Mo")
        }

        moreModes.remove("Pro Video")
        primaryModes.remove("Pro Video")
        moreModes.remove("Pro")
        primaryModes.remove("Pro")
        // If an earlier capability check removed Portrait from both saved lists,
        // restore it to its original primary-strip position. Otherwise preserve
        // the user's existing mode arrangement.
        if ("Portrait" !in primaryModes && "Portrait" !in moreModes) {
            primaryModes.add(0, "Portrait")
        }
        val availableBaseModes = listOf(
            "Photo", "Video", "Night", "Single Take",
            "Food", "Panorama", "Hyperlapse", "Portrait Video"
        )
        availableBaseModes.forEach { mode ->
            if (mode !in primaryModes && mode !in moreModes) moreModes.add(mode)
        }
        if (supportsSuperSlowMo &&
            "Super Slow-Mo" !in primaryModes &&
            "Super Slow-Mo" !in moreModes
        ) {
            val insertAt = moreModes.indexOf("Hyperlapse").takeIf { it >= 0 }
                ?: moreModes.size
            moreModes.add(insertAt, "Super Slow-Mo")
        }
        if (supportsSlowMo &&
            "Slow-Mo" !in primaryModes &&
            "Slow-Mo" !in moreModes
        ) {
            val insertAt = moreModes.indexOf("Hyperlapse").takeIf { it >= 0 }
                ?: moreModes.size
            moreModes.add(insertAt, "Slow-Mo")
        }

        if (primaryModes.none { it != "More" }) {
            primaryModes.add(0, "Photo")
        }
        primaryModes.remove("More")
        primaryModes.add("More")
        if (activePrimaryMode !in primaryModes) activePrimaryMode = "More"
        saveCameraModeConfiguration()
        if (::modeRow.isInitialized) rebuildModeStrip()
    }

    private fun detectRearPortraitCameraSupport(
        openedCameraId: String
    ): PortraitCameraSupport {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return PortraitCameraSupport(
                available = false,
                reason = "Camera2 physical camera IDs require Android 9 or newer."
            )
        }

        return try {
            val characteristics = cameraManager.getCameraCharacteristics(openedCameraId)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) !=
                CameraCharacteristics.LENS_FACING_BACK
            ) {
                return PortraitCameraSupport(
                    available = false,
                    reason = "The active camera is not rear-facing."
                )
            }

            val capabilities = characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            ) ?: intArrayOf()
            val isLogicalMultiCamera = capabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
            )
            val physicalIds = if (isLogicalMultiCamera) {
                characteristics.physicalCameraIds.toList().distinct()
            } else {
                emptyList()
            }
            val rearCameraIds = cameraManager.cameraIdList.filter { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }.distinct()

            val multipleRearCameras = physicalIds.size >= 2 || rearCameraIds.size >= 2
            PortraitCameraSupport(
                available = multipleRearCameras,
                route = when {
                    physicalIds.size >= 2 -> "logical-physical"
                    rearCameraIds.size >= 2 -> "separate-camera-ids"
                    else -> null
                },
                logicalCameraId = openedCameraId.takeIf { isLogicalMultiCamera },
                physicalCameraIds = physicalIds,
                concurrentCameraIds = rearCameraIds,
                reason = when {
                    physicalIds.size >= 2 ->
                        "Multiple physical rear cameras are exposed by the logical camera."
                    rearCameraIds.size >= 2 ->
                        "Multiple rear-facing camera IDs are exposed."
                    else ->
                        "Only one rear camera is exposed through Camera2."
                }
            )
        } catch (e: CameraAccessException) {
            PortraitCameraSupport(
                available = false,
                reason = "Camera capability query failed: ${e.reason}"
            )
        } catch (e: RuntimeException) {
            PortraitCameraSupport(
                available = false,
                reason = "Camera capability query failed: ${e.message ?: "unknown error"}"
            )
        }
    }

    private fun rebuildModeStrip() {
        if (!::modeRow.isInitialized) return
        modeRow.removeAllViews()
        val labels = mutableMapOf<String, TextView>()
        primaryModes.forEach { mode ->
            val label = TextView(this).apply {
                text = mode.uppercase(Locale.ROOT)
                textSize = if (mode == "Portrait") 11f else 12f
                gravity = Gravity.CENTER
                includeFontPadding = false
                isSingleLine = true
                maxLines = 1
                setTextColor(0xFFBDBDBD.toInt())
                setPadding(dp(9), dp(9), dp(9), dp(9))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (!modeEditing) selectMode(mode)
                }
                if (modeEditing && mode != "More") {
                    contentDescription = "Hold and drag $mode"
                    setOnLongClickListener {
                        startModeDrag(it, mode, "main")
                    }
                    setModeDropTarget(this, "main", mode)
                }
            }
            modeRow.addView(
                label,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(42)
                ).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                }
            )
            labels[mode] = label
        }
        modeLabels = labels
        updateModeScrollEdgePadding()
        updatePrimaryModeLabels()
    }

    private fun updateModeScrollEdgePadding() {
        if (!::modeScroll.isInitialized || !::modeLabels.isInitialized) return
        modeScroll.post {
            val first = primaryModes.firstOrNull()?.let { modeLabels[it] }
            val last = primaryModes.lastOrNull()?.let { modeLabels[it] }
            if (first == null || last == null || modeScroll.width <= 0) return@post

            // At either end, centre the first/last mode name in the viewport.
            val leftInset = ((modeScroll.width - first.width) / 2).coerceAtLeast(0)
            val rightInset = ((modeScroll.width - last.width) / 2).coerceAtLeast(0)
            modeRow.setPadding(leftInset, 0, rightInset, 0)
            centerSelectedMode()
        }
    }

    private fun selectMode(mode: String) {
        if (mode == "More") {
            activePrimaryMode = "More"
            updatePrimaryModeLabels()
            showMoreModes()
            return
        }
        if (mode !in primaryModes) return
        if (moreModesOverlay != null) closeMoreModes()

        activePrimaryMode = mode
        activeMode = mode
        updatePrimaryModeLabels()
        if (mode == "Pro") {
            Toast.makeText(this, "Pro mode is not implemented yet.", Toast.LENGTH_SHORT).show()
            return
        }
        if (mode != "Photo" && mode != "Portrait") {
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
        centerSelectedMode()
    }

    private fun centerSelectedMode() {
        if (!::modeScroll.isInitialized || !::modeLabels.isInitialized) return
        modeScroll.post {
            val selected = modeLabels[activePrimaryMode] ?: return@post
            val target = selected.left + selected.width / 2 - modeScroll.width / 2
            val maxScroll = (modeRow.width - modeScroll.width).coerceAtLeast(0)
            modeScroll.smoothScrollTo(target.coerceIn(0, maxScroll), 0)
        }
    }

    private fun showMoreModes() {
        closeMoreModes(resetEditing = false)
        val overlay = FrameLayout(this).apply {
            // Keep the MORE panel dimming the live preview, while the real mode strip
            // underneath remains touchable and horizontally scrollable.
            setBackgroundColor(0x4D000000)
            if (!modeEditing) {
                setOnClickListener { closeMoreModes() }
            }
        }

        if (modeEditing) {
            val instruction = TextView(this).apply {
                text = "Touch and hold to move modes to or from the bottom of the screen."
                textSize = 12.5f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(0xFFF2F2F2.toInt())
                setPadding(dp(20), 0, dp(20), 0)
                isClickable = false
            }
            overlay.addView(
                instruction,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    dp(40),
                    Gravity.TOP
                ).apply { topMargin = dp(82) }
            )
        }

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
            setOnClickListener { }
        }
        addModeGridRows(grid, moreModes, editing = modeEditing, group = "more")
        if (modeEditing) setModeDropTarget(overlay, "more", null)
        if (moreModes.isEmpty()) {
            grid.addView(TextView(this).apply {
                text = if (modeEditing) "Drag modes here" else "No additional modes."
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(0xFFBBBBBB.toInt())
                setPadding(0, dp(24), 0, dp(24))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(105)
            ))
            if (modeEditing) setModeDropTarget(grid, "more", null)
        }

        overlay.addView(
            grid,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply { topMargin = if (modeEditing) dp(154) else dp(118) }
        )

        if (modeEditing) {
            val saveButton = TextView(this).apply {
                text = "Save"
                textSize = 17f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(Color.WHITE)
                isClickable = true
                isFocusable = true
                setPadding(dp(28), 0, dp(28), 0)
                setOnClickListener {
                    modeEditing = false
                    saveCameraModeConfiguration()
                    rebuildModeStrip()
                    showMoreModes()
                }
            }
            overlay.addView(
                saveButton,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    dp(48),
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply { bottomMargin = dp(72) }
            )
        } else {
            val addButton = TextView(this).apply {
                text = "+"
                textSize = 27f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(Color.WHITE)
                background = oval(0x55444444)
                contentDescription = "Edit camera modes"
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    modeEditing = true
                    rebuildModeStrip()
                    showMoreModes()
                }
            }
            overlay.addView(
                addButton,
                FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.BOTTOM).apply {
                    marginEnd = dp(18)
                    bottomMargin = dp(124)
                }
            )
        }

        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        moreModesOverlay = overlay
        if (modeEditing) {
            addBottomModeDropTargets(overlay)
        } else {
            installMoreModesTaskbarTouchProxy(overlay)
        }
    }

    /**
     * The real mode strip remains visible beneath the translucent MORE overlay.
     * These transparent hit targets sit directly over its current labels, so
     * drops reorder/move modes without drawing a second, artificial toolbar.
     */
    private fun addBottomModeDropTargets(overlay: FrameLayout) {
        if (!modeEditing || !::modeScroll.isInitialized || !::modeLabels.isInitialized) return

        overlay.post {
            if (!modeEditing || moreModesOverlay !== overlay ||
                modeScroll.width <= 0 || modeScroll.height <= 0
            ) return@post

            val barLocation = IntArray(2)
            val rootLocation = IntArray(2)
            modeScroll.getLocationOnScreen(barLocation)
            root.getLocationOnScreen(rootLocation)

            val hitLayer = FrameLayout(this)
            val layerParams = FrameLayout.LayoutParams(
                modeScroll.width,
                modeScroll.height
            ).apply {
                leftMargin = barLocation[0] - rootLocation[0]
                topMargin = barLocation[1] - rootLocation[1]
            }
            overlay.addView(hitLayer, layerParams)
            setModeDropTarget(hitLayer, "main", null)

            primaryModes.filter { it != "More" }.forEach { mode ->
                val label = modeLabels[mode] ?: return@forEach
                if (label.width <= 0) return@forEach
                val target = View(this)
                setModeDropTarget(target, "main", mode)
                hitLayer.addView(
                    target,
                    FrameLayout.LayoutParams(
                        label.width,
                        modeScroll.height
                    ).apply {
                        leftMargin = label.left - modeScroll.scrollX
                    }
                )
            }
        }
    }

    private fun setModeDropTarget(
        view: View,
        destinationGroup: String,
        targetMode: String?
    ) {
        view.setOnDragListener { target, event ->
            val payload = event.localState as? ModeDragPayload
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    if (payload != null &&
                        payload.sourceGroup == destinationGroup &&
                        payload.mode == targetMode
                    ) {
                        target.alpha = 0.14f
                    }
                    payload != null
                }
                DragEvent.ACTION_DRAG_ENTERED -> {
                    if (payload != null &&
                        !(payload.sourceGroup == destinationGroup && payload.mode == targetMode)
                    ) {
                        target.alpha = 0.55f
                    }
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    target.alpha = if (
                        payload != null &&
                        payload.sourceGroup == destinationGroup &&
                        payload.mode == targetMode
                    ) 0.14f else 1f
                    true
                }
                DragEvent.ACTION_DROP -> {
                    target.alpha = 1f
                    if (payload != null) moveCameraMode(payload, destinationGroup, targetMode)
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    target.alpha = 1f
                    true
                }
                else -> true
            }
        }
    }

    private fun startModeDrag(view: View, mode: String, sourceGroup: String): Boolean {
        val payload = ModeDragPayload(mode, sourceGroup)
        val clip = ClipData.newPlainText("camera-mode", mode)
        @Suppress("DEPRECATION")
        return view.startDrag(clip, createModeDragShadow(mode), payload, 0)
    }

    private fun createModeDragShadow(mode: String): View.DragShadowBuilder {
        val cardWidth = dp(108)
        val cardHeight = dp(104)
        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(5))
            background = rounded(Color.WHITE, 14)
            elevation = dp(5).toFloat()
            addView(
                CameraIconButton(
                    this@MainActivity,
                    "mode:$mode",
                    0xFF151515.toInt()
                ) { }.apply {
                    isClickable = false
                    isFocusable = false
                    background = null
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(52)
                )
            )
            addView(TextView(this@MainActivity).apply {
                text = mode.uppercase(Locale.ROOT)
                textSize = 11.5f
                gravity = Gravity.CENTER
                includeFontPadding = false
                isSingleLine = true
                setTextColor(0xFF171717.toInt())
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(26)
            ))
        }
        preview.measure(
            View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(cardHeight, View.MeasureSpec.EXACTLY)
        )
        preview.layout(0, 0, cardWidth, cardHeight)
        return View.DragShadowBuilder(preview)
    }

    private fun createTaskbarModeChip(mode: String): View {
        val chip = TextView(this).apply {
            text = mode.uppercase(Locale.ROOT)
            textSize = 10.5f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Color.WHITE)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = rounded(0xFF303030.toInt(), 100)
            isClickable = true
            isFocusable = true
            contentDescription = "Drag $mode to rearrange camera modes"
            setOnLongClickListener {
                startModeDrag(it, mode, "main")
            }
        }
        setModeDropTarget(chip, "main", mode)
        return chip
    }

    private fun createModeTile(
        mode: String,
        editing: Boolean = false,
        group: String = "more"
    ): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(5), dp(4), dp(5))
            isClickable = true
            isFocusable = true
            background = android.graphics.drawable.StateListDrawable().apply {
                val pressed = rounded(0x33444444, 12)
                addState(intArrayOf(android.R.attr.state_pressed), pressed)
                addState(intArrayOf(), GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                })
            }
            addView(
                CameraIconButton(this@MainActivity, "mode:$mode") { }.apply {
                    isClickable = false
                    isFocusable = false
                    background = null
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(54)
                )
            )
            addView(TextView(this@MainActivity).apply {
                text = mode.uppercase(Locale.ROOT)
                textSize = 10.5f
                gravity = Gravity.CENTER
                includeFontPadding = false
                isSingleLine = true
                setTextColor(0xFFE2E2E2.toInt())
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24)
            ))
        }

        if (editing) {
            tile.contentDescription = "Drag $mode to rearrange camera modes"
            tile.setOnLongClickListener {
                startModeDrag(tile, mode, group)
            }
            setModeDropTarget(tile, group, mode)
        } else {
            tile.setOnClickListener {
                activeMode = mode
                activePrimaryMode = if (mode in primaryModes) mode else "More"
                closeMoreModes()
                updatePrimaryModeLabels()
                if (mode != "Photo" && mode != "Portrait") {
                    Toast.makeText(
                        this@MainActivity,
                        "$mode mode is not implemented in this build yet.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        return tile
    }

    private fun addModeGridRows(
        container: LinearLayout,
        modes: List<String>,
        editing: Boolean,
        group: String
    ) {
        if (editing) setModeDropTarget(container, group, null)
        modes.chunked(3).forEach { rowModes ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            if (editing) setModeDropTarget(row, group, null)
            rowModes.forEach { mode ->
                row.addView(
                    createModeTile(mode, editing, group),
                    LinearLayout.LayoutParams(0, dp(105), 1f)
                )
            }
            repeat(3 - rowModes.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, dp(105), 1f))
            }
            container.addView(row)
        }
    }

    private fun moveCameraMode(
        payload: ModeDragPayload,
        destinationGroup: String,
        targetMode: String?
    ) {
        if (payload.sourceGroup == destinationGroup && payload.mode == targetMode) return
        val source = if (payload.sourceGroup == "main") primaryModes else moreModes
        val destination = if (destinationGroup == "main") primaryModes else moreModes

        if (payload.mode !in source) return
        source.remove(payload.mode)

        if (destinationGroup == "main") {
            destination.remove("More")
        }
        val insertAt = if (targetMode == null) {
            destination.size
        } else {
            destination.indexOf(targetMode).takeIf { it >= 0 } ?: destination.size
        }
        destination.add(insertAt.coerceIn(0, destination.size), payload.mode)

        if (destinationGroup == "main") {
            destination.remove("More")
            destination.add("More")
        }
        if (primaryModes.none { it != "More" }) {
            moreModes.remove("Photo")
            primaryModes.add(0, "Photo")
        }
        if (activePrimaryMode !in primaryModes) activePrimaryMode = "More"
        saveCameraModeConfiguration()
        rebuildModeStrip()
        showMoreModes()
    }

    private fun installMoreModesTaskbarTouchProxy(overlay: FrameLayout) {
        // Place a transparent sibling above the MORE overlay exactly over the real
        // taskbar. It forwards taps and complete drag/fling gestures to modeScroll.
        overlay.post {
            if (moreModesOverlay !== overlay || modeEditing ||
                !::modeScroll.isInitialized || modeScroll.width <= 0
            ) return@post

            moreModesTaskbarTouchProxy?.let { root.removeView(it) }
            val rootLocation = IntArray(2)
            val stripLocation = IntArray(2)
            root.getLocationOnScreen(rootLocation)
            modeScroll.getLocationOnScreen(stripLocation)

            val proxy = object : View(this@MainActivity) {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    val forwarded = MotionEvent.obtain(event)
                    forwarded.offsetLocation(
                        (stripLocation[0] - (rootLocation[0] +
                            (left))).toFloat(),
                        (stripLocation[1] - (rootLocation[1] +
                            (top))).toFloat()
                    )
                    try {
                        modeScroll.dispatchTouchEvent(forwarded)
                    } finally {
                        forwarded.recycle()
                    }
                    return true
                }
            }.apply {
                isClickable = true
                isFocusable = false
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = "Camera mode taskbar"
            }

            val params = FrameLayout.LayoutParams(modeScroll.width, modeScroll.height).apply {
                leftMargin = stripLocation[0] - rootLocation[0]
                topMargin = stripLocation[1] - rootLocation[1]
            }
            root.addView(proxy, params)
            moreModesTaskbarTouchProxy = proxy
        }
    }

    private fun closeMoreModes(resetEditing: Boolean = true) {
        moreModesTaskbarTouchProxy?.let { root.removeView(it) }
        moreModesTaskbarTouchProxy = null
        moreModesOverlay?.let { root.removeView(it) }
        moreModesOverlay = null
        if (resetEditing) modeEditing = false
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
        return CameraIconButton(this, icon, onPressed = action).apply {
            contentDescription = description
        }
    }

    private fun weightedControl() = LinearLayout.LayoutParams(0, dp(52), 1f)

    private fun cycleFlash() {
        showExpandedOptions("flash")
    }

    private fun updateFlashButton() {
        if (!::flashButton.isInitialized) return
        flashButton.contentDescription = when (flashMode) {
            0 -> "Flash automatic"
            1 -> "Flash on"
            else -> "Flash off"
        }
        flashButton.invalidate()
    }

    private fun cycleTimer() {
        showExpandedOptions("timer")
    }

    private fun updateTimerButton() {
        if (!::timerButton.isInitialized) return
        timerButton.contentDescription = when (timerSeconds) {
            0 -> "Timer off"
            else -> "Timer $timerSeconds seconds"
        }
        timerButton.invalidate()
    }

    private fun showExpandedOptions(kind: String) {
        if (!::expandedOptionsRow.isInitialized) return

        if (expandedOptionsKind == kind) {
            closeExpandedOptions()
            return
        }

        expandedOptionsKind = kind
        quickControls.visibility = View.GONE
        expandedOptionsRow.removeAllViews()

        val options = when (kind) {
            "flash" -> listOf("flashOff", "flashAuto", "flashOn")
            "timer" -> listOf("timerOff", "timer2", "timer5", "timer10")
            else -> listOf("ratio_3_4", "ratio_9_16", "ratio_1_1", "ratio_full")
        }

        options.forEach { option ->
            val button = CameraIconButton(this, option) {
                when (kind) {
                    "flash" -> {
                        flashMode = when (option) {
                            "flashOff" -> 2
                            "flashAuto" -> 0
                            else -> 1
                        }
                        updateFlashButton()
                        startPreviewRepeating()
                    }
                    "timer" -> {
                        timerSeconds = when (option) {
                            "timer2" -> 2
                            "timer5" -> 5
                            "timer10" -> 10
                            else -> 0
                        }
                        updateTimerButton()
                    }
                    "ratio" -> {
                        val selected = when (option) {
                            "ratio_9_16" -> "9:16"
                            "ratio_1_1" -> "1:1"
                            "ratio_full" -> "Full"
                            else -> "3:4"
                        }
                        applyPhotoRatio(selected)
                    }
                }
                closeExpandedOptions()
            }.apply {
                contentDescription = when (option) {
                    "flashOff" -> "Flash off"
                    "flashAuto" -> "Flash automatic"
                    "flashOn" -> "Flash on"
                    "timerOff" -> "Timer off"
                    "timer2" -> "2 second timer"
                    "timer5" -> "5 second timer"
                    "timer10" -> "10 second timer"
                    "ratio_3_4" -> "3 by 4 photo ratio"
                    "ratio_9_16" -> "9 by 16 photo ratio"
                    "ratio_1_1" -> "Square photo ratio"
                    else -> "Full photo ratio"
                }
                val available = cameraCharacteristics
                    ?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (option == "flashOn" && !available) {
                    isEnabled = false
                    alpha = 0.35f
                }
            }
            expandedOptionsRow.addView(
                button,
                LinearLayout.LayoutParams(0, dp(52), 1f)
            )
        }

        expandedOptionsRow.visibility = View.VISIBLE
    }

    private fun closeExpandedOptions() {
        expandedOptionsKind = null
        if (::expandedOptionsRow.isInitialized) {
            expandedOptionsRow.visibility = View.GONE
            expandedOptionsRow.removeAllViews()
        }
        if (::quickControls.isInitialized) {
            quickControls.visibility = View.VISIBLE
        }
    }

    private fun showAspectRatioPicker() {
        showExpandedOptions("ratio")
    }

    private fun applyPhotoRatio(selected: String) {
        if (selected == aspectRatio) return
        aspectRatio = selected
        getSharedPreferences("camera_settings", MODE_PRIVATE)
            .edit().putString("photo_ratio", selected).apply()
        updateRatioChrome()
        recreateImageReaderAndSession()
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

    private fun showCaptureOverlay(frozenFrame: Bitmap? = null) {
        if (!::root.isInitialized || captureOverlay != null) return

        // Keep only the TextureView visible so the live viewfinder remains clear.
        // Saving every child state also handles temporary panels and mode overlays.
        captureHiddenViewStates = (0 until root.childCount)
            .map { root.getChildAt(it) }
            .filter { it !== previewView }
            .map { it to it.visibility }
        captureHiddenViewStates?.forEach { (view, _) ->
            view.animate().cancel()
            view.visibility = View.GONE
        }

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            elevation = dp(100).toFloat()
            alpha = 0f
            contentDescription = "Capturing image"
        }

        if (frozenFrame != null && !frozenFrame.isRecycled) {
            captureFrozenPreview = frozenFrame
            val frozenImageView = ImageView(this).apply {
                setImageBitmap(frozenFrame)
                scaleType = ImageView.ScaleType.FIT_XY
                isClickable = false
                contentDescription = "Frozen camera preview"
            }
            captureFrozenPreviewView = frozenImageView
            overlay.addView(
                frozenImageView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val message = TextView(this).apply {
            text = "Capturing image..."
            textSize = 18f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create(
                "sans-serif-medium",
                android.graphics.Typeface.NORMAL
            )
            setShadowLayer(dp(8).toFloat(), 0f, dp(2).toFloat(), 0xCC000000.toInt())
            isClickable = false
        }
        overlay.addView(
            message,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
        )

        val indicator = CaptureLoadingIndicator(this)
        overlay.addView(
            indicator,
            FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(58)
            }
        )

        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        overlay.bringToFront()
        captureOverlay = overlay
        overlay.animate()
            .alpha(1f)
            .setDuration(180L)
            .start()
    }

    private fun dismissCaptureOverlay(animate: Boolean = true) {
        val overlay = captureOverlay ?: return
        captureOverlay = null

        val restoreUi = {
            if (overlay.parent === root) root.removeView(overlay)
            captureFrozenPreviewView?.setImageDrawable(null)
            captureFrozenPreviewView = null
            captureFrozenPreview?.let { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            captureFrozenPreview = null
            captureHiddenViewStates?.forEach { (view, visibility) ->
                if (view.parent === root) view.visibility = visibility
            }
            captureHiddenViewStates = null
        }

        if (animate && overlay.isAttachedToWindow && !isFinishing) {
            overlay.animate().cancel()
            overlay.animate()
                .alpha(0f)
                .setDuration(180L)
                .withEndAction { restoreUi() }
                .start()
        } else {
            restoreUi()
        }
    }

    private fun onShutterPressed() {
        if (activeTab != "Basic" || activeMode !in listOf("Photo", "Portrait")) return
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
        if (pendingStillCapture != null || portraitProcessing) {
            Toast.makeText(
                this,
                "A photo is still being captured. Please wait.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val device = cameraDevice
        val session = captureSession
        val reader = imageReader
        if (device == null || session == null || reader == null) {
            Toast.makeText(
                this,
                "Camera is still preparing. Try again in a moment.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val portraitCapture = activeMode == "Portrait"
        val rotation = jpegOrientation()
        val captureId = ++captureSequenceId
        val ticket = PendingStillCapture(
            id = captureId,
            portrait = portraitCapture,
            rotation = rotation,
            focusPoints = portraitFocusPoints.ifEmpty {
                listOf(portraitFocusX to portraitFocusY)
            }
        )
        pendingStillCapture = ticket
        val frozenPreview = if (portraitCapture) {
            runCatching { previewView.bitmap }.getOrNull()
        } else {
            null
        }
        showCaptureOverlay(frozenPreview)
        if (portraitCapture) {
            // Keep the TextureView on the last displayed frame while Portrait
            // segmentation runs; the still capture request is submitted next.
            try {
                session.stopRepeating()
            } catch (_: CameraAccessException) {
                // The frozen bitmap above still provides the visual hold.
            }
        }
        cancelCaptureTimeout()
        val timeout = Runnable {
            if (pendingStillCapture?.id == captureId) {
                pendingStillCapture = null
                captureTimeoutRunnable = null
                dismissCaptureOverlay()
                Toast.makeText(
                    this@MainActivity,
                    "Capture timed out: the camera did not deliver a JPEG. Please try again.",
                    Toast.LENGTH_LONG
                ).show()
                startPreviewRepeating()
            }
        }
        captureTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, 15_000L)
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, currentAfMode())
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                applyFocusRegions(this)
                setFlashForRequest(this)
                applyFilter(this)
                applyZoom(this)
                // Portrait processing rotates the decoded pixels itself, so it
                // requests an unrotated JPEG and avoids retaining stale EXIF rotation.
                set(CaptureRequest.JPEG_ORIENTATION, if (portraitCapture) 0 else rotation)
            }
            session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: android.hardware.camera2.TotalCaptureResult
                ) {
                    if (!portraitCapture) startPreviewRepeating()
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: android.hardware.camera2.CaptureFailure
                ) {
                    failPendingCapture(
                        captureId,
                        "Capture failed (reason ${failure.reason}). Try again."
                    )
                    startPreviewRepeating()
                }

                override fun onCaptureSequenceAborted(
                    session: CameraCaptureSession,
                    sequenceId: Int
                ) {
                    failPendingCapture(
                        captureId,
                        "The camera cancelled the capture. Please try again."
                    )
                    startPreviewRepeating()
                }
            }, cameraHandler)
        } catch (e: Exception) {
            failPendingCapture(
                captureId,
                "Capture failed: ${e.message ?: e.javaClass.simpleName}"
            )
            startPreviewRepeating()
        }
    }

    private fun cancelCaptureTimeout() {
        captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        captureTimeoutRunnable = null
    }

    private fun failPendingCapture(captureId: Long, message: String) {
        if (pendingStillCapture?.id != captureId) return
        pendingStillCapture = null
        cancelCaptureTimeout()
        runOnUiThread {
            startPreviewRepeating()
            dismissCaptureOverlay()
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun applyFocusRegions(builder: CaptureRequest.Builder) {
        val region = focusMeteringRegion ?: return
        val characteristics = cameraCharacteristics ?: return
        if ((characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
        }
        if ((characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        }
    }

    /**
     * Creates a subject-aware Portrait image using an offline, category-agnostic
     * GrabCut mask. A normal tap selects one subject; a long press adds another.
     */
    private fun createPortraitJpeg(
        jpeg: ByteArray,
        focusPoints: List<Pair<Float, Float>>,
        rotation: Int
    ): ByteArray {
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: throw IllegalStateException("The captured JPEG could not be decoded")
        val oriented = if (rotation % 360 != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }

        try {
            val width = oriented.width
            val height = oriented.height
            // Segmentation only needs enough detail to understand subject boundaries.
            // Keeping this at 800px cuts GrabCut's pixel workload by about 56% vs 1200px.
            val scale = minOf(1f, 800f / max(width, height).toFloat())
            val smallWidth = (width * scale).toInt().coerceAtLeast(1)
            val smallHeight = (height * scale).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(oriented, smallWidth, smallHeight, true)
            val smallPixels = IntArray(smallWidth * smallHeight)

            val segmentation = try {
                small.getPixels(
                    smallPixels, 0, smallWidth, 0, 0, smallWidth, smallHeight
                )
                PortraitSubjectSegmenter().createMask(small, focusPoints)
            } finally {
                if (small !== oriented) small.recycle()
            }

            // Resize the feathered mask once with native bilinear interpolation.
            // This replaces a costly mask-sampling function call for every output pixel.
            val sourceMask = Mat(segmentation.height, segmentation.width, CvType.CV_8UC1)
            val fullMaskMat = Mat()
            val fullResolutionMask = ByteArray(width * height)
            try {
                sourceMask.put(0, 0, segmentation.alpha)
                Imgproc.resize(
                    sourceMask,
                    fullMaskMat,
                    org.opencv.core.Size(width.toDouble(), height.toDouble()),
                    0.0,
                    0.0,
                    Imgproc.INTER_LINEAR
                )
                fullMaskMat.get(0, 0, fullResolutionMask)
            } finally {
                sourceMask.release()
                fullMaskMat.release()
            }

            val blurredPixels = blurArgbPixels(
                smallPixels,
                smallWidth,
                smallHeight,
                14
            )
            // Precompute source positions so the full-resolution composite loop
            // performs no per-pixel coordinate division or bilinear-mask calculations.
            val sampleXMap = IntArray(width) { x ->
                ((x.toLong() * smallWidth) / width).toInt().coerceIn(0, smallWidth - 1)
            }
            val sampleYMap = IntArray(height) { y ->
                ((y.toLong() * smallHeight) / height).toInt().coerceIn(0, smallHeight - 1)
            }

            // Composite in-place on the oriented capture. The source JPEG remains
            // available as the fallback, so we do not need a second full-size Bitmap.
            val originalRow = IntArray(width)
            val outputRow = IntArray(width)

            for (y in 0 until height) {
                oriented.getPixels(originalRow, 0, width, 0, y, width, 1)
                val blurRow = sampleYMap[y] * smallWidth
                val maskRow = y * width

                for (x in 0 until width) {
                    val subjectAlpha = fullResolutionMask[maskRow + x].toInt() and 0xFF
                    val blurAmount = (255 - subjectAlpha) / 255f
                    val original = originalRow[x]
                    if (blurAmount <= 0.002f) {
                        outputRow[x] = original
                    } else {
                        val blurred = blurredPixels[blurRow + sampleXMap[x]]
                        val sharpWeight = 1f - blurAmount
                        val red = ((((original shr 16) and 0xFF) * sharpWeight) +
                            (((blurred shr 16) and 0xFF) * blurAmount)).toInt()
                        val green = ((((original shr 8) and 0xFF) * sharpWeight) +
                            (((blurred shr 8) and 0xFF) * blurAmount)).toInt()
                        val blue = (((original and 0xFF) * sharpWeight) +
                            ((blurred and 0xFF) * blurAmount)).toInt()
                        outputRow[x] = (0xFF shl 24) or
                            (red.coerceIn(0, 255) shl 16) or
                            (green.coerceIn(0, 255) shl 8) or
                            blue.coerceIn(0, 255)
                    }
                }
                oriented.setPixels(outputRow, 0, width, 0, y, width, 1)
            }

            val stream = ByteArrayOutputStream()
            val compressed = oriented.compress(Bitmap.CompressFormat.JPEG, 94, stream)
            if (!compressed) throw IllegalStateException("Portrait JPEG encoding failed")
            return stream.toByteArray()
        } finally {
            oriented.recycle()
        }
    }

    /**
     * Blur the downscaled background with OpenCV's native Gaussian implementation.
     * Three pairs of radius-14 box-blur passes have roughly the same spread as
     * a Gaussian with sigma 14.5, but the native implementation avoids six Kotlin
     * pixel-by-pixel passes and their repeated full-array allocations.
     */
    private fun blurArgbPixels(
        source: IntArray,
        width: Int,
        height: Int,
        radius: Int
    ): IntArray {
        val bitmap = Bitmap.createBitmap(source, width, height, Bitmap.Config.ARGB_8888)
        val sourceMat = Mat()
        val blurredMat = Mat()
        try {
            Utils.bitmapToMat(bitmap, sourceMat)
            val sigma = radius * 1.04
            Imgproc.GaussianBlur(
                sourceMat,
                blurredMat,
                org.opencv.core.Size(0.0, 0.0),
                sigma,
                sigma,
                Core.BORDER_REPLICATE
            )
            Utils.matToBitmap(blurredMat, bitmap)
            return IntArray(width * height).also { pixels ->
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            }
        } finally {
            sourceMat.release()
            blurredMat.release()
            bitmap.recycle()
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

    private fun isPreviewGesturePoint(x: Float, y: Float): Boolean {
        if (!::previewView.isInitialized ||
            x < 0f || y < 0f ||
            x > previewView.width.toFloat() ||
            y > previewView.height.toFloat()
        ) return false

        // The TextureView fills the screen, but the visible camera interaction
        // area excludes the top controls and the complete bottom control stack.
        val topBoundary = if (::topPanel.isInitialized && topPanel.visibility == View.VISIBLE) {
            topPanel.bottom.toFloat()
        } else {
            0f
        }
        val bottomBoundary = if (
            ::bottomPanel.isInitialized && bottomPanel.visibility == View.VISIBLE
        ) {
            bottomPanel.top.toFloat()
        } else {
            previewView.height.toFloat()
        }
        return y >= topBoundary && y <= bottomBoundary
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
        zoomChipsRow.removeAllViews()

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

        // Ultra-wide ratios are exposed in 0.1× steps when the camera reports
        // support below 1×. Main/telephoto zoom uses every whole 1× step.
        if (minZoom < 1f) {
            addLevel(minZoom)
            var tenthStep = kotlin.math.ceil(minZoom * 10f - 0.001f).toInt()
            while (tenthStep < 10) {
                addLevel(tenthStep / 10f)
                tenthStep++
            }
        }
        addLevel(1f)
        var wholeZoom = 2f
        while (wholeZoom <= maxZoom + 0.001f) {
            addLevel(wholeZoom)
            wholeZoom += 1f
        }
        if (maxZoom > 1f && levels.none { abs(it - maxZoom) < 0.02f }) {
            addLevel(maxZoom)
        }
        if (levels.isEmpty()) addLevel(1f)
        zoomLevels = levels.sorted()
        zoomRatio = zoomRatio.coerceIn(minZoom, maxZoom)

        val selectedIndex = zoomLevels.indices.minByOrNull {
            abs(zoomRatio - zoomLevels[it])
        } ?: 0
        zoomLevels.forEachIndexed { index, level ->
            val selected = index == selectedIndex
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
            zoomChipsRow.addView(
                chip,
                LinearLayout.LayoutParams(diameter, dp(46)).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                }
            )
        }
        zoomRow.post {
            val selectedChip = zoomChipsRow.getChildAt(selectedIndex)
            val targetScroll = (
                selectedChip.left + selectedChip.width / 2 - zoomRow.width / 2
            ).coerceAtLeast(0)
            zoomRow.smoothScrollTo(targetScroll, 0)
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
            val latestUri = findLatestCameraPhotoUri()
            mainHandler.post {
                if (!isFinishing && ::galleryThumbnail.isInitialized) {
                    if (latestUri != null) {
                        showGalleryThumbnail(latestUri)
                    } else {
                        galleryThumbnail.setImageDrawable(textDrawablePlaceholder())
                        galleryPlaceholder.visibility = View.VISIBLE
                    }
                }
            }
        }.start()
    }

    private fun findLatestCameraPhotoUri(): Uri? {
        return try {
            val storage = selectedCameraStorageLocation() ?: return null
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(storage.volumeName)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection: String
            val selectionArgs: Array<String>
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection = "(${MediaStore.Images.Media.RELATIVE_PATH} = ? OR " +
                    "${MediaStore.Images.Media.RELATIVE_PATH} = ? OR " +
                    "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?)"
                selectionArgs = arrayOf(
                    Environment.DIRECTORY_DCIM + "/Camera",
                    Environment.DIRECTORY_DCIM + "/Camera/",
                    Environment.DIRECTORY_DCIM + "/Camera/%"
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
                "${MediaStore.Images.Media.DATE_TAKEN} DESC, " +
                    "${MediaStore.Images.Media.DATE_ADDED} DESC, " +
                    "${MediaStore.Images.Media._ID} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    )
                    ContentUris.withAppendedId(collection, id)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Gets the newest image or video in the selected DCIM/Camera folder.
     * Samsung Camera passes the selected media item to Gallery, not a
     * bucketId directory URI.
     */
    private fun findLatestCameraGalleryItem(): CameraGalleryItem? {
        return try {
            val storage = selectedCameraStorageLocation()
            val volumeName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                storage?.volumeName ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
            } else {
                "external"
            }
            val filesUri = MediaStore.Files.getContentUri(volumeName)
            val idColumn = MediaStore.MediaColumns._ID
            val mediaTypeColumn = MediaStore.Files.FileColumns.MEDIA_TYPE
            val projection = arrayOf(
                idColumn,
                mediaTypeColumn,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.ORIENTATION,
                MediaStore.MediaColumns.DATA
            )
            val selection: String
            val selectionArgs: Array<String>

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relativePath = MediaStore.MediaColumns.RELATIVE_PATH
                selection = "(" + relativePath + " = ? OR " +
                    relativePath + " = ? OR " +
                    relativePath + " LIKE ?) AND " +
                    mediaTypeColumn + " IN (?, ?)"
                selectionArgs = arrayOf(
                    Environment.DIRECTORY_DCIM + "/Camera/",
                    Environment.DIRECTORY_DCIM + "/Camera",
                    Environment.DIRECTORY_DCIM + "/Camera/%",
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
                )
            } else {
                selection = MediaStore.MediaColumns.DATA + " LIKE ? AND " +
                    mediaTypeColumn + " IN (?, ?)"
                selectionArgs = arrayOf(
                    "%/DCIM/Camera/%",
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
                )
            }

            contentResolver.query(
                filesUri,
                projection,
                selection,
                selectionArgs,
                MediaStore.MediaColumns.DATE_TAKEN + " DESC, " +
                    MediaStore.MediaColumns.DATE_ADDED + " DESC, " +
                    MediaStore.MediaColumns._ID + " DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(idColumn))
                    val mediaType = cursor.getInt(cursor.getColumnIndexOrThrow(mediaTypeColumn))
                    val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (isVideo) {
                            MediaStore.Video.Media.getContentUri(volumeName)
                        } else {
                            MediaStore.Images.Media.getContentUri(volumeName)
                        }
                    } else {
                        if (isVideo) {
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        } else {
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        }
                    }
                    val dateTakenIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                    val dateAddedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                    val orientationIndex = cursor.getColumnIndex(MediaStore.MediaColumns.ORIENTATION)
                    val pathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val dateTaken = if (dateTakenIndex >= 0) cursor.getLong(dateTakenIndex) else 0L
                    val dateAdded = if (dateAddedIndex >= 0) cursor.getLong(dateAddedIndex) else 0L
                    val orientation = if (!isVideo && orientationIndex >= 0) {
                        cursor.getInt(orientationIndex)
                    } else {
                        0
                    }
                    val filePath = if (pathIndex >= 0 && !cursor.isNull(pathIndex)) {
                        cursor.getString(pathIndex)
                    } else {
                        null
                    }

                    CameraGalleryItem(
                        uri = ContentUris.withAppendedId(collection, id),
                        mimeType = if (isVideo) "video/*" else "image/*",
                        dateTaken = if (dateTaken > 0L) dateTaken else dateAdded * 1000L,
                        filePath = filePath,
                        orientation = orientation
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Mirrors Samsung Camera's "viewbuckets" extra for its Gallery activity.
     * Bucket IDs are the lowercase-path hashes Gallery3D expects.
     */
    private fun cameraGalleryViewBuckets(): ArrayList<Int> {
        val storageRoots = linkedSetOf(
            Environment.getExternalStorageDirectory().absolutePath
        )
        val storageManager = getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        storageManager?.storageVolumes?.forEach { volume ->
            if (!volume.isRemovable) return@forEach
            if (volume.state != Environment.MEDIA_MOUNTED &&
                volume.state != Environment.MEDIA_MOUNTED_READ_ONLY
            ) {
                return@forEach
            }
            val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                volume.directory?.absolutePath
            } else {
                null
            } ?: volume.uuid?.let { "/storage/" + it }
            if (!path.isNullOrBlank()) storageRoots.add(path)
        }

        val cameraFolders = listOf(
            "DCIM/Camera",
            "DCIM/AR Emoji camera",
            "DCIM/Bixby Vision",
            "DCIM/AR Doodle",
            "DCIM/Deco Pic"
        )
        return ArrayList<Int>().apply {
            storageRoots.forEach { root ->
                cameraFolders.forEach { folder ->
                    add((root + "/" + folder).lowercase(Locale.US).hashCode())
                }
            }
        }
    }

    private fun openStockCameraAlbum() {
        if (!hasGalleryReadPermission()) {
            requestGalleryReadPermission()
            Toast.makeText(
                this,
                "Allow photo access to open the Camera album.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val galleryPackage = "com.sec.android.gallery3d"
        val media = findLatestCameraGalleryItem()
        if (media == null) {
            val launchIntent = packageManager.getLaunchIntentForPackage(galleryPackage)
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(
                    this,
                    "Samsung Gallery isn't available on this device.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        // This follows Samsung Camera's own GalleryIntentHelper contract:
        // open the latest media item and provide the Camera bucket list.
        val galleryIntent = Intent(Intent.ACTION_VIEW).apply {
            setClassName(
                galleryPackage,
                "com.sec.android.gallery3d.app.GalleryActivity"
            )
            setDataAndType(media.uri, media.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("from-Camera", true)
            putExtra("date", media.dateTaken)
            putExtra("filepath", media.filePath)
            putExtra("orientation", media.orientation)
            putIntegerArrayListExtra("viewbuckets", cameraGalleryViewBuckets())
        }

        try {
            startActivity(galleryIntent)
        } catch (_: Exception) {
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(galleryPackage)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                } else {
                    Toast.makeText(
                        this,
                        "Couldn't open Samsung Gallery.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Couldn't open Samsung Gallery.",
                    Toast.LENGTH_LONG
                ).show()
            }
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
                        refreshDeviceSpecificModes()
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
                // The JPEG callback uses the main queue rather than the camera
                // callback queue. The capture ticket stays active until this
                // listener has actually consumed the still image.
                val ticket = pendingStillCapture
                val image = try {
                    source.acquireNextImage()
                } catch (error: Throwable) {
                    ticket?.let {
                        failPendingCapture(
                            it.id,
                            "Couldn't receive the captured image: ${error.javaClass.simpleName}"
                        )
                    }
                    return@setOnImageAvailableListener
                } ?: run {
                    ticket?.let {
                        failPendingCapture(it.id, "The camera returned an empty image. Try again.")
                    }
                    return@setOnImageAvailableListener
                }

                val bytes = try {
                    val buffer: ByteBuffer = image.planes[0].buffer
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                } catch (error: Throwable) {
                    ticket?.let {
                        failPendingCapture(
                            it.id,
                            "Couldn't read the captured image: ${error.javaClass.simpleName}"
                        )
                    }
                    return@setOnImageAvailableListener
                } finally {
                    image.close()
                }

                // A slow JPEG can arrive after the user-visible timeout. Save it
                // as a normal capture rather than dropping a valid camera frame.
                if (ticket == null || pendingStillCapture?.id != ticket.id) {
                    saveJpeg(bytes)
                    return@setOnImageAvailableListener
                }

                pendingStillCapture = null
                cancelCaptureTimeout()
                if (ticket.portrait) {
                    portraitProcessing = true
                    Thread({
                        var savedPhotoUri: Uri? = null
                        var completionMessage: String? = null
                        try {
                            // Publish the original first, then replace it after the
                            // offline Portrait effect has finished processing.
                            savedPhotoUri = saveJpeg(bytes, showSavedToast = false)
                                ?: return@Thread

                            val portraitBytes = createPortraitJpeg(
                                bytes,
                                ticket.focusPoints,
                                ticket.rotation
                            )
                            replaceSavedJpeg(savedPhotoUri, portraitBytes)
                            completionMessage = "Portrait effect applied."
                        } catch (error: Throwable) {
                            val detail = "${error.javaClass.simpleName}: ${error.message ?: "no details"}"
                            completionMessage =
                                "Portrait processing failed ($detail). Original photo was kept."
                        } finally {
                            portraitProcessing = false
                            mainHandler.post {
                                savedPhotoUri?.let { showGalleryThumbnail(it) }
                                startPreviewRepeating()
                                dismissCaptureOverlay()
                                completionMessage?.let { message ->
                                    Toast.makeText(
                                        this@MainActivity,
                                        message,
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }, "PortraitImageProcessor").start()
                } else {
                    // Keep disk I/O off the main thread so the loading animation
                    // remains responsive while the captured JPEG is written.
                    Thread({
                        val savedPhotoUri = saveJpeg(bytes, showSavedToast = false)
                        mainHandler.post {
                            dismissCaptureOverlay()
                            if (savedPhotoUri != null) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Photo saved.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }, "PhotoImageSaver").start()
                }
            }, mainHandler)
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
            cameraCapabilityReport = try {
                CameraCapabilityAuditor(cameraManager).createReport(
                    activeCameraId = cameraId ?: "unknown",
                    activeDevice = device,
                    previewSurface = previewSurface,
                    jpegSurface = reader.surface,
                    previewSize = size,
                    jpegSize = Size(reader.width, reader.height)
                )
            } catch (error: Exception) {
                "Camera capability audit failed: ${error.message ?: error.javaClass.simpleName}"
            }
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

    private fun currentAfMode(): Int {
        val supported = cameraCharacteristics
            ?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.toSet().orEmpty()
        val canMeterFocus = focusMeteringRegion != null &&
            (cameraCharacteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0
        return when {
            canMeterFocus && supported.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                CaptureRequest.CONTROL_AF_MODE_AUTO
            supported.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            supported.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                CaptureRequest.CONTROL_AF_MODE_AUTO
            else -> CaptureRequest.CONTROL_AF_MODE_OFF
        }
    }

    private fun createPreviewRequest(afTrigger: Int): CaptureRequest.Builder? {
        val device = cameraDevice ?: return null
        val surface = previewSurface ?: return null
        return try {
            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, currentAfMode())
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                val region = focusMeteringRegion
                val characteristics = cameraCharacteristics
                if (region != null && (characteristics
                        ?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0
                ) {
                    set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                    if (currentAfMode() != CaptureRequest.CONTROL_AF_MODE_OFF) {
                        set(CaptureRequest.CONTROL_AF_TRIGGER, afTrigger)
                    }
                }
                if (region != null && (characteristics
                        ?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0
                ) {
                    set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
                }
                applyFilter(this)
                applyZoom(this)
            }
        } catch (e: CameraAccessException) {
            runOnUiThread {
                Toast.makeText(this, "Preview error: ${e.reason}", Toast.LENGTH_SHORT).show()
            }
            null
        }
    }

    private fun startPreviewRepeating() {
        val session = captureSession ?: return
        val request = createPreviewRequest(CaptureRequest.CONTROL_AF_TRIGGER_IDLE) ?: return
        try {
            session.setRepeatingRequest(request.build(), null, cameraHandler)
        } catch (e: CameraAccessException) {
            runOnUiThread {
                Toast.makeText(this, "Preview error: ${e.reason}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun currentZoomCrop(activeArray: Rect): Rect {
        val zoom = zoomRatio.coerceAtLeast(1f)
        val cropWidth = (activeArray.width() / zoom).toInt().coerceAtLeast(1)
        val cropHeight = (activeArray.height() / zoom).toInt().coerceAtLeast(1)
        val left = activeArray.centerX() - cropWidth / 2
        val top = activeArray.centerY() - cropHeight / 2
        return Rect(left, top, left + cropWidth, top + cropHeight)
    }

    private fun showFocusIndicator(x: Float, y: Float) {
        if (!::focusIndicator.isInitialized) return
        val size = dp(72).toFloat()
        focusIndicator.animate().cancel()
        focusIndicator.visibility = View.VISIBLE
        focusIndicator.alpha = 1f
        focusIndicator.scaleX = 0.88f
        focusIndicator.scaleY = 0.88f
        focusIndicator.x = (x - size / 2f).coerceIn(
            0f, (root.width - size).coerceAtLeast(0f)
        )
        focusIndicator.y = (y - size / 2f).coerceIn(
            0f, (root.height - size).coerceAtLeast(0f)
        )
        focusIndicator.bringToFront()
        focusIndicator.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(150L)
            .start()
        mainHandler.removeCallbacks(hideFocusIndicatorRunnable)
        mainHandler.postDelayed(hideFocusIndicatorRunnable, 1_000L)
    }

    private fun focusAtPoint(
        viewX: Float,
        viewY: Float,
        addPortraitSubject: Boolean = false
    ) {
        val characteristics = cameraCharacteristics ?: return
        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: return
        val viewWidth = previewView.width.toFloat()
        val viewHeight = previewView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        val displayX = (viewX / viewWidth).coerceIn(0f, 1f)
        val displayY = (viewY / viewHeight).coerceIn(0f, 1f)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val displayDegrees = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val rotationToDisplay = if (
            characteristics.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        ) {
            (sensorOrientation + displayDegrees) % 360
        } else {
            (sensorOrientation - displayDegrees + 360) % 360
        }
        val (sensorX, sensorY) = when (rotationToDisplay) {
            90 -> displayY to (1f - displayX)
            180 -> (1f - displayX) to (1f - displayY)
            270 -> (1f - displayY) to displayX
            else -> displayX to displayY
        }

        val crop = currentZoomCrop(activeArray)
        val pointX = (crop.left + sensorX * crop.width()).toInt()
            .coerceIn(crop.left, crop.right - 1)
        val pointY = (crop.top + sensorY * crop.height()).toInt()
            .coerceIn(crop.top, crop.bottom - 1)
        val regionSize = (minOf(crop.width(), crop.height()) * 0.08f)
            .toInt().coerceAtLeast(2)
        val left = (pointX - regionSize / 2)
            .coerceIn(crop.left, (crop.right - regionSize).coerceAtLeast(crop.left))
        val top = (pointY - regionSize / 2)
            .coerceIn(crop.top, (crop.bottom - regionSize).coerceAtLeast(crop.top))
        focusMeteringRegion = MeteringRectangle(
            Rect(left, top, left + regionSize, top + regionSize),
            MeteringRectangle.METERING_WEIGHT_MAX
        )
        portraitFocusX = displayX
        portraitFocusY = displayY
        if (activeMode == "Portrait") {
            val selectedPoint = displayX to displayY
            portraitFocusPoints = if (addPortraitSubject && portraitHasUserSelection) {
                (portraitFocusPoints + selectedPoint).distinct().take(5)
            } else {
                listOf(selectedPoint)
            }
            portraitHasUserSelection = true
            if (addPortraitSubject) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Additional subject selected",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        showFocusIndicator(viewX, viewY)

        val session = captureSession ?: return
        val cancel = createPreviewRequest(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL) ?: return
        val canTriggerAf = currentAfMode() != CaptureRequest.CONTROL_AF_MODE_OFF &&
            (characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0
        if (!canTriggerAf) {
            startPreviewRepeating()
            return
        }
        try {
            session.capture(cancel.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: android.hardware.camera2.TotalCaptureResult
                ) {
                    if (captureSession !== session) return
                    val start = createPreviewRequest(CaptureRequest.CONTROL_AF_TRIGGER_START)
                        ?: return
                    try {
                        session.capture(start.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: android.hardware.camera2.TotalCaptureResult
                            ) {
                                if (captureSession === session) startPreviewRepeating()
                            }
                        }, cameraHandler)
                    } catch (_: CameraAccessException) {
                        startPreviewRepeating()
                    }
                }
            }, cameraHandler)
        } catch (_: CameraAccessException) {
            startPreviewRepeating()
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

    private fun saveJpeg(
        bytes: ByteArray,
        showSavedToast: Boolean = true
    ): Uri? {
        val storage = selectedCameraStorageLocation()
        if (storage == null) {
            runOnUiThread {
                Toast.makeText(
                    this,
                    "The selected storage is unavailable. Insert the SD card or choose Internal storage in Settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return null
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(storage.volumeName)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Camera_$timestamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        var savedUri: Uri? = null
        try {
            val uri = contentResolver.insert(collection, values)
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
            runOnUiThread {
                showGalleryThumbnail(uri)
                if (showSavedToast) {
                    Toast.makeText(
                        this,
                        "Photo saved to ${storage.label}/DCIM/Camera",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            return uri
        } catch (e: Throwable) {
            savedUri?.let { contentResolver.delete(it, null, null) }
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Couldn't save photo: ${e.message ?: e.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            }
            return null
        }
    }

    private fun replaceSavedJpeg(uri: Uri, bytes: ByteArray) {
        contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: throw IllegalStateException("Unable to reopen the saved photo for Portrait processing")
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

    private inner class CaptureLoadingIndicator(context: Context) : View(context) {
        private val indicatorPaint = android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG
        )
        private var rotationDegrees = 0f
        private var rotationAnimator: android.animation.ValueAnimator? = null

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            rotationAnimator?.cancel()
            rotationAnimator = android.animation.ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 1_050L
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { animation ->
                    rotationDegrees = animation.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDetachedFromWindow() {
            rotationAnimator?.cancel()
            rotationAnimator = null
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val centerX = width / 2f
            val centerY = height / 2f
            val radius = dp(18).toFloat()
            val segmentWidth = dp(4).toFloat()
            val segmentHeight = dp(6).toFloat()

            // Eight rotating focus marks create a camera-inspired loading cycle.
            for (index in 0 until 8) {
                canvas.save()
                canvas.rotate(rotationDegrees - index * 45f, centerX, centerY)
                indicatorPaint.style = android.graphics.Paint.Style.FILL
                indicatorPaint.color = if (index == 0) 0xFFFFD54F.toInt() else Color.WHITE
                indicatorPaint.alpha = (255 - index * 27).coerceAtLeast(58)
                val mark = android.graphics.RectF(
                    centerX - segmentWidth / 2f,
                    centerY - radius - segmentHeight / 2f,
                    centerX + segmentWidth / 2f,
                    centerY - radius + segmentHeight / 2f
                )
                canvas.drawRoundRect(
                    mark,
                    segmentWidth / 2f,
                    segmentWidth / 2f,
                    indicatorPaint
                )
                canvas.restore()
            }

            indicatorPaint.style = android.graphics.Paint.Style.STROKE
            indicatorPaint.strokeWidth = dp(1).toFloat()
            indicatorPaint.color = Color.WHITE
            indicatorPaint.alpha = 70
            canvas.drawCircle(centerX, centerY, dp(8).toFloat(), indicatorPaint)

            indicatorPaint.style = android.graphics.Paint.Style.FILL
            indicatorPaint.color = 0xFFFFD54F.toInt()
            indicatorPaint.alpha = 230
            canvas.drawCircle(centerX, centerY, dp(2).toFloat(), indicatorPaint)
        }
    }

    private inner class CameraIconButton(
        context: Context,
        private val icon: String,
        private val iconColorOverride: Int? = null,
        private val onPressed: () -> Unit
    ) : View(context) {

        private val strokePaint = android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG
        ).apply {
            color = Color.WHITE
            strokeWidth = 1.65f
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            style = android.graphics.Paint.Style.STROKE
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
            val isRatioChoice = icon.startsWith("ratio_")
            val size = dp(if (isRatioChoice) 19 else if (icon.startsWith("mode:")) 27 else 23).toFloat()
            canvas.save()
            canvas.translate(
                (width - size) / 2f,
                if (isRatioChoice) dp(3f).toFloat() else (height - size) / 2f
            )
            canvas.scale(size / 24f, size / 24f)

            val yellow = 0xFFFFD54F.toInt()
            val highlighted = when (icon) {
                "flash" -> flashMode == 1
                "flashOff" -> flashMode == 2
                "flashAuto" -> flashMode == 0
                "flashOn" -> flashMode == 1
                "timer" -> timerSeconds != 0
                "timerOff" -> timerSeconds == 0
                "timer2" -> timerSeconds == 2
                "timer5" -> timerSeconds == 5
                "timer10" -> timerSeconds == 10
                "ratio_3_4" -> aspectRatio == "3:4"
                "ratio_9_16" -> aspectRatio == "9:16"
                "ratio_1_1" -> aspectRatio == "1:1"
                "ratio_full" -> aspectRatio == "Full"
                else -> false
            }
            strokePaint.color = iconColorOverride ?: if (highlighted) yellow else Color.WHITE
            strokePaint.strokeWidth = 1.55f

            if (icon.startsWith("mode:")) {
                drawModeIcon(canvas, icon.removePrefix("mode:"))
            } else when (icon) {
                "settings" -> drawSettings(canvas)
                "flash" -> drawFlash(canvas, flashMode)
                "flashOff" -> drawFlashChoice(canvas, 2)
                "flashAuto" -> drawFlashChoice(canvas, 0)
                "flashOn" -> drawFlashChoice(canvas, 1)
                "timer" -> drawTimer(canvas, timerSeconds)
                "timerOff" -> drawTimer(canvas, 0)
                "timer2" -> drawTimer(canvas, 2)
                "timer5" -> drawTimer(canvas, 5)
                "timer10" -> drawTimer(canvas, 10)
                "ratio" -> drawRatio(canvas)
                "ratio_3_4" -> drawRatioChoice(canvas, "3:4")
                "ratio_9_16" -> drawRatioChoice(canvas, "9:16")
                "ratio_1_1" -> drawRatioChoice(canvas, "1:1")
                "ratio_full" -> drawRatioChoice(canvas, "Full")
                "motion" -> drawMotion(canvas)
                "filters" -> drawFilters(canvas)
                "gallery" -> drawGallery(canvas)
                "flip" -> drawFlip(canvas)
            }
            canvas.restore()
            if (icon.startsWith("ratio_")) {
                val ratioLabel = when (icon) {
                    "ratio_9_16" -> "9:16"
                    "ratio_1_1" -> "1:1"
                    "ratio_full" -> "Full"
                    else -> "3:4"
                }
                val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (isHighlightedRatio()) 0xFFFFD54F.toInt() else Color.WHITE
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = dp(10f).toFloat()
                    typeface = android.graphics.Typeface.create(
                        "sans-serif-medium",
                        android.graphics.Typeface.NORMAL
                    )
                }
                canvas.drawText(
                    ratioLabel,
                    width / 2f,
                    (height - dp(12f)).toFloat(),
                    labelPaint
                )
            }
        }

        private fun isHighlightedRatio(): Boolean {
            return when (icon) {
                "ratio_9_16" -> aspectRatio == "9:16"
                "ratio_1_1" -> aspectRatio == "1:1"
                "ratio_full" -> aspectRatio == "Full"
                else -> aspectRatio == "3:4"
            }
        }

        private fun drawSettings(canvas: android.graphics.Canvas) {
            val path = android.graphics.Path()
            for (i in 0 until 32) {
                val angle = Math.toRadians(-90.0 + i * 11.25)
                val radius = if (i % 4 == 1 || i % 4 == 2) 10.3f else 8.5f
                val x = 12f + kotlin.math.cos(angle).toFloat() * radius
                val y = 12f + kotlin.math.sin(angle).toFloat() * radius
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            canvas.drawPath(path, strokePaint)
            canvas.drawCircle(12f, 12f, 3.6f, strokePaint)
        }

        private fun drawBolt(canvas: android.graphics.Canvas, mode: Int) {
            val bolt = android.graphics.Path().apply {
                moveTo(13.5f, 1.8f)
                lineTo(5.6f, 13.2f)
                lineTo(10.2f, 13.2f)
                lineTo(9.5f, 22f)
                lineTo(18.4f, 9.4f)
                lineTo(13.6f, 9.4f)
                close()
            }
            canvas.drawPath(bolt, strokePaint)
            if (mode == 2) {
                canvas.drawLine(3.2f, 21f, 20.5f, 3f, strokePaint)
            } else if (mode == 0) {
                val a = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = strokePaint.color
                    textSize = 8f
                    typeface = android.graphics.Typeface.create(
                        "sans-serif-medium",
                        android.graphics.Typeface.NORMAL
                    )
                }
                canvas.drawText("A", 15.2f, 21f, a)
            }
        }

        private fun drawFlash(canvas: android.graphics.Canvas, mode: Int) {
            drawBolt(canvas, mode)
        }

        private fun drawFlashChoice(canvas: android.graphics.Canvas, mode: Int) {
            drawBolt(canvas, mode)
        }

        private fun drawTimer(canvas: android.graphics.Canvas, seconds: Int) {
            val face = android.graphics.RectF(2.5f, 2.5f, 20.5f, 20.5f)
            if (seconds == 0) {
                canvas.drawCircle(11.5f, 11.5f, 8.6f, strokePaint)
            } else {
                // Leave a 90-degree opening centred at 225 degrees; the seconds
                // label is drawn inside that opening instead of over the clock rim.
                canvas.drawArc(face, 270f, 270f, false, strokePaint)
            }
            canvas.drawLine(11.5f, 11.5f, 11.5f, 5.8f, strokePaint)
            canvas.drawLine(11.5f, 11.5f, 15.2f, 13.6f, strokePaint)

            if (seconds > 0) {
                val numberPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = strokePaint.color
                    textSize = if (seconds == 10) 6.1f else 7.8f
                    typeface = android.graphics.Typeface.create(
                        "sans-serif-medium",
                        android.graphics.Typeface.NORMAL
                    )
                    textAlign = android.graphics.Paint.Align.CENTER
                    style = android.graphics.Paint.Style.FILL
                }
                canvas.drawText(
                    seconds.toString(),
                    if (seconds == 10) 4.3f else 5.0f,
                    7.4f,
                    numberPaint
                )
            }
        }

        private fun drawRatio(canvas: android.graphics.Canvas) {
            val p = strokePaint
            // Samsung's open-corner crop frame with the ratio label in the centre.
            val corners = android.graphics.Path().apply {
                moveTo(8f, 2.7f)
                lineTo(5.3f, 2.7f)
                quadTo(2.5f, 2.7f, 2.5f, 5.5f)
                lineTo(2.5f, 8f)

                moveTo(16f, 2.7f)
                lineTo(18.7f, 2.7f)
                quadTo(21.5f, 2.7f, 21.5f, 5.5f)
                lineTo(21.5f, 8f)

                moveTo(2.5f, 16f)
                lineTo(2.5f, 18.5f)
                quadTo(2.5f, 21.3f, 5.3f, 21.3f)
                lineTo(8f, 21.3f)

                moveTo(16f, 21.3f)
                lineTo(18.7f, 21.3f)
                quadTo(21.5f, 21.3f, 21.5f, 18.5f)
                lineTo(21.5f, 16f)
            }
            canvas.drawPath(corners, p)
            val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = p.color
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = if (aspectRatio == "Full") 6.3f else 7.6f
                typeface = android.graphics.Typeface.create(
                    "sans-serif-medium",
                    android.graphics.Typeface.NORMAL
                )
                style = android.graphics.Paint.Style.FILL
            }
            canvas.drawText(
                if (aspectRatio == "Full") "FULL" else aspectRatio,
                12f,
                14.9f,
                textPaint
            )
        }

        private fun drawRatioChoice(canvas: android.graphics.Canvas, ratio: String) {
            val p = strokePaint
            val rect = when (ratio) {
                "3:4" -> android.graphics.RectF(5f, 2.5f, 19f, 21.5f)
                "9:16" -> android.graphics.RectF(7.3f, 2f, 16.7f, 22f)
                "1:1" -> android.graphics.RectF(3f, 3f, 21f, 21f)
                else -> android.graphics.RectF(2f, 5f, 22f, 19f)
            }
            canvas.drawRoundRect(rect, 1.8f, 1.8f, p)
        }

        private fun drawMotion(canvas: android.graphics.Canvas) {
            val p = strokePaint
            // Rounded image frame and small play arrow projecting from its right edge.
            val frame = android.graphics.Path().apply {
                moveTo(5.5f, 3.5f)
                lineTo(15.5f, 3.5f)
                quadTo(18f, 3.5f, 18f, 6f)
                lineTo(18f, 18f)
                quadTo(18f, 20.5f, 15.5f, 20.5f)
                lineTo(5.5f, 20.5f)
                quadTo(3f, 20.5f, 3f, 18f)
                lineTo(3f, 6f)
                quadTo(3f, 3.5f, 5.5f, 3.5f)
                close()
            }
            canvas.drawPath(frame, p)
            val play = android.graphics.Path().apply {
                moveTo(15.3f, 8f)
                lineTo(22f, 12f)
                lineTo(15.3f, 16f)
                close()
            }
            val playPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = p.color
                style = android.graphics.Paint.Style.FILL
            }
            canvas.drawPath(play, playPaint)
        }

        private fun drawFilters(canvas: android.graphics.Canvas) {
            val p = strokePaint
            // Samsung's magic-wand silhouette with three crisp sparkle rays.
            canvas.drawLine(5.2f, 18.7f, 17.5f, 6.3f, p)
            canvas.drawLine(4f, 20f, 6.4f, 17.6f, p)
            canvas.drawLine(14.8f, 2.2f, 14.8f, 6.5f, p)
            canvas.drawLine(12.7f, 4.35f, 17f, 4.35f, p)
            canvas.drawLine(4.5f, 3.3f, 4.5f, 7.5f, p)
            canvas.drawLine(2.4f, 5.4f, 6.6f, 5.4f, p)
            canvas.drawLine(20f, 14.4f, 20f, 18.5f, p)
            canvas.drawLine(17.9f, 16.45f, 22.1f, 16.45f, p)
        }

        private fun drawFlip(canvas: android.graphics.Canvas) {
            val p = strokePaint
            // Two opposing curved arrows kept symmetric around the icon centre.
            canvas.drawArc(
                android.graphics.RectF(3.2f, 3.2f, 20.8f, 20.8f),
                205f,
                145f,
                false,
                p
            )
            val topArrow = android.graphics.Path().apply {
                moveTo(3.8f, 6.5f)
                lineTo(3.5f, 2.8f)
                lineTo(7.2f, 4.1f)
            }
            canvas.drawPath(topArrow, p)
            canvas.drawArc(
                android.graphics.RectF(3.2f, 3.2f, 20.8f, 20.8f),
                25f,
                145f,
                false,
                p
            )
            val bottomArrow = android.graphics.Path().apply {
                moveTo(20.2f, 17.5f)
                lineTo(20.5f, 21.2f)
                lineTo(16.8f, 19.9f)
            }
            canvas.drawPath(bottomArrow, p)
        }

        private fun drawModeIcon(canvas: android.graphics.Canvas, mode: String) {
            val p = strokePaint.apply {
                strokeWidth = 1.35f
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            when (mode) {
                "Night" -> {
                    // A clean filled crescent keeps the silhouette balanced at small sizes.
                    val moon = android.graphics.Path().apply {
                        moveTo(18.5f, 3.8f)
                        cubicTo(14.2f, 5.7f, 12.3f, 9.2f, 12.6f, 12.4f)
                        cubicTo(12.9f, 16.5f, 16.0f, 19.3f, 20.0f, 19.0f)
                        cubicTo(17.9f, 21.0f, 15.0f, 22.0f, 12.0f, 21.7f)
                        cubicTo(5.8f, 21.2f, 2.0f, 16.2f, 2.6f, 10.3f)
                        cubicTo(3.2f, 4.0f, 9.0f, 0.5f, 15.0f, 2.0f)
                        cubicTo(16.5f, 2.3f, 17.7f, 2.9f, 18.5f, 3.8f)
                        close()
                    }
                    val moonPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = p.color
                        style = android.graphics.Paint.Style.FILL
                    }
                    canvas.drawPath(moon, moonPaint)
                }
                "Single Take" -> {
                    canvas.drawCircle(12f, 12f, 8.8f, p)
                    canvas.drawCircle(12f, 12f, 3.2f, p)
                    canvas.drawCircle(12f, 12f, 0.8f, p)
                }
                "Food" -> {
                    canvas.drawCircle(12f, 12f, 8.8f, p)
                    canvas.drawLine(9f, 7f, 9f, 17f, p)
                    canvas.drawLine(7f, 7f, 7f, 10f, p)
                    canvas.drawLine(11f, 7f, 11f, 10f, p)
                    canvas.drawLine(7f, 10f, 11f, 10f, p)
                    canvas.drawLine(16f, 7f, 16f, 17f, p)
                    canvas.drawArc(android.graphics.RectF(14f, 6.5f, 18f, 11f), 180f, 180f, false, p)
                }
                "Panorama" -> {
                    // Curved wrap-around frame with a mountain range inside.
                    val wrapFrame = android.graphics.Path().apply {
                        moveTo(2.5f, 8.2f)
                        cubicTo(7.0f, 4.2f, 17.0f, 4.2f, 21.5f, 8.2f)
                        lineTo(21.5f, 15.8f)
                        cubicTo(17.0f, 19.8f, 7.0f, 19.8f, 2.5f, 15.8f)
                        close()
                    }
                    canvas.drawPath(wrapFrame, p)
                    val mountain = android.graphics.Path().apply {
                        moveTo(5.0f, 15.2f)
                        lineTo(9.0f, 10.7f)
                        lineTo(11.7f, 13.2f)
                        lineTo(15.0f, 9.5f)
                        lineTo(19.0f, 15.2f)
                    }
                    canvas.drawPath(mountain, p)
                }
                "Super Slow-Mo" -> {
                    // Three ball-like frames, fading away to show progressively slower motion.
                    val ballPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style = android.graphics.Paint.Style.FILL
                    }
                    val base = p.color
                    fun drawBall(x: Float, opacity: Int) {
                        ballPaint.color = android.graphics.Color.argb(
                            android.graphics.Color.alpha(base) * opacity / 255,
                            android.graphics.Color.red(base),
                            android.graphics.Color.green(base),
                            android.graphics.Color.blue(base)
                        )
                        canvas.drawCircle(x, 12f, 3.6f, ballPaint)
                    }
                    drawBall(5.5f, 255)
                    drawBall(12f, 150)
                    drawBall(18.5f, 72)
                }
                "Slow-Mo" -> {
                    // Two adjacent balls; the trailing one is dimmer to imply motion.
                    val ballPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style = android.graphics.Paint.Style.FILL
                    }
                    val base = p.color
                    fun drawBall(x: Float, opacity: Int) {
                        ballPaint.color = android.graphics.Color.argb(
                            android.graphics.Color.alpha(base) * opacity / 255,
                            android.graphics.Color.red(base),
                            android.graphics.Color.green(base),
                            android.graphics.Color.blue(base)
                        )
                        canvas.drawCircle(x, 12f, 4.2f, ballPaint)
                    }
                    drawBall(8f, 255)
                    drawBall(16.5f, 95)
                }
                "Hyperlapse" -> {
                    // Faded streaks trail to the left of a prominent moving circle.
                    val streakPaint = android.graphics.Paint(p).apply {
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 1.5f
                        strokeCap = android.graphics.Paint.Cap.ROUND
                    }
                    val base = p.color
                    fun drawStreak(y: Float, endX: Float, opacity: Int) {
                        streakPaint.color = android.graphics.Color.argb(
                            android.graphics.Color.alpha(base) * opacity / 255,
                            android.graphics.Color.red(base),
                            android.graphics.Color.green(base),
                            android.graphics.Color.blue(base)
                        )
                        canvas.drawLine(2.5f, y, endX, y, streakPaint)
                    }
                    drawStreak(8f, 8.2f, 65)
                    drawStreak(12f, 10.0f, 105)
                    drawStreak(16f, 8.2f, 65)
                    canvas.drawCircle(17.2f, 12f, 4.4f, p)
                }
                "Portrait Video" -> {
                    canvas.drawCircle(10f, 7.5f, 3f, p)
                    canvas.drawArc(android.graphics.RectF(3f, 11f, 17f, 24f), 195f, 150f, false, p)
                    canvas.drawCircle(19f, 15f, 3.2f, p)
                }
                "Portrait" -> {
                    canvas.drawCircle(12f, 7.5f, 3f, p)
                    canvas.drawArc(android.graphics.RectF(4f, 11f, 20f, 24f), 195f, 150f, false, p)
                }
                "Photo" -> {
                    canvas.drawRoundRect(android.graphics.RectF(3f, 6f, 21f, 19f), 2f, 2f, p)
                    canvas.drawCircle(12f, 12.5f, 4f, p)
                }
                "Video" -> {
                    canvas.drawRoundRect(android.graphics.RectF(2.5f, 6f, 16.5f, 18f), 2f, 2f, p)
                    val lens = android.graphics.Path().apply {
                        moveTo(16.5f, 9f)
                        lineTo(22f, 6.5f)
                        lineTo(22f, 17.5f)
                        lineTo(16.5f, 15f)
                        close()
                    }
                    canvas.drawPath(lens, p)
                }
                else -> {
                    canvas.drawCircle(12f, 12f, 8.5f, p)
                    canvas.drawCircle(12f, 12f, 2f, p)
                }
            }
        }

        private fun drawGallery(canvas: android.graphics.Canvas) {
            val p = strokePaint
            canvas.drawRoundRect(android.graphics.RectF(2.5f, 4f, 21.5f, 20f), 2f, 2f, p)
            canvas.drawCircle(8f, 9f, 1.8f, p)
            val mountain = android.graphics.Path().apply {
                moveTo(4f, 17.8f)
                lineTo(9.3f, 11.5f)
                lineTo(12.6f, 15f)
                lineTo(15.5f, 11f)
                lineTo(20f, 17.8f)
            }
            canvas.drawPath(mountain, p)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
}
