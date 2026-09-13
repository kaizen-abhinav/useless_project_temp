package com.example

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale

/**
 * Embedded Lightweight Teleop WebServer for Laptop Remote Control Demo.
 *
 * Runs on Port 8080.
 * Serves a tactical HTML/CSS/JS Single-Page Application (SPA) dashboard.
 * Parses REST endpoint: GET /teleop?angle=[0-180]&light=[0|1]&override=[0|1]
 */
class TeleopServer(
    private val context: Context,
    private val port: Int = 8080,
    private val commandListener: (angle: Int, lightOn: Boolean, overrideActive: Boolean) -> Unit
) {

    companion object {
        private const val TAG = "TeleopServer"
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true

        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                Log.d(TAG, "Teleop Embedded WebServer started on port $port")

                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    scope.launch { handleClientSocket(socket) }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Teleop Server error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Teleop Server", e)
        }
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is InetAddress) {
                        val ip = addr.hostAddress ?: ""
                        if (!ip.contains(":")) { // IPv4
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address", e)
        }
        return "192.168.4.2"
    }

    private fun handleClientSocket(socket: Socket) {
        try {
            socket.soTimeout = 2000
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val writer = PrintWriter(socket.outputStream)

            val requestLine = reader.readLine() ?: return
            Log.d(TAG, "Teleop Request: $requestLine")

            when {
                requestLine.contains("GET /teleop") -> {
                    // Parse query parameters
                    val angle = parseQueryParam(requestLine, "angle", 90).coerceIn(0, 180)
                    val lightVal = parseQueryParam(requestLine, "light", 0)
                    val overrideVal = parseQueryParam(requestLine, "override", 1)

                    val lightOn = lightVal == 1
                    val overrideActive = overrideVal == 1

                    commandListener.invoke(angle, lightOn, overrideActive)

                    val json = String.format(
                        Locale.US,
                        "{\"status\":\"ok\",\"angle\":%d,\"light\":%d,\"override\":%b}",
                        angle, lightVal, overrideActive
                    )

                    writer.println("HTTP/1.1 200 OK")
                    writer.println("Content-Type: application/json")
                    writer.println("Access-Control-Allow-Origin: *")
                    writer.println("Content-Length: ${json.length}")
                    writer.println()
                    writer.print(json)
                    writer.flush()
                }

                requestLine.contains("GET /status") -> {
                    val json = "{\"server\":\"HBGCS Teleop\",\"status\":\"running\"}"
                    writer.println("HTTP/1.1 200 OK")
                    writer.println("Content-Type: application/json")
                    writer.println("Access-Control-Allow-Origin: *")
                    writer.println("Content-Length: ${json.length}")
                    writer.println()
                    writer.print(json)
                    writer.flush()
                }

                else -> {
                    // Serve Teleop Dashboard SPA
                    val html = getDashboardHtml()
                    writer.println("HTTP/1.1 200 OK")
                    writer.println("Content-Type: text/html")
                    writer.println("Access-Control-Allow-Origin: *")
                    writer.println("Content-Length: ${html.toByteArray().size}")
                    writer.println()
                    writer.print(html)
                    writer.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client socket", e)
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {}
        }
    }

    private fun parseQueryParam(line: String, paramName: String, defaultValue: Int): Int {
        try {
            val key = "$paramName="
            val startIndex = line.indexOf(key)
            if (startIndex != -1) {
                val valueStart = startIndex + key.length
                var valueEnd = line.indexOf('&', valueStart)
                if (valueEnd == -1) {
                    valueEnd = line.indexOf(' ', valueStart)
                }
                if (valueEnd == -1) {
                    valueEnd = line.length
                }
                val subStr = line.substring(valueStart, valueEnd)
                return subStr.toInt()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing query param $paramName", e)
        }
        return defaultValue
    }

    private fun getDashboardHtml(): String {
        return """
<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>HBGCS Teleop Remote Control</title>
    <style>
        body { background: #080d1a; color: #00e5ff; font-family: monospace; padding: 20px; text-align: center; }
        .card { background: #0f172a; border: 2px solid #00e5ff; border-radius: 12px; padding: 24px; max-width: 520px; margin: 0 auto; box-shadow: 0 0 25px rgba(0,229,255,0.25); }
        h2 { color: #ff1744; margin-top: 0; text-transform: uppercase; letter-spacing: 1px; }
        .btn { background: #00e5ff; color: #080d1a; border: none; font-weight: bold; font-family: monospace; padding: 12px 22px; font-size: 15px; border-radius: 6px; cursor: pointer; margin: 6px; transition: all 0.2s; }
        .btn:hover { transform: scale(1.05); }
        .btn-strike { background: #ff1744; color: #fff; box-shadow: 0 0 15px rgba(255,23,68,0.5); }
        .btn-off { background: #334155; color: #94a3b8; }
        .slider { width: 95%; margin: 20px 0; height: 14px; accent-color: #00e5ff; cursor: pointer; }
        .badge { display: inline-block; padding: 6px 16px; border-radius: 20px; font-weight: bold; background: #ff1744; color: #fff; margin-bottom: 18px; font-size: 14px; }
        .val-text { font-size: 28px; color: #00e676; font-weight: bold; }
    </style>
</head>
<body>
    <div class="card">
        <h2>⚡ HBGCS TELEOP CONTROL ⚡</h2>
        <div id="status-badge" class="badge">OVERRIDE ACTIVE</div>
        
        <p>PAN SERVO AZIMUTH</p>
        <div class="val-text"><span id="angle-val">90</span>°</div>
        <input type="range" min="0" max="180" value="90" class="slider" id="angle-slider" oninput="updateAngle(this.value)">
        
        <div>
            <button class="btn" onclick="setAngle(0)">0° (LEFT)</button>
            <button class="btn" onclick="setAngle(90)">90° (CENTER)</button>
            <button class="btn" onclick="setAngle(180)">180° (RIGHT)</button>
        </div>

        <h3 style="margin-top:28px;">RETALIATORY TORCH POWER</h3>
        <button id="torch-btn" class="btn btn-off" onclick="toggleTorch()">FIRE TORCH (OFF)</button>

        <div style="margin-top:30px; border-top: 1px solid #1e293b; padding-top: 20px;">
            <button id="override-btn" class="btn btn-strike" onclick="toggleOverride()">RELEASE TO AUTO CV</button>
        </div>
    </div>

    <script>
        let currentAngle = 90;
        let torchOn = false;
        let overrideOn = true;
        let dispatchTimer = null;

        function sendTeleop() {
            clearTimeout(dispatchTimer);
            dispatchTimer = setTimeout(() => {
                fetch('/teleop?angle=' + currentAngle + '&light=' + (torchOn ? 1 : 0) + '&override=' + (overrideOn ? 1 : 0))
                    .catch(e => console.error(e));
            }, 25);
        }

        function updateAngle(val) {
            currentAngle = parseInt(val);
            document.getElementById('angle-val').innerText = currentAngle;
            sendTeleop();
        }

        function setAngle(val) {
            currentAngle = val;
            document.getElementById('angle-slider').value = val;
            document.getElementById('angle-val').innerText = val;
            sendTeleop();
        }

        function toggleTorch() {
            torchOn = !torchOn;
            const btn = document.getElementById('torch-btn');
            if (torchOn) {
                btn.className = 'btn btn-strike';
                btn.innerText = 'TORCH ACTIVE (ON 🔥)';
            } else {
                btn.className = 'btn btn-off';
                btn.innerText = 'FIRE TORCH (OFF)';
            }
            sendTeleop();
        }

        function toggleOverride() {
            overrideOn = !overrideOn;
            const btn = document.getElementById('override-btn');
            const badge = document.getElementById('status-badge');
            if (overrideOn) {
                btn.className = 'btn btn-strike';
                btn.innerText = 'RELEASE TO AUTO CV';
                badge.style.background = '#ff1744';
                badge.innerText = 'OVERRIDE ACTIVE';
            } else {
                btn.className = 'btn';
                btn.innerText = 'TAKE TELEOP CONTROL';
                badge.style.background = '#00e676';
                badge.innerText = 'AUTO CV MODE';
            }
            sendTeleop();
        }
    </script>
</body>
</html>
        """.trimIndent()
    }
}
