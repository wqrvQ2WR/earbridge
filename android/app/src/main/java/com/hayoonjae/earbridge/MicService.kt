package com.hayoonjae.earbridge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.math.sqrt

data class StreamState(
    val running: Boolean = false,
    val clients: Int = 0,
    val level: Float = 0f,
    val address: String? = null,
    val error: String? = null,
    val code: String? = null,
    val remoteOn: Boolean = false,
    val remoteConnected: Boolean = false,
    val remoteListeners: Int = 0,
)

/** 마이크 소리를 TCP로 내보내는 서비스. 맥 앱이 Bonjour(_earbridge._tcp)로 찾아서 붙는다. */
class MicService : Service() {

    companion object {
        const val PORT = 7700
        const val SERVICE_TYPE = "_earbridge._tcp"
        private const val CHANNEL = "stream"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_REMOTE = "remote"
        private const val ACTION_STOP = "stop"

        private val _state = MutableStateFlow(StreamState())
        val state: StateFlow<StreamState> = _state

        fun start(ctx: Context, source: MicSource, remote: Boolean) {
            val i = Intent(ctx, MicService::class.java).putExtra(EXTRA_SOURCE, source.name).putExtra(EXTRA_REMOTE, remote)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MicService::class.java))
        }
    }

    private class Client(val socket: Socket) {
        val queue = ArrayBlockingQueue<ByteArray>(50) // 10ms 조각 50개 = 0.5초까지만 쌓고 넘치면 버린다
    }

    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var record: AudioRecord? = null
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wifiLockScreenOff: WifiManager.WifiLock? = null
    private var remote: RemoteSender? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        val source = runCatching { MicSource.valueOf(intent?.getStringExtra(EXTRA_SOURCE) ?: "") }.getOrDefault(MicSource.RAW)

        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        running = true
        val useRemote = intent?.getBooleanExtra(EXTRA_REMOTE, false) == true
        val code = RemoteSender.code(this)
        _state.value = StreamState(running = true, address = localAddress(), code = code, remoteOn = useRemote)
        acquireLocks()
        if (useRemote) {
            remote = RemoteSender(code) { ok, n ->
                _state.update { it.copy(remoteConnected = ok, remoteListeners = n) }
            }.also { it.start() }
        }
        startServer()
        startRecording(source)
        registerNsd()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        remote?.stop()
        remote = null
        nsdListener?.let { runCatching { nsd?.unregisterService(it) } }
        runCatching { server?.close() }
        clients.forEach { runCatching { it.socket.close() } }
        clients.clear()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLockScreenOff?.let { if (it.isHeld) it.release() }
        _state.value = StreamState()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "소리 보내기", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MicService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("EarBridge")
            .setContentText("마이크 소리를 맥으로 보내는 중")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "끄기", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun acquireLocks() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "earbridge:mic").apply { acquire() }
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "earbridge:wifi").apply { acquire() }
        // LOW_LATENCY 락은 화면이 켜져 있을 때만 먹어서, 화면 꺼진 동안 와이파이가 졸지 않게 하나 더 잡는다
        @Suppress("DEPRECATION")
        wifiLockScreenOff = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "earbridge:wifi-off").apply { acquire() }
    }

    private fun startServer() {
        val ss = try {
            ServerSocket(PORT)
        } catch (e: Exception) {
            _state.update { it.copy(error = "포트 $PORT 를 열 수 없음: ${e.message}") }
            return
        }
        server = ss
        thread(name = "accept") {
            while (running) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                s.tcpNoDelay = true
                val c = Client(s)
                clients += c
                _state.update { it.copy(clients = clients.size) }
                thread(name = "send") { sendLoop(c) }
            }
        }
    }

    private fun sendLoop(c: Client) {
        try {
            val out = BufferedOutputStream(c.socket.getOutputStream(), 4096)
            out.write(Protocol.header(Protocol.SAMPLE_RATE, 1))
            out.flush()
            while (running) {
                val chunk = c.queue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                out.write(chunk)
                if (c.queue.isEmpty()) out.flush()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { c.socket.close() }
            clients -= c
            _state.update { it.copy(clients = clients.size) }
        }
    }

    @SuppressLint("MissingPermission") // 권한은 화면에서 받은 뒤에만 서비스를 켠다
    private fun startRecording(source: MicSource) {
        val rate = Protocol.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(source.androidSource(), rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate / 5 * 2))
        } catch (e: Exception) {
            _state.update { it.copy(error = "마이크를 열 수 없음: ${e.message}") }
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            _state.update { it.copy(error = "마이크를 열 수 없음") }
            rec.release()
            return
        }
        record = rec
        thread(name = "record", priority = Thread.MAX_PRIORITY) {
            val frame = ShortArray(rate / 100) // 10ms
            var meterTick = 0
            var peak = 0f
            rec.startRecording()
            try {
                while (running) {
                    val n = rec.read(frame, 0, frame.size)
                    if (n <= 0) continue
                    val bytes = Protocol.toLittleEndian(frame, n)
                    for (c in clients) {
                        if (!c.queue.offer(bytes)) { c.queue.poll(); c.queue.offer(bytes) }
                    }
                    remote?.offer(bytes)
                    peak = maxOf(peak, rms(frame, n))
                    if (++meterTick >= 5) { // 50ms마다 화면 막대 갱신
                        val p = peak
                        _state.update { it.copy(level = p) }
                        meterTick = 0
                        peak = 0f
                    }
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }
    }

    private fun rms(a: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) { val v = a[i] / 32768.0; sum += v * v }
        return sqrt(sum / n).toFloat()
    }

    private fun registerNsd() {
        val info = NsdServiceInfo().apply {
            serviceName = "EarBridge ${Build.MODEL}"
            serviceType = SERVICE_TYPE
            port = PORT
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        nsd = getSystemService(NsdManager::class.java)
        nsdListener = l
        runCatching { nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    private fun localAddress(): String? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }
}

enum class MicSource(val label: String) {
    RAW("원음"),
    VOICE("잡음 줄임");

    fun androidSource(): Int = when (this) {
        RAW -> MediaRecorder.AudioSource.MIC
        VOICE -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
    }
}
