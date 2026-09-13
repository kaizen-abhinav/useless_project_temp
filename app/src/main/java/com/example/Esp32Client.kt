package com.example

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ultra-Fast Asynchronous REST Client for ESP32 'HelmetTracker' SoftAP.
 *
 * Implements Layer 3 REST Protocol Specification:
 * - Target Endpoint: http://192.168.4.1/set?angle=X&light=Y
 * - Active-LOW Relay Switching Logic:
 *     - lightOn == true  => light = 0 (Energizes active-LOW optocoupled relay -> Torch ON 🔥)
 *     - lightOn == false => light = 1 (De-energizes active-LOW optocoupled relay -> Torch OFF)
 * - 15ms Low-Latency Debounce & HTTP Connection Pooling for instant 60 FPS servo tracking.
 */
class Esp32Client(
    private val hostIp: String = "192.168.4.1",
    private val port: Int = 80,
    private val statusListener: ((connected: Boolean, statusText: String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "Esp32Client"
        private const val DEBOUNCE_MS = 15L // 15ms ultra-low latency for 60 FPS tracking
    }

    private val client = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES)) // HTTP Keep-Alive socket reuse
        .connectTimeout(400, TimeUnit.MILLISECONDS)
        .readTimeout(400, TimeUnit.MILLISECONDS)
        .writeTimeout(400, TimeUnit.MILLISECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var debounceJob: Job? = null
    private val isBusy = AtomicBoolean(false)

    private var lastDispatchedAngle = -1
    private var lastDispatchedLight = -1

    /**
     * Dispatches angle [0-180] and active-LOW light state (0 = ON, 1 = OFF) to ESP32 /set REST endpoint.
     */
    fun dispatchTargetState(angle: Int, lightOn: Boolean) {
        val clampedAngle = angle.coerceIn(0, 180)

        // Inverted Active-LOW Relay Control Logic:
        // Active-LOW Optocoupler Relay: 0 = Torch ON, 1 = Torch OFF
        val lightVal = if (lightOn) 0 else 1

        // Skip duplicate state dispatches
        if (clampedAngle == lastDispatchedAngle && lightVal == lastDispatchedLight) return

        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)

            // Non-blocking execution lock to discard intermediary states if HTTP request is in-flight
            if (isBusy.compareAndSet(false, true)) {
                lastDispatchedAngle = clampedAngle
                lastDispatchedLight = lightVal

                val url = "http://$hostIp:$port/set?angle=$clampedAngle&light=$lightVal"
                val request = Request.Builder()
                    .url(url)
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        isBusy.set(false)
                        Log.e(TAG, "ESP32 SoftAP request failed: ${e.message}")
                        statusListener?.invoke(false, "ESP32 SOFTAP: DISCONNECTED")
                    }

                    override fun onResponse(call: Call, response: Response) {
                        isBusy.set(false)
                        response.use { res ->
                            val responseBody = res.body?.string() ?: ""
                            Log.d(TAG, "ESP32 Response [$url]: $responseBody")
                            statusListener?.invoke(true, "ESP32 CONNECTED: $responseBody")
                        }
                    }
                })
            }
        }
    }
}
