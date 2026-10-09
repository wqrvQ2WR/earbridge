package com.hayoonjae.earbridge

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 다른 네트워크용: 파이 중계 서버로 소리를 올린다.
 * 맥이 같은 코드로 듣고 있을 때만(listeners > 0) 실제로 보내서 데이터를 아낀다.
 */
class RemoteSender(
    private val code: String,
    private val onChange: (connected: Boolean, listeners: Int) -> Unit,
) {
    companion object {
        const val RELAY = "wss://pi.taild4dc8e.ts.net/earbridge"
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 헷갈리는 O/0, I/1 뺌

        fun code(ctx: Context): String {
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            prefs.getString("code", null)?.let { return it }
            val c = newCode()
            prefs.edit().putString("code", c).apply()
            return c
        }

        fun newCode(): String {
            val r = SecureRandom()
            return (1..8).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
        }
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val retry = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var listeners = 0
    @Volatile private var running = true

    fun start() = connect()

    private fun connect() {
        if (!running) return
        val req = Request.Builder().url("$RELAY/send/$code").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(Protocol.header(Protocol.SAMPLE_RATE, 1).toByteString())
                onChange(true, listeners)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.startsWith("listeners:")) {
                    listeners = text.removePrefix("listeners:").toIntOrNull() ?: 0
                    onChange(true, listeners)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)
        })
    }

    private fun lost(w: WebSocket) {
        if (w !== ws) return
        ws = null
        listeners = 0
        onChange(false, 0)
        if (running) retry.schedule({ connect() }, 3, TimeUnit.SECONDS)
    }

    fun offer(pcm: ByteArray) {
        val w = ws ?: return
        if (listeners == 0) return
        if (w.queueSize() > 64 * 1024) return // 업로드가 밀리면 버려서 지연이 쌓이지 않게
        w.send(pcm.toByteString())
    }

    fun stop() {
        running = false
        ws?.close(1000, null)
        ws = null
        retry.shutdownNow()
        client.dispatcher.executorService.shutdown()
    }
}
