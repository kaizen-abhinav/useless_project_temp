package com.example

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * High-Speed Non-Blocking Asynchronous REST Client for ESP32 'HelmetTracker' SoftAP.
 *
 * Implements Layer 3 REST Protocol Specification:
 * - Target Endpoint: http://192.168.4.1/set?angle=X&light=Y
 * - Query Parameters:
 *     - angle: [0-180] Clamped Pan Servo Angle
 *     - light: [0|1] (1 = High Beam Torch ON, 0 = Torch OFF)
 * - Automatic Wi-Fi Socket Binding via Android ConnectivityManager.
 * - Non-blocking execution lock & 50ms rate limiter to prevent ESP32 HTTP daemon starvation.
 */
class Esp32Client(
    context: Context,
    private val hostIp: String = "192.168.4.1",
    private val port: Int = 80,
    private val statusListener: ((connected: Boolean, statusText: String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "Esp32Client"
        private const val RATE_LIMIT_MS = 50L
    }

    private var okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val isBusy = AtomicBoolean(false)

    private var lastDispatchedAngle = -1
    private var lastDispatchedLight = -1
    private var lastDispatchTime = 0L

    init {
        // Bind OkHttp directly to the Wi-Fi network interface (bypasses mobile data default route)
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager != null) {
                val networkRequest = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()

                connectivityManager.registerNetworkCallback(networkRequest, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        okHttpClient = okHttpClient.newBuilder()
                            .socketFactory(network.socketFactory)
                            .build()
                        Log.d(TAG, "Bound OkHttp client directly to Wi-Fi Network interface.")
                    }

                    override fun onLost(network: Network) {
                        Log.w(TAG, "Wi-Fi Network interface lost.")
                        statusListener?.invoke(false, "ESP32 SOFTAP: DISCONNECTED")
                    }
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering Wi-Fi network callback", e)
        }
    }

    /**
     * Dispatches angle [0-180] and light state (1 = ON, 0 = OFF) to ESP32 /set REST endpoint.
     */
    fun dispatchTargetState(angle: Int, lightOn: Boolean) {
        val clampedAngle = angle.coerceIn(0, 180)
        val lightVal = if (lightOn) 1 else 0

        val now = System.currentTimeMillis()
        if (clampedAngle == lastDispatchedAngle && lightVal == lastDispatchedLight) return

        // Throttle request rate to 50ms to match ESP32 synchronous HTTP daemon
        if (now - lastDispatchTime < RATE_LIMIT_MS && lightVal == lastDispatchedLight) return

        scope.launch {
            if (isBusy.compareAndSet(false, true)) {
                lastDispatchTime = System.currentTimeMillis()

                val url = "http://$hostIp:$port/set?angle=$clampedAngle&light=$lightVal"
                val request = Request.Builder()
                    .url(url)
                    .build()

                okHttpClient.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        isBusy.set(false)
                        Log.e(TAG, "ESP32 SoftAP request failed [$url]: ${e.message}")
                        statusListener?.invoke(false, "CONNECT TO 'HelmetTracker' WI-FI")
                    }

                    override fun onResponse(call: Call, response: Response) {
                        isBusy.set(false)
                        response.use { res ->
                            if (res.isSuccessful) {
                                lastDispatchedAngle = clampedAngle
                                lastDispatchedLight = lightVal
                                val responseBody = res.body?.string() ?: ""
                                Log.d(TAG, "ESP32 Response [$url]: $responseBody")
                                statusListener?.invoke(true, "ESP32 CONNECTED: $responseBody")
                            } else {
                                Log.w(TAG, "ESP32 HTTP Error ${res.code} [$url]")
                            }
                        }
                    }
                })
            }
        }
    }
}
