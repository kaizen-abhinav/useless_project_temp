package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PointF
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.databinding.ActivityMainBinding
import org.opencv.android.OpenCVLoader
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Helmet Mount Computer Vision & High-Beam Glare Countermeasure Engine.
 *
 * Implements:
 * 1. OpenCV 4.9.0 native runtime initialization.
 * 2. CameraX 640x480 feed binding with STRATEGY_KEEP_ONLY_LATEST backpressure.
 * 3. Camera2 interop exposure clamping to minimum (-4 EV or lower) to isolate high-beam filaments.
 * 4. DriverTargetAnalyzer integration with photometric high-beam classifier and HUD.
 * 5. Serial NMEA protocol vector generation for ESP32 helmet mount torch.
 * 6. High-Beam Retaliation Audio Engine:
 *    - Plays `are2.mp3` at MAX volume when high beam is detected.
 *    - Plays `marathaka.mp3` at MAX volume when the vehicle passes / high beam terminates.
 */
class MainActivity : ComponentActivity(),
    DriverTargetAnalyzer.TargetVectorListener,
    DriverTargetAnalyzer.TelemetryListener {

    enum class SimMode {
        OFF,
        HIGH_BEAM,
        LOW_BEAM
    }

    companion object {
        private const val TAG = "HighBeamAimHUD"
        private const val TARGET_WIDTH = 640
        private const val TARGET_HEIGHT = 480
        private const val DEFAULT_CLAMPED_EV = -4
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var camera: Camera? = null
    private var isExposureClamped = true
    private var simMode = SimMode.OFF

    // Audio Engine State
    private var mediaPlayer: MediaPlayer? = null
    private var isHighBeamActive = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            binding.permissionContainer.visibility = View.GONE
            startCamera()
        } else {
            binding.permissionContainer.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen awake continuously while helmet-mounted
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUI()

        // Initialize OpenCV native binaries
        initializeOpenCV()

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupControls()

        // Verify camera permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            binding.permissionContainer.visibility = View.GONE
            startCamera()
        } else {
            binding.permissionContainer.visibility = View.VISIBLE
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun initializeOpenCV() {
        if (!OpenCVLoader.initLocal()) {
            Log.e(TAG, "OpenCV native library failed to load via initLocal.")
        } else {
            Log.d(TAG, "OpenCV 4.9.0 loaded successfully.")
        }
    }

    private fun setupControls() {
        binding.btnGrantPermission.setOnClickListener {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.btnToggleExposure.setOnClickListener {
            isExposureClamped = !isExposureClamped
            applyExposureClamping()
        }

        binding.btnSimulateTarget.setOnClickListener {
            simMode = when (simMode) {
                SimMode.OFF -> SimMode.HIGH_BEAM
                SimMode.HIGH_BEAM -> SimMode.LOW_BEAM
                SimMode.LOW_BEAM -> SimMode.OFF
            }

            when (simMode) {
                SimMode.HIGH_BEAM -> {
                    binding.btnSimulateTarget.text = "SIM: HIGH BEAM"
                    binding.btnSimulateTarget.setBackgroundColor(Color.parseColor("#88FF1744"))
                    runSimulationPulse(isHighBeam = true)
                }
                SimMode.LOW_BEAM -> {
                    binding.btnSimulateTarget.text = "SIM: LOW BEAM"
                    binding.btnSimulateTarget.setBackgroundColor(Color.parseColor("#4400E5FF"))
                    runSimulationPulse(isHighBeam = false)
                }
                SimMode.OFF -> {
                    binding.btnSimulateTarget.text = "SIM HIGH BEAM"
                    binding.btnSimulateTarget.setBackgroundColor(Color.parseColor("#2200E676"))
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(TARGET_WIDTH, TARGET_HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                // Preview setup
                val preview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .build()
                    .also {
                        it.surfaceProvider = binding.previewView.surfaceProvider
                    }

                // ImageAnalysis pipeline with KEEP_ONLY_LATEST strategy
                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(
                            cameraExecutor,
                            DriverTargetAnalyzer(
                                targetVectorListener = this,
                                telemetryListener = this
                            )
                        )
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                // Forcefully clamp exposure compensation to turn ambient night scenery pitch-black
                applyExposureClamping()

            } catch (e: Exception) {
                Log.e(TAG, "Camera initialization failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyExposureClamping() {
        val cam = camera ?: return

        try {
            val cameraInfo = cam.cameraInfo
            val exposureState = cameraInfo.exposureState

            val targetEv = if (isExposureClamped) {
                if (exposureState.isExposureCompensationSupported) {
                    minOf(DEFAULT_CLAMPED_EV, exposureState.exposureCompensationRange.lower)
                } else {
                    DEFAULT_CLAMPED_EV
                }
            } else {
                0
            }

            if (exposureState.isExposureCompensationSupported) {
                cam.cameraControl.setExposureCompensationIndex(targetEv)
            }

            // Forcefully override via Camera2 interop capture request
            val camera2Control = Camera2CameraControl.from(cam.cameraControl)
            val captureRequestOptions = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    targetEv
                )
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_LOCK,
                    false
                )
                .setCaptureRequestOption(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_FAST
                )
                .build()

            camera2Control.setCaptureRequestOptions(captureRequestOptions)

            runOnUiThread {
                if (isExposureClamped) {
                    binding.exposureStatusText.text = "AE CLAMP: ${targetEv} EV [ON]"
                    binding.exposureStatusText.setTextColor(Color.parseColor("#00E676"))
                    binding.btnToggleExposure.text = "CLAMP: ON"
                } else {
                    binding.exposureStatusText.text = "AE CLAMP: 0 EV [OFF]"
                    binding.exposureStatusText.setTextColor(Color.parseColor("#FF5252"))
                    binding.btnToggleExposure.text = "CLAMP: OFF"
                }
            }
            Log.d(TAG, "Applied exposure compensation: $targetEv EV")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying exposure clamping", e)
        }
    }

    /**
     * Real-time kinematic target vector dispatch interface for ESP32 / Audio integration.
     */
    override fun onTargetVectorUpdated(pan: Int, tilt: Int, locked: Boolean, countermeasureActive: Boolean) {
        if (locked) {
            val status = if (countermeasureActive) "STRIKE ACTIVE" else "PASSIVE"
            Log.d(TAG, "TARGET VECTOR -> Pan: $pan°, Tilt: $tilt° [$status]")
        }

        // High Beam Audio Engine Trigger Logic
        val currentHighBeam = countermeasureActive
        if (currentHighBeam != isHighBeamActive) {
            isHighBeamActive = currentHighBeam
            runOnUiThread {
                if (currentHighBeam) {
                    // High beam detected -> play are2.mp3 at MAX volume
                    playAudio(R.raw.are2)
                } else {
                    // Vehicle passed / High beam terminated -> play marathaka.mp3 at MAX volume
                    playAudio(R.raw.marathaka)
                }
            }
        }
    }

    /**
     * Plays the specified raw audio resource at MAXIMUM stream volume.
     */
    private fun playAudio(resId: Int) {
        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)

            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(this, resId)
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Audio playback error", e)
        }
    }

    /**
     * Real-time HUD telemetry callback.
     */
    override fun onTelemetryUpdated(telemetry: DriverTargetAnalyzer.TelemetryData) {
        if (simMode != SimMode.OFF) return // Don't override active simulation feed

        runOnUiThread {
            // Update graphical overlay
            binding.hudOverlayView.updateTelemetry(telemetry)

            // Update top status badge
            when {
                telemetry.countermeasureActive -> {
                    binding.statusBadgeText.text = "[⚡ RETALIATORY STRIKE ENGAGED ⚡]"
                    binding.statusBadgeText.setTextColor(Color.parseColor("#FF1744"))
                    binding.statusBadgeText.setBackgroundResource(R.drawable.hud_status_badge)
                }
                telemetry.locked -> {
                    binding.statusBadgeText.text = "[LOW BEAM - PASSIVE TRACKING]"
                    binding.statusBadgeText.setTextColor(Color.parseColor("#00E676"))
                    binding.statusBadgeText.setBackgroundResource(R.drawable.hud_status_badge)
                }
                else -> {
                    binding.statusBadgeText.text = "[SEARCHING ONCOMING BEAMS]"
                    binding.statusBadgeText.setTextColor(Color.parseColor("#FFB300"))
                    binding.statusBadgeText.setBackgroundResource(R.drawable.hud_status_badge)
                }
            }

            // Update FPS & Latency
            binding.fpsText.text = String.format(Locale.US, "FPS: %.1f | %dms", telemetry.fps, telemetry.latencyMs)

            // Update Left Kinematics Panel
            binding.telemetryStatusText.text = if (telemetry.locked) "LOCK: LOCKED (RHD)" else "LOCK: SEARCHING"
            binding.telemetryStatusText.setTextColor(
                if (telemetry.locked) Color.parseColor("#00E676") else Color.parseColor("#B0BEC5")
            )
            binding.telemetryBlobsText.text = "BLOBS: ${telemetry.blobCount}"

            if (telemetry.locked) {
                binding.telemetryBaselineText.text = String.format(Locale.US, "BASELINE W: %.0f px", telemetry.baselineWidth)
            } else {
                binding.telemetryBaselineText.text = "BASELINE W: ---"
            }

            binding.telemetryPanText.text = "SERVO PAN:  ${telemetry.pan}°"
            binding.telemetryTiltText.text = "SERVO TILT: ${telemetry.tilt}°"

            // Update Right Photometric Classifier Panel
            val beamStr = when (telemetry.beamType) {
                DriverTargetAnalyzer.BeamType.HIGH_BEAM -> String.format(Locale.US, "BEAM: HIGH (%d%%)", (telemetry.highBeamConfidence * 100).roundToInt())
                DriverTargetAnalyzer.BeamType.LOW_BEAM -> String.format(Locale.US, "BEAM: LOW (%d%%)", (telemetry.highBeamConfidence * 100).roundToInt())
                DriverTargetAnalyzer.BeamType.NONE -> "BEAM: NONE"
            }

            binding.beamTypeText.text = beamStr
            binding.beamTypeText.setTextColor(
                if (telemetry.beamType == DriverTargetAnalyzer.BeamType.HIGH_BEAM) Color.parseColor("#FF1744") else Color.parseColor("#00E676")
            )

            binding.glareLuxText.text = String.format(Locale.US, "GLARE: %d LUX", telemetry.glareLuxEstimate.roundToInt())

            if (telemetry.countermeasureActive) {
                binding.countermeasureStatusText.text = "TORCH STRIKE: ACTIVE 🔥"
                binding.countermeasureStatusText.setTextColor(Color.parseColor("#FF1744"))
            } else {
                binding.countermeasureStatusText.text = "TORCH STRIKE: OFF"
                binding.countermeasureStatusText.setTextColor(Color.parseColor("#00E676"))
            }
        }
    }

    /**
     * Test simulation generator for indoor benchtop / hackathon presentation demo.
     */
    private fun runSimulationPulse(isHighBeam: Boolean) {
        if (simMode == SimMode.OFF) return

        val simulatedP1 = PointF(210f, 260f)
        val simulatedP2 = PointF(390f, 260f)
        val baseline = 180f

        // Kinematics: Ydriver = My - 0.85*W = 260 - 153 = 107
        // Xdriver = Mx - 0.25*W = 300 - 45 = 255
        val driverTarget = PointF(255f, 107f)

        val pan = 97
        val tilt = 52

        val beamType = if (isHighBeam) DriverTargetAnalyzer.BeamType.HIGH_BEAM else DriverTargetAnalyzer.BeamType.LOW_BEAM
        val confidence = if (isHighBeam) 0.94f else 0.18f
        val lux = if (isHighBeam) 1840.0 else 220.0
        val dist = 22.5
        val strikeActive = isHighBeam

        val nmeaStr = String.format(
            Locale.US,
            "\$HBGCS,PAN:%03d,TILT:%03d,LUX:%04d,BEAM:%s,%s*3F",
            pan, tilt, lux.roundToInt(), if (isHighBeam) "HIGH" else "LOW", if (strikeActive) "STRIKE:ACTIVE" else "STRIKE:PASSIVE"
        )

        val simTelemetry = DriverTargetAnalyzer.TelemetryData(
            fps = 60.0,
            locked = true,
            pan = pan,
            tilt = tilt,
            blobCount = 2,
            headlightLeft = simulatedP1,
            headlightRight = simulatedP2,
            driverTarget = driverTarget,
            baselineWidth = baseline,
            frameWidth = TARGET_WIDTH,
            frameHeight = TARGET_HEIGHT,
            latencyMs = 4L,
            allBlobs = listOf(simulatedP1, simulatedP2),
            beamType = beamType,
            highBeamConfidence = confidence,
            glareLuxEstimate = lux,
            estimatedDistanceMeters = dist,
            countermeasureActive = strikeActive,
            nmeaPacket = nmeaStr
        )

        binding.hudOverlayView.updateTelemetry(simTelemetry)

        if (strikeActive) {
            binding.statusBadgeText.text = "[⚡ RETALIATORY STRIKE ENGAGED ⚡]"
            binding.statusBadgeText.setTextColor(Color.parseColor("#FF1744"))
        } else {
            binding.statusBadgeText.text = "[LOW BEAM - PASSIVE TRACKING]"
            binding.statusBadgeText.setTextColor(Color.parseColor("#00E676"))
        }

        binding.fpsText.text = "FPS: 60.0 | 4ms"
        binding.telemetryStatusText.text = "LOCK: LOCKED (RHD)"
        binding.telemetryStatusText.setTextColor(Color.parseColor("#00E676"))
        binding.telemetryBlobsText.text = "BLOBS: 2 (SIM)"
        binding.telemetryBaselineText.text = "BASELINE W: 180 px"
        binding.telemetryPanText.text = "SERVO PAN:  $pan°"
        binding.telemetryTiltText.text = "SERVO TILT: $tilt°"

        binding.beamTypeText.text = if (isHighBeam) "BEAM: HIGH (94%)" else "BEAM: LOW (18%)"
        binding.beamTypeText.setTextColor(if (isHighBeam) Color.parseColor("#FF1744") else Color.parseColor("#00E676"))

        binding.glareLuxText.text = String.format(Locale.US, "GLARE: %d LUX", lux.roundToInt())

        if (strikeActive) {
            binding.countermeasureStatusText.text = "TORCH STRIKE: ACTIVE 🔥"
            binding.countermeasureStatusText.setTextColor(Color.parseColor("#FF1744"))
        } else {
            binding.countermeasureStatusText.text = "TORCH STRIKE: OFF"
            binding.countermeasureStatusText.setTextColor(Color.parseColor("#00E676"))
        }

        onTargetVectorUpdated(pan, tilt, true, strikeActive)
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        cameraExecutor.shutdown()
    }
}
