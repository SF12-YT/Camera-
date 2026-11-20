package com.sf12.camera

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Build
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor

/**
 * Produces an on-device Camera2 hardware and stream report.
 *
 * The session probes use the active camera's actual preview/JPEG surfaces, then
 * temporarily add YUV/depth ImageReader surfaces. They ask Camera2 whether the
 * combination is supported; they do not create or reconfigure a capture session.
 */
internal class CameraCapabilityAuditor(
    private val cameraManager: CameraManager
) {
    private data class ProbeResult(
        val supported: Boolean?,
        val detail: String? = null
    )

    private val directExecutor = Executor { command -> command.run() }

    fun createReport(
        activeCameraId: String,
        activeDevice: CameraDevice,
        previewSurface: Surface?,
        jpegSurface: Surface?,
        previewSize: Size?,
        jpegSize: Size?
    ): String {
        val lines = mutableListOf<String>()
        lines += "CAMERA2 CAPABILITY REPORT"
        lines += "Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
        lines += "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        lines += "Active camera ID: $activeCameraId"
        lines += "Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}"
        lines += ""

        val ids = try {
            cameraManager.cameraIdList.toList()
        } catch (error: Exception) {
            lines += "Unable to enumerate camera IDs: ${error.message ?: error.javaClass.simpleName}"
            return lines.joinToString("\n")
        }
        val rearIds = ids.filter { id ->
            try {
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } catch (_: Exception) {
                false
            }
        }
        lines += "Camera2 IDs: ${ids.joinToString().ifBlank { "none" }}"
        lines += "Rear-facing IDs: ${rearIds.joinToString().ifBlank { "none" }}"
        lines += "Rear camera count exposed by Camera2: ${rearIds.size}"

        val concurrentSets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                cameraManager.concurrentCameraIds
                    .map { it.sorted().joinToString(" + ") }
                    .sorted()
            } catch (error: Exception) {
                listOf("Query failed: ${error.message ?: error.javaClass.simpleName}")
            }
        } else {
            emptyList()
        }
        lines += if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "Concurrent camera combinations: ${concurrentSets.joinToString(" | ").ifBlank { "none reported" }}"
        } else {
            "Concurrent camera combinations: unavailable before Android 11"
        }
        lines += ""

        ids.forEach { id ->
            lines += "CAMERA $id${if (id == activeCameraId) " (ACTIVE)" else ""}"
            try {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                    else -> "UNKNOWN"
                }
                val hardware = hardwareLevelName(
                    characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                )
                val capabilities = characteristics.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
                ) ?: intArrayOf()
                val streamMap = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                )
                val jpegSizes = outputSizes(streamMap, ImageFormat.JPEG)
                val previewSizes = try {
                    streamMap?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
                } catch (_: Exception) {
                    emptyList()
                }
                val yuvSizes = outputSizes(streamMap, ImageFormat.YUV_420_888)
                val depthSizes = outputSizes(streamMap, ImageFormat.DEPTH16)
                val depthPointCloudSizes = outputSizes(streamMap, ImageFormat.DEPTH_POINT_CLOUD)
                val rawSizes = outputSizes(streamMap, ImageFormat.RAW_SENSOR)
                val physicalIds = if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                ) {
                    try {
                        characteristics.physicalCameraIds.toList().sorted()
                    } catch (_: Exception) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }

                lines += "  Facing: $facing"
                lines += "  Hardware level: $hardware"
                lines += "  Capabilities: ${capabilities.map(::capabilityName).joinToString().ifBlank { "none reported" }}"
                lines += "  Logical multi-camera: ${capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)}"
                lines += "  Physical camera IDs: ${physicalIds.joinToString().ifBlank { "none exposed" }}"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                ) {
                    val sync = characteristics.get(
                        CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE
                    )
                    lines += "  Multi-camera sensor sync: ${when (sync) {
                        CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_CALIBRATED -> "CALIBRATED"
                        CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_APPROXIMATE -> "APPROXIMATE"
                        else -> "UNKNOWN"
                    }}"
                    physicalIds.forEach { physicalId ->
                        val physical = try {
                            cameraManager.getCameraCharacteristics(physicalId)
                        } catch (_: Exception) {
                            null
                        }
                        if (physical != null) {
                            val focal = physical.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                                ?.joinToString { "${"%.2f".format(java.util.Locale.US, it)}mm" }
                            val poseRotation = physical.get(CameraCharacteristics.LENS_POSE_ROTATION)?.joinToString()
                            val poseTranslation = physical.get(CameraCharacteristics.LENS_POSE_TRANSLATION)?.joinToString()
                            val intrinsics = physical.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.joinToString()
                            val distortion = physical.get(CameraCharacteristics.LENS_DISTORTION)?.joinToString()
                            val timestampSource = physical.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
                            val physicalMap = physical.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                            val physicalYuvSizes = outputSizes(physicalMap, ImageFormat.YUV_420_888)
                            val physicalSensorSize = physical.get(
                                CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
                            )
                            val physicalEquivalentFocal = physical.get(
                                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                            )?.maxOrNull()?.let { focalLength ->
                                if (physicalSensorSize != null && physicalSensorSize.width > 0f) {
                                    focalLength * 36f / physicalSensorSize.width
                                } else {
                                    null
                                }
                            }
                            lines += "    Physical $physicalId: focal lengths=${focal ?: "unknown"}, 35mm-equivalent=${physicalEquivalentFocal?.let { "${"%.2f".format(java.util.Locale.US, it)}mm" } ?: "not calculable"}, sensor size=${physicalSensorSize ?: "unknown"}, pixel array=${physical.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: "unknown"}"
                            lines += "      YUV_420_888 outputs: ${sizesSummary(physicalYuvSizes)}"
                            lines += "      Lens pose rotation=${poseRotation ?: "unavailable"}, translation=${poseTranslation ?: "unavailable"}"
                            lines += "      Intrinsics=${intrinsics ?: "unavailable"}, distortion=${distortion ?: "unavailable"}, timestamp source=${timestampSource ?: "unknown"}"
                        }
                    }
                }
                lines += "  Sensor timestamp source: ${characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ?: "unknown"}"
                lines += "  Sensor orientation: ${characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: "unknown"}°"
                lines += "  Sensor pixel array: ${characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: "unknown"}"
                lines += "  Active array: ${characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.toShortString() ?: "unknown"}"
                val focalLengths = characteristics.get(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                )
                val physicalSensorSize = characteristics.get(
                    CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
                )
                val equivalentFocal = focalLengths?.maxOrNull()?.let { focal ->
                    if (physicalSensorSize != null && physicalSensorSize.width > 0f) {
                        focal * 36f / physicalSensorSize.width
                    } else {
                        null
                    }
                }
                val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                } else {
                    null
                }
                lines += "  Sensor physical size: ${physicalSensorSize ?: "not reported"}"
                lines += "  Focal lengths: ${focalLengths?.joinToString { "${"%.2f".format(java.util.Locale.US, it)}mm" } ?: "unknown"}"
                lines += "  Approx. 35mm-equivalent focal length: ${equivalentFocal?.let { "${"%.2f".format(java.util.Locale.US, it)}mm" } ?: "not calculable"}"
                lines += "  CONTROL_ZOOM_RATIO_RANGE: ${zoomRange?.let { "${it.lower}× to ${it.upper}×" } ?: "not exposed"}"
                lines += "  Minimum focus distance: ${characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: "unknown"} dioptres"
                lines += "  AF modes: ${characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.map(::afModeName)?.joinToString().orEmpty().ifBlank { "none reported" }}"
                lines += "  AF / AE metering regions: ${characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0} / ${characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0}"
                lines += "  Flash: ${characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true}"
                lines += "  JPEG outputs: ${sizesSummary(jpegSizes)}"
                lines += "  Preview (SurfaceTexture) outputs: ${sizesSummary(previewSizes)}"
                lines += "  YUV_420_888 outputs: ${sizesSummary(yuvSizes)}"
                lines += "  DEPTH16 outputs: ${sizesSummary(depthSizes)}"
                lines += "  DEPTH_POINT_CLOUD outputs: ${sizesSummary(depthPointCloudSizes)}"
                lines += "  DEPTH_OUTPUT capability: ${capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)}"
                lines += "  Depth output exclusive: ${characteristics.get(CameraCharacteristics.DEPTH_DEPTH_IS_EXCLUSIVE) ?: "unknown"}"
                lines += "  RAW_SENSOR outputs: ${sizesSummary(rawSizes)}"
                val fpsRanges = try {
                    streamMap?.highSpeedVideoFpsRanges
                        ?.map { "${it.lower}-${it.upper}fps" }
                        ?.distinct()
                        .orEmpty()
                } catch (_: Exception) {
                    emptyList()
                }
                lines += "  Constrained high-speed ranges: ${fpsRanges.joinToString().ifBlank { "none reported" }}"
            } catch (error: Exception) {
                lines += "  Camera query failed: ${error.message ?: error.javaClass.simpleName}"
            }
            lines += ""
        }

        lines += "ACTIVE CAMERA STREAM COMBINATION TESTS"
        lines += "Selected app preview size: ${previewSize ?: "unknown"}"
        lines += "Selected app JPEG size: ${jpegSize ?: "unknown"}"
        val baseSurfaces = listOfNotNull(previewSurface, jpegSurface)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            lines += "Session combination probing requires Android 10 or newer."
        } else if (baseSurfaces.size != 2) {
            lines += "Session probes unavailable: active preview/JPEG surfaces are not ready."
        } else {
            lines += probeLine(
                "Preview + JPEG (current app configuration)",
                probeSession(activeDevice, baseSurfaces)
            )
            val activeCharacteristics = try {
                cameraManager.getCameraCharacteristics(activeCameraId)
            } catch (_: Exception) {
                null
            }
            val activeMap = activeCharacteristics?.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )
            val yuvSizes = outputSizes(activeMap, ImageFormat.YUV_420_888)
            val depthSizes = outputSizes(activeMap, ImageFormat.DEPTH16)
            val yuvProbeSize = pickProbeSize(yuvSizes, 1920, 1080)
            val depthProbeSize = pickProbeSize(depthSizes, 640, 480)

            if (yuvProbeSize != null) {
                lines += probeLine(
                    "Preview + JPEG + YUV_420_888 (${yuvProbeSize})",
                    probeSession(activeDevice, baseSurfaces, yuvSize = yuvProbeSize)
                )
            } else {
                lines += "Preview + JPEG + YUV_420_888: NOT TESTED (no YUV output size advertised)"
            }
            if (depthProbeSize != null) {
                lines += probeLine(
                    "Preview + JPEG + DEPTH16 (${depthProbeSize})",
                    probeSession(activeDevice, baseSurfaces, depthSize = depthProbeSize)
                )
            } else {
                lines += "Preview + JPEG + DEPTH16: NOT TESTED (no DEPTH16 output size advertised)"
            }
            if (yuvProbeSize != null && depthProbeSize != null) {
                lines += probeLine(
                    "Preview + JPEG + YUV + DEPTH16",
                    probeSession(
                        activeDevice,
                        baseSurfaces,
                        yuvSize = yuvProbeSize,
                        depthSize = depthProbeSize
                    )
                )
            } else {
                lines += "Preview + JPEG + YUV + DEPTH16: NOT TESTED (one or both outputs unavailable)"
            }

            val logicalCapabilities = activeCharacteristics?.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            ) ?: intArrayOf()
            val physicalIds = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                logicalCapabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            ) {
                try {
                    activeCharacteristics?.physicalCameraIds?.toList().orEmpty()
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                lines += "Two-physical-camera stream test: NOT TESTED (requires Android 9+)"
            } else if (physicalIds.size < 2 || activeCharacteristics == null) {
                lines += "Two-physical-camera stream test: NOT TESTED (active camera does not expose a logical physical-camera pair)"
            } else {
                val candidates = physicalIds.mapNotNull { physicalId ->
                    try {
                        val physical = cameraManager.getCameraCharacteristics(physicalId)
                        val physicalMap = physical.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        val sizes = outputSizes(physicalMap, ImageFormat.YUV_420_888)
                        val size = pickProbeSize(sizes, 1280, 720) ?: return@mapNotNull null
                        val focal = physical.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            ?.minOrNull() ?: Float.NaN
                        Triple(physicalId, size, focal)
                    } catch (_: Exception) {
                        null
                    }
                }.sortedBy { if (it.third.isNaN()) Float.MAX_VALUE else it.third }

                if (candidates.size >= 2) {
                    val first = candidates.first()
                    val second = candidates.last()
                    lines += "Physical YUV streams selected: ${first.first} @ ${first.second} + ${second.first} @ ${second.second}"
                    lines += probeLine(
                        "Preview + JPEG + two physical YUV streams",
                        probePhysicalPairSession(
                            activeDevice,
                            baseSurfaces,
                            first.first,
                            first.second,
                            second.first,
                            second.second
                        )
                    )
                } else {
                    lines += "Two-physical-camera stream test: NOT TESTED (fewer than two physical cameras expose YUV_420_888 outputs)"
                }
            }
        }

        lines += ""
        lines += "Interpretation:"
        lines += "- Output size lists describe individual stream support."
        lines += "- The tests above query whether those particular streams can run together."
        lines += "- Concurrent camera ID sets describe cameras Android says may be opened simultaneously."
        lines += "- Physical camera IDs do not by themselves guarantee calibrated stereo depth."
        lines += "- If DEPTH_DEPTH_IS_EXCLUSIVE is true, depth and color must be targeted by separate capture requests."
        lines += "- A missing DEPTH16 stream means Portrait must use software-estimated depth/segmentation."
        return lines.joinToString("\n")
    }

    private fun probeSession(
        device: CameraDevice,
        baseSurfaces: List<Surface>,
        yuvSize: Size? = null,
        depthSize: Size? = null
    ): ProbeResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ProbeResult(null, "requires Android 10+")
        }
        val readers = mutableListOf<ImageReader>()
        return try {
            val extraSurfaces = mutableListOf<Surface>()
            if (yuvSize != null) {
                val reader = ImageReader.newInstance(
                    yuvSize.width,
                    yuvSize.height,
                    ImageFormat.YUV_420_888,
                    1
                )
                readers += reader
                extraSurfaces += reader.surface
            }
            if (depthSize != null) {
                val reader = ImageReader.newInstance(
                    depthSize.width,
                    depthSize.height,
                    ImageFormat.DEPTH16,
                    1
                )
                readers += reader
                extraSurfaces += reader.surface
            }
            val outputs = (baseSurfaces + extraSurfaces).map(::OutputConfiguration)
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                directExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) = session.close()
                    override fun onConfigureFailed(session: CameraCaptureSession) = session.close()
                }
            )
            ProbeResult(device.isSessionConfigurationSupported(sessionConfig))
        } catch (error: Exception) {
            ProbeResult(null, error.message ?: error.javaClass.simpleName)
        } finally {
            readers.forEach {
                try {
                    it.close()
                } catch (_: Exception) {
                    // Diagnostic cleanup only.
                }
            }
        }
    }

    private fun probePhysicalPairSession(
        device: CameraDevice,
        baseSurfaces: List<Surface>,
        firstCameraId: String,
        firstSize: Size,
        secondCameraId: String,
        secondSize: Size
    ): ProbeResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return ProbeResult(null, "requires Android 9+")
        }
        val readers = mutableListOf<ImageReader>()
        return try {
            val firstReader = ImageReader.newInstance(
                firstSize.width,
                firstSize.height,
                ImageFormat.YUV_420_888,
                1
            )
            readers += firstReader
            val secondReader = ImageReader.newInstance(
                secondSize.width,
                secondSize.height,
                ImageFormat.YUV_420_888,
                1
            )
            readers += secondReader

            val firstOutput = OutputConfiguration(firstReader.surface).apply {
                setPhysicalCameraId(firstCameraId)
            }
            val secondOutput = OutputConfiguration(secondReader.surface).apply {
                setPhysicalCameraId(secondCameraId)
            }
            val outputs = baseSurfaces.map(::OutputConfiguration) +
                listOf(firstOutput, secondOutput)
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                directExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) = session.close()
                    override fun onConfigureFailed(session: CameraCaptureSession) = session.close()
                }
            )
            ProbeResult(device.isSessionConfigurationSupported(sessionConfig))
        } catch (error: Exception) {
            ProbeResult(null, error.message ?: error.javaClass.simpleName)
        } finally {
            readers.forEach {
                try {
                    it.close()
                } catch (_: Exception) {
                    // Diagnostic cleanup only.
                }
            }
        }
    }

    private fun probeLine(label: String, result: ProbeResult): String {
        val status = when (result.supported) {
            true -> "SUPPORTED"
            false -> "NOT SUPPORTED"
            null -> "NOT TESTED"
        }
        return "$label: $status" + (result.detail?.let { " ($it)" } ?: "")
    }

    private fun outputSizes(
        map: android.hardware.camera2.params.StreamConfigurationMap?,
        format: Int
    ): List<Size> {
        if (map == null) return emptyList()
        return try {
            map.getOutputSizes(format)?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun pickProbeSize(sizes: List<Size>, maxWidth: Int, maxHeight: Int): Size? {
        if (sizes.isEmpty()) return null
        val capped = sizes.filter { it.width <= maxWidth && it.height <= maxHeight }
        return (capped.ifEmpty { sizes }).maxByOrNull {
            it.width.toLong() * it.height.toLong()
        }
    }

    private fun sizesSummary(sizes: List<Size>): String {
        if (sizes.isEmpty()) return "none"
        val sorted = sizes.distinct().sortedByDescending {
            it.width.toLong() * it.height.toLong()
        }
        val shown = sorted.take(10).joinToString()
        val remainder = sorted.size - minOf(sorted.size, 10)
        val maxSize = sorted.firstOrNull()
        return "$shown${if (remainder > 0) " (+$remainder more)" else ""}; max=${maxSize ?: "unknown"}"
    }

    private fun hardwareLevelName(level: Int?): String = when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    private fun capabilityName(capability: Int): String = when (capability) {
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "HIGH_SPEED_VIDEO"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        else -> "CAPABILITY_$capability"
    }

    private fun afModeName(mode: Int): String = when (mode) {
        CameraCharacteristics.CONTROL_AF_MODE_OFF -> "OFF"
        CameraCharacteristics.CONTROL_AF_MODE_AUTO -> "AUTO"
        CameraCharacteristics.CONTROL_AF_MODE_MACRO -> "MACRO"
        CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONTINUOUS_VIDEO"
        CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONTINUOUS_PICTURE"
        CameraCharacteristics.CONTROL_AF_MODE_EDOF -> "EDOF"
        else -> "AF_$mode"
    }
}
