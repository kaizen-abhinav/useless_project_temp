package com.example

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Tactical HUD Overlay with Single-Axis Pan Aiming & High-Beam Photon Torch Control.
 *
 * Visual Features:
 * - Center gimbal horizon and crosshairs
 * - Headlight pair tracking bounds and baseline vector
 * - Photometric glare dispersion cones (Red = High Beam, Cyan = Low Beam)
 * - Helmet-Mounted Retaliatory Torch Beam Vector (Fired directly at driver eye-box on High Beam)
 * - High-Beam Countermeasure Warning Banner
 * - NMEA ESP32 Serial Packet Output ($HBGCS,PAN:xxx,BEAM:x,TORCH:ON/OFF*3F)
 * - Dynamic Single-Axis Pan Servo Gauge (Left/Right Azimuth)
 */
class HudOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var telemetry: DriverTargetAnalyzer.TelemetryData? = null

    // Paints
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4400E5FF")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val gridTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8800E5FF")
        textSize = 22f
        typeface = Typeface.MONOSPACE
    }

    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9900E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val headlightBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val headlightHighBeamBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFEA00")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(15f, 10f), 0f)
    }

    private val vectorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val strikeBeamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val strikeBeamGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FF1744")
        style = Paint.Style.STROKE
        strokeWidth = 18f
    }

    private val driverReticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val driverReticleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FF1744")
        style = Paint.Style.FILL
    }

    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        textSize = 26f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val alertBannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC1A0006")
        style = Paint.Style.FILL
    }

    private val alertBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val alertTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744")
        textSize = 24f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val gaugePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3300E5FF")
        style = Paint.Style.FILL
    }

    private val gaugeActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    private val gaugeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8800E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val conePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2200E5FF")
        style = Paint.Style.FILL
    }

    private val coneHighBeamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FF1744")
        style = Paint.Style.FILL
    }

    fun updateTelemetry(data: DriverTargetAnalyzer.TelemetryData) {
        this.telemetry = data
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        // 1. Draw Fixed Gimbal Horizon & Tactical Crosshairs
        drawTacticalGrid(canvas, viewW, viewH)

        val currentData = telemetry ?: return

        // Compute coordinate scaling from analyzer frame to view size
        val frameW = currentData.frameWidth.toFloat()
        val frameH = currentData.frameHeight.toFloat()
        if (frameW <= 0f || frameH <= 0f) return

        val scale = maxOf(viewW / frameW, viewH / frameH)
        val offsetX = (viewW - frameW * scale) / 2f
        val offsetY = (viewH - frameH * scale) / 2f

        fun toViewCoords(pt: PointF): PointF {
            return PointF(
                pt.x * scale + offsetX,
                pt.y * scale + offsetY
            )
        }

        // 2. Draw all detected candidate light blobs
        for (blob in currentData.allBlobs) {
            val vPt = toViewCoords(blob)
            canvas.drawCircle(vPt.x, vPt.y, 8f, blobPaint)
        }

        // 3. Draw Tracked Headlights, Photometric Glare Cones, and Driver Eye-Box Target
        if (currentData.locked && currentData.headlightLeft != null && currentData.headlightRight != null) {
            val p1 = toViewCoords(currentData.headlightLeft)
            val p2 = toViewCoords(currentData.headlightRight)

            val isHighBeam = currentData.beamType == DriverTargetAnalyzer.BeamType.HIGH_BEAM
            val activeBoxPaint = if (isHighBeam) headlightHighBeamBoxPaint else headlightBoxPaint

            // Draw Photometric Glare Cones from headlights towards camera
            drawGlareCones(canvas, p1, p2, isHighBeam)

            // Draw Headlight Bracket Boxes
            val boxSize = 26f * scale
            drawCornerBracket(canvas, p1.x, p1.y, boxSize, activeBoxPaint)
            drawCornerBracket(canvas, p2.x, p2.y, boxSize, activeBoxPaint)

            canvas.drawText("P1:L", p1.x - boxSize, p1.y + boxSize + 20f, gridTextPaint)
            canvas.drawText("P2:R", p2.x + 8f, p2.y + boxSize + 20f, gridTextPaint)

            // Draw Headlight Baseline
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, baselinePaint)

            val midX = (p1.x + p2.x) / 2f
            val midY = (p1.y + p2.y) / 2f
            canvas.drawCircle(midX, midY, 6f, baselinePaint)

            // Draw Driver Windshield & Eye-Box Target
            if (currentData.driverTarget != null) {
                val driverPt = toViewCoords(currentData.driverTarget)

                // Vector from vehicle midpoint to driver head
                canvas.drawLine(midX, midY, driverPt.x, driverPt.y, vectorLinePaint)

                // IF HIGH BEAM: Fire retaliatory helmet torch photon vector directly into driver's eyes!
                if (currentData.countermeasureActive) {
                    val helmetSourceX = viewW / 2f
                    val helmetSourceY = viewH

                    // Glowing laser strike line from helmet torch origin to target
                    canvas.drawLine(helmetSourceX, helmetSourceY, driverPt.x, driverPt.y, strikeBeamGlowPaint)
                    canvas.drawLine(helmetSourceX, helmetSourceY, driverPt.x, driverPt.y, strikeBeamPaint)
                }

                // Target Reticle
                val r = 28f
                canvas.drawCircle(driverPt.x, driverPt.y, r, driverReticleFillPaint)
                canvas.drawCircle(driverPt.x, driverPt.y, r, driverReticlePaint)
                canvas.drawCircle(driverPt.x, driverPt.y, 6f, driverReticlePaint)

                // Crosshairs on target reticle
                canvas.drawLine(driverPt.x - r - 10f, driverPt.y, driverPt.x + r + 10f, driverPt.y, driverReticlePaint)
                canvas.drawLine(driverPt.x, driverPt.y - r - 10f, driverPt.x, driverPt.y + r + 10f, driverReticlePaint)

                // Target Text Overlay
                val targetStatusStr = if (isHighBeam) "STRIKE TARGET: EYE BOX" else "TARGET: RHD DRIVER"
                hudTextPaint.color = if (isHighBeam) Color.parseColor("#FF1744") else Color.parseColor("#00E676")

                val torchLabel = if (currentData.countermeasureActive) "TORCH: ON 🔥" else "TORCH: OFF"
                canvas.drawText(targetStatusStr, driverPt.x + r + 12f, driverPt.y - 10f, hudTextPaint)
                canvas.drawText(
                    String.format(Locale.US, "PAN:%d° %s DIST:%.1fm", currentData.pan, torchLabel, currentData.estimatedDistanceMeters),
                    driverPt.x + r + 12f,
                    driverPt.y + 20f,
                    gridTextPaint
                )
            }
        }

        // 4. High-Beam Countermeasure Warning Banner at top-center
        if (currentData.locked && currentData.beamType == DriverTargetAnalyzer.BeamType.HIGH_BEAM) {
            drawCountermeasureBanner(canvas, viewW, currentData)
        }

        // 5. Draw NMEA ESP32 Serial Telemetry Output Line
        if (currentData.nmeaPacket.isNotEmpty()) {
            canvas.drawText(currentData.nmeaPacket, 16f, viewH - 12f, gridTextPaint)
        }

        // 6. Draw Angular Pan Servo Gauge on bottom
        drawPanServoGauge(canvas, viewW, viewH, currentData.pan, currentData.locked, currentData.countermeasureActive)
    }

    private fun drawTacticalGrid(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h / 2f

        // Center crosshairs
        canvas.drawLine(cx - 50f, cy, cx - 15f, cy, crosshairPaint)
        canvas.drawLine(cx + 15f, cy, cx + 50f, cy, crosshairPaint)
        canvas.drawLine(cx, cy - 50f, cx, cy - 15f, crosshairPaint)
        canvas.drawLine(cx, cy + 15f, cx, cy + 50f, crosshairPaint)

        // Concentric horizon circles
        canvas.drawCircle(cx, cy, 70f, crosshairPaint)
        canvas.drawCircle(cx, cy, 180f, crosshairPaint)

        // Pitch ladder tick marks
        for (i in -2..2) {
            if (i == 0) continue
            val tickY = cy + i * 60f
            val tickW = if (i % 2 == 0) 30f else 15f
            canvas.drawLine(cx - tickW, tickY, cx + tickW, tickY, crosshairPaint)
        }

        canvas.drawText("+90° AZIMUTH (BORESIGHT)", cx + 20f, cy - 20f, gridTextPaint)
    }

    private fun drawGlareCones(canvas: Canvas, p1: PointF, p2: PointF, isHighBeam: Boolean) {
        val paint = if (isHighBeam) coneHighBeamPaint else conePaint
        val coneLength = if (isHighBeam) 160f else 90f
        val coneSpread = if (isHighBeam) 45f else 25f

        // Cone for Left Headlight
        val path1 = Path().apply {
            moveTo(p1.x, p1.y)
            lineTo(p1.x - coneSpread, p1.y + coneLength)
            lineTo(p1.x + coneSpread, p1.y + coneLength)
            close()
        }
        canvas.drawPath(path1, paint)

        // Cone for Right Headlight
        val path2 = Path().apply {
            moveTo(p2.x, p2.y)
            lineTo(p2.x - coneSpread, p2.y + coneLength)
            lineTo(p2.x + coneSpread, p2.y + coneLength)
            close()
        }
        canvas.drawPath(path2, paint)
    }

    private fun drawCountermeasureBanner(canvas: Canvas, viewW: Float, data: DriverTargetAnalyzer.TelemetryData) {
        val bannerW = 680f
        val bannerH = 50f
        val bannerLeft = (viewW - bannerW) / 2f
        val bannerTop = 60f

        val rect = RectF(bannerLeft, bannerTop, bannerLeft + bannerW, bannerTop + bannerH)
        canvas.drawRoundRect(rect, 8f, 8f, alertBannerPaint)
        canvas.drawRoundRect(rect, 8f, 8f, alertBorderPaint)

        val confidencePct = (data.highBeamConfidence * 100).roundToInt()
        val alertText = String.format(Locale.US, "⚡ HIGH BEAM DETECTED [%d%%] - RETALIATORY TORCH ON ⚡", confidencePct)
        val textWidth = alertTextPaint.measureText(alertText)
        val textX = bannerLeft + (bannerW - textWidth) / 2f
        val textY = bannerTop + 33f

        canvas.drawText(alertText, textX, textY, alertTextPaint)
    }

    private fun drawCornerBracket(canvas: Canvas, x: Float, y: Float, size: Float, paint: Paint) {
        val half = size / 2f
        val arm = size / 3f

        // Top-left
        canvas.drawLine(x - half, y - half, x - half + arm, y - half, paint)
        canvas.drawLine(x - half, y - half, x - half, y - half + arm, paint)

        // Top-right
        canvas.drawLine(x + half, y - half, x + half - arm, y - half, paint)
        canvas.drawLine(x + half, y - half, x + half, y - half + arm, paint)

        // Bottom-left
        canvas.drawLine(x - half, y + half, x - half + arm, y + half, paint)
        canvas.drawLine(x - half, y + half, x - half, y - half + arm, paint)

        // Bottom-right
        canvas.drawLine(x + half, y + half, x + half - arm, y + half, paint)
        canvas.drawLine(x + half, y + half, x + half, y + half - arm, paint)
    }

    private fun drawPanServoGauge(canvas: Canvas, w: Float, h: Float, pan: Int, locked: Boolean, countermeasure: Boolean) {
        // Bottom Pan Servo Track (Left 0° <---> 90° Center <---> 180° Right)
        val gaugeH = 16f
        val gaugeW = 320f
        val gaugeLeft = (w - gaugeW) / 2f
        val gaugeTop = h - 50f

        canvas.drawRoundRect(gaugeLeft, gaugeTop, gaugeLeft + gaugeW, gaugeTop + gaugeH, 6f, 6f, gaugePaint)
        canvas.drawRoundRect(gaugeLeft, gaugeTop, gaugeLeft + gaugeW, gaugeTop + gaugeH, 6f, 6f, gaugeBorderPaint)

        val centerIndicatorX = gaugeLeft + (gaugeW / 2f)
        canvas.drawLine(centerIndicatorX, gaugeTop - 4f, centerIndicatorX, gaugeTop + gaugeH + 4f, gridTextPaint)

        val panRatio = (pan.coerceIn(0, 180)) / 180f
        val markerX = gaugeLeft + panRatio * gaugeW

        gaugeActivePaint.color = when {
            countermeasure -> Color.parseColor("#FF1744")
            locked -> Color.parseColor("#00E676")
            else -> Color.parseColor("#00E5FF")
        }
        canvas.drawCircle(markerX, gaugeTop + (gaugeH / 2f), 10f, gaugeActivePaint)

        val torchText = if (countermeasure) "TORCH: ON 🔥" else "TORCH: OFF"
        canvas.drawText("PAN SERVO: $pan° | $torchText", gaugeLeft, gaugeTop - 10f, gridTextPaint)
    }
}
