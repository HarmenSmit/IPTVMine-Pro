package com.samyak.iptvminepro.provider

import android.content.Context
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.samyak.iptvminepro.model.Provider
import com.samyak.iptvminepro.model.ProviderType
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ProviderPairingServer(
    private val context: Context,
    private val onCodeUpdated: (String) -> Unit,
    private val onTimeUpdated: (Int) -> Unit,
    private val onPairingSuccess: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var timerJob: Job? = null
    private var timerScope: CoroutineScope? = null
    private var serverScope: CoroutineScope? = null
    private val repository = ProviderRepository(context)
    private val gson = Gson()
    private var currentCode = ""
    private var nsdPublisher: NsdPublisher? = null
    private val secureRandom = SecureRandom()
    // Rate-limiting: track consecutive failed code attempts per remote IP
    private val failedAttempts = ConcurrentHashMap<String, AtomicInteger>()
    private val lockedUntil = ConcurrentHashMap<String, Long>()

    init {
        nsdPublisher = NsdPublisher(context)
    }

    fun start() {
        // Prevent double-start (e.g. screen rotation)
        if (serverSocket != null) return

        // Start code generator timer
        val tScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        timerScope = tScope
        timerJob = tScope.launch {
            while (isActive) {
                generateNewCode()
                for (secondsLeft in 30 downTo 0) {
                    onTimeUpdated(secondsLeft)
                    delay(1000)
                }
            }
        }

        // Start server socket
        val sScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        serverScope = sScope
        serverJob = sScope.launch {
            try {
                val socket = ServerSocket(0) // dynamic port
                serverSocket = socket
                val port = socket.localPort

                withContext(Dispatchers.Main) {
                    nsdPublisher?.registerService(port, "IPTVMinePro-${Build.MODEL}")
                }

                while (isActive) {
                    val clientSocket = socket.accept()
                    launch {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun generateNewCode() {
        // BUG-017 fix: use SecureRandom for cryptographically unpredictable codes
        val num = secureRandom.nextInt(9000000) + 1000000 // Ensure 7 digits
        currentCode = num.toString()
        onCodeUpdated(currentCode)
    }

    private fun handleClient(client: Socket) {
        try {
            val remoteIp = client.inetAddress?.hostAddress ?: "unknown"
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val out: OutputStream = client.getOutputStream()

            // BUG-001 fix: rate-limit failed attempts per remote IP
            val lockUntil = lockedUntil[remoteIp] ?: 0L
            if (System.currentTimeMillis() < lockUntil) {
                val errorJson = "{\"error\":\"Too many failed attempts. Try again later.\"}"
                val response = "HTTP/1.1 429 Too Many Requests\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${errorJson.toByteArray().size}\r\n" +
                        "Connection: close\r\n\r\n" + errorJson
                out.write(response.toByteArray())
                out.flush()
                return
            }

            val requestLine = reader.readLine() ?: return
            // Format is: GET /pair?code=XXXXXXX&device=XXXX HTTP/1.1
            if (requestLine.startsWith("GET /pair")) {
                val urlParts = requestLine.split(" ")
                if (urlParts.size >= 2) {
                    val path = urlParts[1]
                    val params = parseQueryParams(path)
                    val code = params["code"]
                    val device = params["device"] ?: "Unknown TV"

                    if (code != null && code == currentCode) {
                        // Success — reset failure counter
                        failedAttempts.remove(remoteIp)
                        lockedUntil.remove(remoteIp)
                        val providers = repository.getProviders().filter { it.isActive }
                        val json = gson.toJson(providers)

                        val response = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${json.toByteArray().size}\r\n" +
                                "Connection: close\r\n\r\n" +
                                json

                        out.write(response.toByteArray())
                        out.flush()

                        // Notify UI on main thread
                        timerScope?.launch(Dispatchers.Main) {
                            onPairingSuccess(device)
                        }
                    } else {
                        // Invalid code — track and potentially lock
                        val attempts = failedAttempts.getOrPut(remoteIp) { AtomicInteger(0) }
                        val count = attempts.incrementAndGet()
                        if (count >= 3) {
                            // Lock this IP for 60 seconds after 3 consecutive failures
                            lockedUntil[remoteIp] = System.currentTimeMillis() + 60_000L
                            failedAttempts.remove(remoteIp)
                            Log.w("PairingServer", "IP $remoteIp locked out after $count failed attempts")
                        }
                        val errorJson = "{\"error\":\"Invalid pairing code\"}"
                        val response = "HTTP/1.1 401 Unauthorized\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${errorJson.toByteArray().size}\r\n" +
                                "Connection: close\r\n\r\n" +
                                errorJson
                        out.write(response.toByteArray())
                        out.flush()
                    }
                }
            } else {
                // Not found
                val response = "HTTP/1.1 404 Not Found\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(response.toByteArray())
                out.flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun parseQueryParams(path: String): Map<String, String> {
        val queryIndex = path.indexOf('?')
        if (queryIndex == -1 || queryIndex >= path.length - 1) return emptyMap()
        val query = path.substring(queryIndex + 1)
        val params = mutableMapOf<String, String>()
        query.split('&').forEach { param ->
            val pair = param.split('=', limit = 2)
            if (pair.size == 2) {
                // BUG-009 fix: URL-decode parameter values
                try {
                    params[pair[0]] = URLDecoder.decode(pair[1], "UTF-8")
                } catch (e: Exception) {
                    params[pair[0]] = pair[1]
                }
            }
        }
        return params
    }

    fun stop() {
        timerJob?.cancel()
        serverJob?.cancel()
        timerScope?.cancel()
        serverScope?.cancel()
        timerScope = null
        serverScope = null
        nsdPublisher?.unregisterService()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverSocket = null
        failedAttempts.clear()
        lockedUntil.clear()
    }
}
