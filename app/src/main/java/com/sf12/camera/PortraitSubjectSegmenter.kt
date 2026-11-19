package com.sf12.camera

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * Classical, offline foreground segmentation for Portrait mode.
 *
 * GrabCut is seeded by the user's tap/long-press points. It is category-agnostic:
 * it does not depend on faces, people, pets or a learned subject model.
 */
internal class PortraitSubjectSegmenter {

    data class SegmentationMask(
        val width: Int,
        val height: Int,
        val alpha: ByteArray
    )

    fun createMask(
        bitmap: Bitmap,
        normalizedFocusPoints: List<Pair<Float, Float>>
    ): SegmentationMask {
        require(bitmap.width > 1 && bitmap.height > 1) { "Image is too small to segment" }
        check(OpenCVLoader.initLocal()) { "OpenCV could not be initialized" }

        val width = bitmap.width
        val height = bitmap.height
        val pointList = normalizedFocusPoints.ifEmpty { listOf(0.5f to 0.5f) }
            .take(5)
            .map { (x, y) ->
                val marginX = 8f / width
                val marginY = 8f / height
                (x.coerceIn(marginX, 1f - marginX) * (width - 1)) to
                    (y.coerceIn(marginY, 1f - marginY) * (height - 1))
            }

        val rgba = Mat()
        val bgr = Mat()
        val labels = Mat()
        val backgroundModel = Mat()
        val foregroundModel = Mat()
        val sureForeground = Mat()
        val probableForeground = Mat()
        val binaryMask = Mat()
        val expandedMask = Mat()
        val softenedMask = Mat()
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE,
            Size(5.0, 5.0)
        )

        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)

            val initialLabels = ByteArray(width * height) {
                Imgproc.GC_PR_BGD.toByte()
            }
            // Keep a generous probable-foreground region around each tap.
            // GrabCut refines the boundary from image colour and texture; the tap
            // should guide it, not impose a tight crop around the selected pixel.
            val radii = pointList.mapIndexed { index, _ ->
                val widthFraction = if (index == 0) 0.58f else 0.42f
                val heightFraction = if (index == 0) 0.58f else 0.42f
                max(12f, width * widthFraction) to max(12f, height * heightFraction)
            }

            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    var insideSelection = false
                    for (index in pointList.indices) {
                        val point = pointList[index]
                        val radius = radii[index]
                        val dx = (x - point.first) / radius.first
                        val dy = (y - point.second) / radius.second
                        if (dx * dx + dy * dy <= 1f) {
                            insideSelection = true
                            break
                        }
                    }
                    if (insideSelection) {
                        initialLabels[row + x] = Imgproc.GC_PR_FGD.toByte()
                    }
                }
            }

            // A firm background border gives GrabCut background samples even when
            // the selected subject is large or touches the probable-foreground area.
            val border = max(5, min(width, height) / 90)
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    if (x < border || y < border ||
                        x >= width - border || y >= height - border
                    ) {
                        initialLabels[row + x] = Imgproc.GC_BGD.toByte()
                    }
                }
            }

            // Each tap contributes a hard foreground seed so separately selected
            // objects can remain sharp in the same photograph.
            pointList.forEach { point ->
                val radius = max(4, min(width, height) / 28)
                val centreX = point.first.toInt()
                val centreY = point.second.toInt()
                val radiusSquared = radius * radius
                for (y in max(border, centreY - radius)..min(height - border - 1, centreY + radius)) {
                    val row = y * width
                    for (x in max(border, centreX - radius)..min(width - border - 1, centreX + radius)) {
                        val dx = x - centreX
                        val dy = y - centreY
                        if (dx * dx + dy * dy <= radiusSquared) {
                            initialLabels[row + x] = Imgproc.GC_FGD.toByte()
                        }
                    }
                }
            }

            labels.create(height, width, CvType.CV_8UC1)
            labels.put(0, 0, initialLabels)
            Imgproc.grabCut(
                bgr,
                labels,
                Rect(0, 0, width, height),
                backgroundModel,
                foregroundModel,
                5,
                Imgproc.GC_INIT_WITH_MASK
            )

            Core.compare(
                labels,
                Scalar(Imgproc.GC_FGD.toDouble()),
                sureForeground,
                Core.CMP_EQ
            )
            Core.compare(
                labels,
                Scalar(Imgproc.GC_PR_FGD.toDouble()),
                probableForeground,
                Core.CMP_EQ
            )
            Core.bitwise_or(sureForeground, probableForeground, binaryMask)
            Imgproc.dilate(binaryMask, expandedMask, kernel)
            Imgproc.GaussianBlur(
                expandedMask,
                softenedMask,
                Size(0.0, 0.0),
                5.0
            )

            val alpha = ByteArray(width * height)
            softenedMask.get(0, 0, alpha)
            var foregroundPixels = 0
            alpha.forEach { if ((it.toInt() and 0xFF) >= 128) foregroundPixels++ }
            val foregroundRatio = foregroundPixels.toFloat() / alpha.size
            check(foregroundRatio in 0.005f..0.92f) {
                "Subject selection was unclear; try tapping closer to the subject"
            }

            return SegmentationMask(width, height, alpha)
        } finally {
            rgba.release()
            bgr.release()
            labels.release()
            backgroundModel.release()
            foregroundModel.release()
            sureForeground.release()
            probableForeground.release()
            binaryMask.release()
            expandedMask.release()
            softenedMask.release()
            kernel.release()
        }
    }
}
