package com.example

import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Clean Default High-Speed OpenCV Computer Vision & Kinematics Analyzer.
 *
 * Implements:
 * 1. Luminance Y-plane zero-copy extraction to 8-bit grayscale Mat.
 * 2. Binary thresholding (> 230) to isolate bright headlight filament cores.
 * 3. Contour extraction and moment-based centroid calculation (30 <= Area <= 15000 px).
 * 4. Symmetrical pair matching (dx > 40, dy < dx * 0.25, area ratio < 2.5).
 * 5. Photometric High-Beam classification and Single-Axis Pan Servo Aiming.
 */
class DriverTargetAnalyzer(
    private val targetVectorListener: TargetVectorListener? = null,
    private val telemetryListener: TelemetryListener? = null
) : ImageAnalysis.Analyzer {

    enum class BeamType {
        NONE,
        LOW_BEAM,
        HIGH_BEAM
    }

    companion object {
        private const val TAG = "DriverTargetAnalyzer"

        const val MIN_CONTOUR_AREA = 30.0
        const val MAX_CONTOUR_AREA = 15000.0
        const val BRIGHTNESS_THRESHOLD = 230.0

        const val MIN_HORIZONTAL_SEPARATION = 40.0
        const val MAX_VERTICAL_ALIGNMENT_RATIO = 0.25
        const val MAX_AREA_SYMMETRY_RATIO = 2.5
        const val DRIVER_HEIGHT_FACTOR = 0.85
        const val DRIVER_LATERAL_OFFSET_FACTOR = 0.25
        const val SERVO_CENTER_DEG = 90
        const val PAN_FOV_HALF_DEG = 34.0

        private const val ASSUMED_HEADLIGHT_SEPARATION_METERS = 1.25
        private const val FOCAL_LENGTH_PX = 580.0
        private const val HIGH_BEAM_DISPERSION_THRESHOLD = 0.28
        private const val HIGH_BEAM_CONFIDENCE_CUTOFF = 0.60f
    }

    /**
     * Listener callback interface for servo vector updates and ESP32 dispatch.
     */
    interface TargetVectorListener {
        fun onTargetVectorUpdated(pan: Int, tilt: Int, locked: Boolean, countermeasureActive: Boolean)
    }

    /**
     * Telemetry callback interface for real-time HUD rendering.
     */
    interface TelemetryListener {
        fun onTelemetryUpdated(telemetry: TelemetryData)
    }

    data class TelemetryData(
        val fps: Double,
        val locked: Boolean,
        val pan: Int,
        val tilt: Int = SERVO_CENTER_DEG,
        val blobCount: Int,
        val headlightLeft: PointF? = null,
        val headlightRight: PointF? = null,
        val driverTarget: PointF? = null,
        val baselineWidth: Float = 0f,
        val frameWidth: Int = 640,
        val frameHeight: Int = 480,
        val latencyMs: Long = 0L,
        val allBlobs: List<PointF> = emptyList(),
        val beamType: BeamType = BeamType.NONE,
        val highBeamConfidence: Float = 0f,
        val glareLuxEstimate: Double = 0.0,
        val estimatedDistanceMeters: Double = 0.0,
        val countermeasureActive: Boolean = false,
        val nmeaPacket: String = ""
    )

    private data class LightBlob(
        val x: Double,
        val y: Double,
        val area: Double,
        val boundingRect: Rect
    )

    private var yPlaneBuffer: ByteArray? = null

    private var smoothedPan = SERVO_CENTER_DEG.toDouble()
    private val emaAlpha = 0.35

    private var frameCount = 0
    private var lastFpsCalculationTime = SystemClock.elapsedRealtime()
    private var currentFps = 0.0

    override fun analyze(imageProxy: ImageProxy) {
        val startTime = SystemClock.elapsedRealtime()

        try {
            val plane = imageProxy.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val imgWidth = imageProxy.width
            val imgHeight = imageProxy.height
            val remaining = buffer.remaining()

            if (yPlaneBuffer == null || yPlaneBuffer!!.size != remaining) {
                yPlaneBuffer = ByteArray(remaining)
            }
            buffer.get(yPlaneBuffer!!)

            val rawMat = Mat(imgHeight, rowStride, CvType.CV_8UC1)
            rawMat.put(0, 0, yPlaneBuffer)

            val yMat = if (rowStride == imgWidth) {
                rawMat
            } else {
                rawMat.submat(0, imgHeight, 0, imgWidth)
            }

            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val orientedMat = Mat()
            when (rotationDegrees) {
                90 -> Core.rotate(yMat, orientedMat, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(yMat, orientedMat, Core.ROTATE_180)
                270 -> Core.rotate(yMat, orientedMat, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> yMat.copyTo(orientedMat)
            }

            val frameWidth = orientedMat.cols()
            val frameHeight = orientedMat.rows()

            // Step 1: Binary thresholding (> 230)
            val threshMat = Mat()
            Imgproc.threshold(orientedMat, threshMat, BRIGHTNESS_THRESHOLD, 255.0, Imgproc.THRESH_BINARY)

            // Step 2: Contour extraction
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(threshMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            // Step 3: Centroid calculation
            val detectedBlobs = ArrayList<LightBlob>()
            val allBlobPoints = ArrayList<PointF>()

            for (contour in contours) {
                val moments = Imgproc.moments(contour)
                val area = moments.m00
                if (area in MIN_CONTOUR_AREA..MAX_CONTOUR_AREA) {
                    val cx = moments.m10 / moments.m00
                    val cy = moments.m01 / moments.m00
                    if (!cx.isNaN() && !cy.isNaN()) {
                        val rect = Imgproc.boundingRect(contour)
                        detectedBlobs.add(LightBlob(cx, cy, area, rect))
                        allBlobPoints.add(PointF(cx.toFloat(), cy.toFloat()))
                    }
                }
                contour.release()
            }

            // Step 4: Symmetrical Pair Matching
            var bestLeft: LightBlob? = null
            var bestRight: LightBlob? = null
            var maxBaseline = -1.0

            val numBlobs = detectedBlobs.size
            for (i in 0 until numBlobs) {
                for (j in i + 1 until numBlobs) {
                    val b1 = detectedBlobs[i]
                    val b2 = detectedBlobs[j]

                    val left = if (b1.x <= b2.x) b1 else b2
                    val right = if (b1.x <= b2.x) b2 else b1

                    val dx = right.x - left.x
                    val dy = abs(right.y - left.y)

                    if (dx <= MIN_HORIZONTAL_SEPARATION) continue
                    if (dy >= dx * MAX_VERTICAL_ALIGNMENT_RATIO) continue

                    val maxArea = max(left.area, right.area)
                    val minArea = min(left.area, right.area)
                    if (minArea <= 0.0 || (maxArea / minArea) >= MAX_AREA_SYMMETRY_RATIO) continue

                    val baseline = hypot(dx, right.y - left.y)
                    if (baseline > maxBaseline) {
                        maxBaseline = baseline
                        bestLeft = left
                        bestRight = right
                    }
                }
            }

            // Step 5: Triangulation & High Beam Classification
            var isLocked = false
            var panAngle = SERVO_CENTER_DEG
            val tiltAngle = SERVO_CENTER_DEG
            var driverTargetPoint: PointF? = null
            var headlightLeftPoint: PointF? = null
            var headlightRightPoint: PointF? = null
            var baselineWidthFloat = 0f
            var beamType = BeamType.NONE
            var highBeamConfidence = 0.0f
            var glareLuxEstimate = 0.0
            var estimatedDistanceMeters = 0.0
            var countermeasureActive = false
            var nmeaPacket = ""

            if (bestLeft != null && bestRight != null) {
                isLocked = true
                headlightLeftPoint = PointF(bestLeft.x.toFloat(), bestLeft.y.toFloat())
                headlightRightPoint = PointF(bestRight.x.toFloat(), bestRight.y.toFloat())

                val dx = bestRight.x - bestLeft.x
                val dy = bestRight.y - bestLeft.y
                val baselineW = hypot(dx, dy)
                baselineWidthFloat = baselineW.toFloat()

                val mx = (bestLeft.x + bestRight.x) / 2.0
                val my = (bestLeft.y + bestRight.y) / 2.0

                estimatedDistanceMeters = (FOCAL_LENGTH_PX * ASSUMED_HEADLIGHT_SEPARATION_METERS) / baselineW

                val roiX = max(0, min(bestLeft.boundingRect.x, bestRight.boundingRect.x) - 10)
                val roiY = max(0, min(bestLeft.boundingRect.y, bestRight.boundingRect.y) - 15)
                val roiW = min(frameWidth - roiX, max(bestLeft.boundingRect.x + bestLeft.boundingRect.width, bestRight.boundingRect.x + bestRight.boundingRect.width) - roiX + 10)
                val roiH = min(frameHeight - roiY, max(bestLeft.boundingRect.height, bestRight.boundingRect.height) + 30)

                if (roiW > 0 && roiH > 0) {
                    val roiMat = Mat(orientedMat, Rect(roiX, roiY, roiW, roiH))

                    val halfRoiH = roiH / 2
                    val upperRoi = Mat(roiMat, Rect(0, 0, roiW, halfRoiH))
                    val lowerRoi = Mat(roiMat, Rect(0, halfRoiH, roiW, roiH - halfRoiH))

                    val upperSum = Core.sumElems(upperRoi).`val`[0]
                    val lowerSum = Core.sumElems(lowerRoi).`val`[0]
                    val totalSum = upperSum + lowerSum

                    val dispersionRatio = if (totalSum > 0.0) upperSum / totalSum else 0.0
                    val meanLuminance = Core.mean(roiMat).`val`[0]

                    glareLuxEstimate = ((meanLuminance * (bestLeft.area + bestRight.area)) / max(1.0, estimatedDistanceMeters * estimatedDistanceMeters)) * 1.55

                    val luminanceScore = (meanLuminance / 255.0).coerceIn(0.0, 1.0)
                    val dispersionScore = (dispersionRatio / HIGH_BEAM_DISPERSION_THRESHOLD).coerceIn(0.0, 1.0)
                    val areaScore = ((bestLeft.area + bestRight.area) / 1000.0).coerceIn(0.0, 1.0)

                    highBeamConfidence = (0.45 * dispersionScore + 0.35 * luminanceScore + 0.20 * areaScore).toFloat().coerceIn(0.0f, 1.0f)

                    if (highBeamConfidence >= HIGH_BEAM_CONFIDENCE_CUTOFF) {
                        beamType = BeamType.HIGH_BEAM
                        countermeasureActive = true
                    } else {
                        beamType = BeamType.LOW_BEAM
                        countermeasureActive = false
                    }

                    upperRoi.release()
                    lowerRoi.release()
                    roiMat.release()
                }

                val yDriver = my - (DRIVER_HEIGHT_FACTOR * baselineW)
                val xDriver = mx - (DRIVER_LATERAL_OFFSET_FACTOR * baselineW)

                driverTargetPoint = PointF(xDriver.toFloat(), yDriver.toFloat())

                val centerX = frameWidth / 2.0
                val normX = (xDriver - centerX) / centerX

                val rawPan = (SERVO_CENTER_DEG - (normX * PAN_FOV_HALF_DEG)).coerceIn(0.0, 180.0)
                smoothedPan = smoothedPan + emaAlpha * (rawPan - smoothedPan)
                panAngle = smoothedPan.roundToInt()

                val torchState = if (countermeasureActive) "ON" else "OFF"
                val beamStr = if (beamType == BeamType.HIGH_BEAM) "HIGH" else "LOW"
                nmeaPacket = String.format(
                    Locale.US,
                    "\$HBGCS,PAN:%03d,BEAM:%s,TORCH:%s*3F",
                    panAngle, beamStr, torchState
                )
            } else if (detectedBlobs.isNotEmpty()) {
                // Single Light Spot Lock (e.g., flashlight or single headlight)
                val maxBlob = detectedBlobs.maxByOrNull { it.area }
                if (maxBlob != null && maxBlob.area >= 30.0) {
                    isLocked = true
                    headlightLeftPoint = PointF(maxBlob.x.toFloat(), maxBlob.y.toFloat())
                    headlightRightPoint = PointF(maxBlob.x.toFloat(), maxBlob.y.toFloat())
                    baselineWidthFloat = maxBlob.boundingRect.width.toFloat()

                    driverTargetPoint = PointF(maxBlob.x.toFloat(), maxBlob.y.toFloat())

                    glareLuxEstimate = (maxBlob.area * 2.8).coerceIn(150.0, 2500.0)
                    highBeamConfidence = 0.95f
                    beamType = BeamType.HIGH_BEAM
                    countermeasureActive = true

                    val centerX = frameWidth / 2.0
                    val normX = (maxBlob.x - centerX) / centerX

                    val rawPan = (SERVO_CENTER_DEG - (normX * PAN_FOV_HALF_DEG)).coerceIn(0.0, 180.0)
                    smoothedPan = smoothedPan + emaAlpha * (rawPan - smoothedPan)
                    panAngle = smoothedPan.roundToInt()

                    nmeaPacket = String.format(
                        Locale.US,
                        "\$HBGCS,PAN:%03d,BEAM:HIGH,TORCH:ON*3F",
                        panAngle
                    )
                }
            } else {
                smoothedPan = smoothedPan + 0.15 * (SERVO_CENTER_DEG - smoothedPan)
                panAngle = smoothedPan.roundToInt()
                countermeasureActive = false
                beamType = BeamType.NONE

                nmeaPacket = String.format(
                    Locale.US,
                    "\$HBGCS,PAN:%03d,BEAM:OFF,TORCH:OFF*3F",
                    panAngle
                )
            }

            targetVectorListener?.onTargetVectorUpdated(panAngle, tiltAngle, isLocked, countermeasureActive)

            frameCount++
            val now = SystemClock.elapsedRealtime()
            val timeDiff = now - lastFpsCalculationTime
            if (timeDiff >= 1000L) {
                currentFps = (frameCount * 1000.0) / timeDiff
                frameCount = 0
                lastFpsCalculationTime = now
            }

            val latency = SystemClock.elapsedRealtime() - startTime

            telemetryListener?.onTelemetryUpdated(
                TelemetryData(
                    fps = currentFps,
                    locked = isLocked,
                    pan = panAngle,
                    tilt = tiltAngle,
                    blobCount = detectedBlobs.size,
                    headlightLeft = headlightLeftPoint,
                    headlightRight = headlightRightPoint,
                    driverTarget = driverTargetPoint,
                    baselineWidth = baselineWidthFloat,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    latencyMs = latency,
                    allBlobs = allBlobPoints,
                    beamType = beamType,
                    highBeamConfidence = highBeamConfidence,
                    glareLuxEstimate = glareLuxEstimate,
                    estimatedDistanceMeters = estimatedDistanceMeters,
                    countermeasureActive = countermeasureActive,
                    nmeaPacket = nmeaPacket
                )
            )

            hierarchy.release()
            threshMat.release()
            orientedMat.release()
            if (yMat !== rawMat) {
                yMat.release()
            }
            rawMat.release()

        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing image frame", e)
        } finally {
            imageProxy.close()
        }
    }
}
