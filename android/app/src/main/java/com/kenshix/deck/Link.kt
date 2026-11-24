package com.kenshix.deck

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private const val USB = "127.0.0.1"
private const val PORT = 43851
private const val MAX_MESSAGE = 64 * 1024

internal fun message(type: String) = JSONObject().put("type", type)

private class PinnedCertificate(fingerprint: String) : X509TrustManager {
    private val expected = fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun verify(certificate: X509Certificate) {
        certificate.checkValidity()
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        if (!MessageDigest.isEqual(expected, fingerprint)) throw CertificateException("KenshixDeck sertifikası eşleşmiyor")
        certificate.verify(certificate.publicKey)
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("İstemci sertifikası kabul edilmiyor")
    }
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        verify(chain.singleOrNull() ?: throw CertificateException("Tek PC sertifikası bekleniyor"))
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun client(fingerprint: String): OkHttpClient {
    val pin = PinnedCertificate(fingerprint)
    val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(pin), null) }
    return OkHttpClient.Builder()
        .sslSocketFactory(tls.socketFactory, pin)
        .hostnameVerifier { _, session ->
            // Both IP transports use the same identity. Verify the full certificate on each.
            runCatching { pin.verify(session.peerCertificates.single() as X509Certificate) }.isSuccess
        }
        .connectTimeout(900, TimeUnit.MILLISECONDS)
        .build()
}

private class Connection(client: OkHttpClient, address: String) {
    val usb = address == USB
    private sealed interface Event {
        data class Text(val text: String) : Event
        data object Closed : Event
    }
    private val events = ArrayBlockingQueue<Event>(4)
    private val socket = client.newWebSocket(Request.Builder().url("wss://$address:$PORT/v1").build(),
        object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > MAX_MESSAGE || !events.offer(Event.Text(text))) webSocket.cancel()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events.offer(Event.Closed)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.offer(Event.Closed)
            }
        })

    fun exchange(request: JSONObject, timeout: Long): JSONObject {
        val payload = request.toString()
        check(payload.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE)
        check(socket.send(payload))
        val event = events.poll(timeout, TimeUnit.MILLISECONDS)
        check(event is Event.Text)
        return JSONObject(event.text)
    }

    fun close() = socket.cancel()
}

internal class DeckLink(
    private val credentials: Credentials,
    private val update: (String, JSONObject?) -> Unit,
    private val report: (String) -> Unit
) {
    private val worker = ScheduledThreadPoolExecutor(1)
    private var identity = runCatching { credentials.load() }.getOrNull()
    private var client = identity?.let { client(it.fingerprint) }
    @Volatile private var connection: Connection? = null
    @Volatile private var paused = false
    private var nextAttempt = 0L
    private var failures = 0
    private var nextUsbProbe = 0L
    private var deckRevision = -1L
    private var lastAlert: String? = null

    init {
        worker.scheduleWithFixedDelay({ runCatching { tick() }.onFailure { lost() } }, 0, 430, TimeUnit.MILLISECONDS)
    }

    fun live() = connection != null && !paused

    fun request(request: JSONObject, onResult: (JSONObject?) -> Unit) {
        val current = connection
        if (current == null || paused) {
            onResult(null)
            return
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + 1500
        worker.execute {
            if (connection !== current || paused || android.os.SystemClock.elapsedRealtime() > deadline) {
                onResult(null)
                return@execute
            }
            val response = runCatching { current.exchange(request, 2200) }.getOrNull()
            if (response == null) lost()
            onResult(response)
        }
    }

    fun pair(pairing: String, lan: String) {
        val parts = pairing.trim().split('.')
        if (parts.size != 2 || !parts[0].matches(Regex("[a-f0-9]{64}")) ||
            !parts[1].matches(Regex("[A-Za-z0-9_-]{32}")) || !validLan(lan)) {
            report("Eşleştirme kodu veya yerel adres geçersiz.")
            return
        }
        worker.execute {
            val pinned = client(parts[0])
            val response = addresses(lan).firstNotNullOfOrNull { address ->
                val attempt = Connection(pinned, address)
                try {
                    runCatching {
                        attempt.exchange(message("pair").put("version", 1).put("code", parts[1]), 1600)
                    }.getOrNull()
                } finally {
                    attempt.close()
                }
            }
            if (response?.optString("type") != "paired") {
                pinned.dispatcher.executorService.shutdown()
                report("Eşleştirme tamamlanamadı. USB bağlantısını ve beş dakikalık kodu kontrol et.")
                return@execute
            }
            val replacement = Identity(parts[0], response.getString("token"), lan)
            if (runCatching { credentials.save(replacement) }.isFailure) {
                pinned.dispatcher.executorService.shutdown()
                report("Telefon eşleştirme anahtarını saklayamadı.")
                return@execute
            }
            lost()
            client?.dispatcher?.executorService?.shutdown()
            identity = replacement
            client = pinned
            deckRevision = -1
            nextAttempt = 0
            failures = 0
            report("Telefon eşleştirildi.")
        }
    }

    fun forget() {
        worker.execute {
            runCatching { credentials.forget() }.onFailure {
                report("Telefon anahtarı silinemedi.")
                return@execute
            }
            identity = null
            lost()
            client?.dispatcher?.executorService?.shutdown()
            client = null
        }
    }

    fun pause(value: Boolean) {
        paused = value
        if (value) worker.execute { lost() }
    }

    fun close() {
        paused = true
        worker.execute {
            connection?.close()
            client?.dispatcher?.executorService?.shutdown()
            worker.shutdown()
        }
    }

    private fun addresses(lan: String) = listOf(USB, lan).filter(String::isNotEmpty)

    private fun greet(address: String): Pair<Connection, JSONObject>? {
        val pinned = client ?: return null
        val record = identity ?: return null
        val attempt = Connection(pinned, address)
        val response = runCatching {
            attempt.exchange(message("hello").put("version", 1).put("token", record.token), 1000)
        }.getOrNull()
        if (response?.optString("type") == "snapshot") return attempt to response
        attempt.close()
        if (response?.optString("code") == "unauthorized") update("Eşleştirme gerekli", null)
        return null
    }

    private fun tick() {
        if (paused) return
        val record = identity ?: return
        val time = android.os.SystemClock.elapsedRealtime()
        val current = connection
        if (current == null) {
            if (time < nextAttempt) return
            val online = addresses(record.lan).firstNotNullOfOrNull(::greet)
            if (online == null) {
                failures = (failures + 1).coerceAtMost(3)
                nextAttempt = time + (400L shl failures)
                return
            }
            connected(online)
            return
        }

        if (!current.usb && time >= nextUsbProbe) {
            nextUsbProbe = time + 3000
            val usb = greet(USB)
            if (usb != null) {
                current.close()
                connected(usb)
                return
            }
        }
        val response = current.exchange(message("snapshot"), 1300)
        check(response.optString("type") == "snapshot")
        snapshot(current, response)
    }

    private fun connected(online: Pair<Connection, JSONObject>) {
        connection = online.first
        failures = 0
        deckRevision = -1
        nextUsbProbe = android.os.SystemClock.elapsedRealtime() + 3000
        snapshot(online.first, online.second)
    }

    private fun snapshot(current: Connection, response: JSONObject) {
        val deck = response.getJSONObject("deck")
        val alert = response.optString("alert").takeUnless { it.isBlank() || it == "null" }
        if (alert != null && lastAlert != null && alert != lastAlert) report(alert)
        lastAlert = alert ?: ""
        val revision = deck.getLong("revision")
        if (revision != deckRevision) {
            deckRevision = revision
            update(if (current.usb) "USB" else "Wi-Fi", deck)
        }
    }

    private fun lost() {
        connection?.close()
        connection = null
        update(if (identity == null) "Eşleştirme gerekli" else "Bağlantı bekleniyor", null)
    }
}
