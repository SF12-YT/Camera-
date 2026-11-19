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

            // Build GrabCut's initial labels with native OpenCV drawing operations.
            // The previous per-pixel Kotlin loops dominated setup time on large images.
            labels.create(height, width, CvType.CV_8UC1)
            labels.setTo(Scalar(Imgproc.GC_PR_BGD.toDouble()))

            // Keep a generous probable-foreground region around each tap.
            // GrabCut refines the boundary from image colour and texture; the tap
            // should guide it, not impose a tight crop around the selected pixel.
            val radii = pointList.mapIndexed { index, _ ->
                val widthFraction = if (index == 0) 0.58f else 0.42f
                val heightFraction = if (index == 0) 0.58f else 0.42f
                max(12f, width * widthFraction) to max(12f, height * heightFraction)
            }
            pointList.forEachIndexed { index, point ->
                val radius = radii[index]
                Imgproc.ellipse(
                    labels,
                    org.opencv.core.Point(point.first.toDouble(), point.second.toDouble()),
                    Size(radius.first.toDouble(), radius.second.toDouble()),
                    0.0,
                    0.0,
                    360.0,
                    Scalar(Imgproc.GC_PR_FGD.toDouble()),
                    -1
                )
            }

            // A firm background border gives GrabCut background samples even when
            // the selected subject is large or touches the probable-foreground area.
            val border = max(5, min(width, height) / 90)
            val background = Scalar(Imgproc.GC_BGD.toDouble())
            Imgproc.rectangle(
                labels,
                org.opencv.core.Point(0.0, 0.0),
                org.opencv.core.Point((width - 1).toDouble(), (border - 1).toDouble()),
                background,
                -1
            )
            Imgproc.rectangle(
                labels,
                org.opencv.core.Point(0.0, (height - border).toDouble()),
                org.opencv.core.Point((width - 1).toDouble(), (height - 1).toDouble()),
                background,
                -1
            )
            Imgproc.rectangle(
                labels,
                org.opencv.core.Point(0.0, border.toDouble()),
                org.opencv.core.Point((border - 1).toDouble(), (height - border - 1).toDouble()),
                background,
                -1
            )
            Imgproc.rectangle(
                labels,
                org.opencv.core.Point((width - border).toDouble(), border.toDouble()),
                org.opencv.core.Point((width - 1).toDouble(), (height - border - 1).toDouble()),
                background,
                -1
            )

            // Each tap contributes a hard foreground seed so separately selected
            // objects can remain sharp in the same photograph.
            val seedRadius = max(4, min(width, height) / 28)
            pointList.forEach { point ->
                val centreX = point.first.toInt().coerceIn(border + seedRadius, width - border - seedRadius - 1)
                val centreY = point.second.toInt().coerceIn(border + seedRadius, height - border - seedRadius - 1)
                Imgproc.circle(
                    labels,
                    org.opencv.core.Point(centreX.toDouble(), centreY.toDouble()),
                    seedRadius,
                    Scalar(Imgproc.GC_FGD.toDouble()),
                    -1
                )
            }
            Imgproc.grabCut(
                bgr,
                labels,
                Rect(0, 0, width, height),
                backgroundModel,
                foregroundModel,
                2,
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
            val initialForegroundRatio = Core.countNonZero(binaryMask).toFloat() /
                (width * height).toFloat()
            // Spend one extra native iteration only for unusually small or large
            // selections, where the first mask is more likely to need refinement.
            if (initialForegroundRatio < 0.015f || initialForegroundRatio > 0.88f) {
                Imgproc.grabCut(
                    bgr,
                    labels,
                    Rect(0, 0, width, height),
                    backgroundModel,
                    foregroundModel,
                    1,
                    Imgproc.GC_EVAL
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
            }
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
