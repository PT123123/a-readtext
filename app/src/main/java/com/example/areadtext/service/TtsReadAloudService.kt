package com.example.areadtext.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.areadtext.R
import com.example.areadtext.reader.ReadState
import com.example.areadtext.reader.TtsCommand
import com.example.areadtext.reader.TtsEventBus
import com.example.areadtext.reader.TtsReadAloudEngine
import com.example.areadtext.ui.ReaderActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 前台朗读服务（MoRealm TtsService 的轻量版）。
 *
 * 朗读循环常驻服务内（TtsReadAloudEngine），退出阅读器后：
 *  - 通知栏 MediaStyle 提供 上一段 / 播放暂停 / 下一段 / 停止；
 *  - 音频焦点被抢占时自动暂停；拔耳机自动暂停；
 *  - 播放期间持有 PARTIAL_WAKE_LOCK，长时听书不被 CPU 休眠打断。
 *
 * UI 与通知按钮都不直接调服务，而是发 [TtsCommand] 到 [TtsEventBus]。
 */
class TtsReadAloudService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: TtsReadAloudEngine? = null
    private var stateJob: Job? = null

    private lateinit var audioManager: AudioManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var focusRequested = false

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()
        // 拔耳机/断开音频输出 → 暂停
        val noisyFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(noisyReceiver, noisyFilter)

        val engine = TtsReadAloudEngine(applicationContext)
        this.engine = engine
        engine.start()

        // 状态驱动通知 + 唤醒锁
        stateJob = scope.launch {
            TtsEventBus.state.collectLatest { state ->
                updateNotification(state)
                if (state.isPlaying) acquireWakeLock() else releaseWakeLock()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        // 通知栏按钮：把 intent 里携带的命令名转发给总线（引擎在服务内消费）
        if (intent?.action == ACTION_COMMAND) {
            val name = intent.getStringExtra(EXTRA_COMMAND)
            commandOf(name)?.let { TtsEventBus.send(it) }
            if (name == "StopService") stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(noisyReceiver) }
        engine?.release()
        engine = null
        stateJob?.cancel()
        releaseWakeLock()
        scope.coroutineContext.cancel()
    }

    // ── 前台化 + 通知 ────────────────────────────────────────────────────────

    private fun startAsForeground() {
        val notification = buildNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else 0,
        )
    }

    private fun buildNotification(): Notification {
        val state = TtsEventBus.snapshot()
        val contentTitle = state.bookTitle.ifBlank { getString(R.string.app_name) }
        val chapter = state.chapterTitle
        val text = if (chapter.isNotBlank()) {
            "${chapter} · 第 ${state.paragraphIndex + 1}/${state.totalParagraphs} 段"
        } else {
            getString(R.string.notification_ready)
        }
        val playPause = if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play

        val openIntent = Intent(this, ReaderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reader)
            .setContentTitle(contentTitle)
            .setContentText(text)
            .setContentIntent(openPi)
            .setOngoing(state.isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(R.drawable.ic_prev_para, getString(R.string.prev_paragraph), actionPi(TtsCommand.PrevParagraph))
            .addAction(playPause, getString(if (state.isPlaying) R.string.pause else R.string.play), actionPi(if (state.isPlaying) TtsCommand.Pause else TtsCommand.Resume))
            .addAction(R.drawable.ic_next_para, getString(R.string.next_paragraph), actionPi(TtsCommand.NextParagraph))
            .addAction(R.drawable.ic_stop, getString(R.string.stop), actionPi(TtsCommand.StopService))
            .build()
    }

    private fun actionPi(command: TtsCommand): PendingIntent {
        val intent = Intent(this, TtsReadAloudService::class.java).apply {
            action = ACTION_COMMAND
            putExtra(EXTRA_COMMAND, command::class.java.simpleName)
        }
        return PendingIntent.getService(
            this, command.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun updateNotification(state: ReadState) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun commandOf(name: String?): TtsCommand? = when (name) {
        "PrevParagraph" -> TtsCommand.PrevParagraph
        "NextParagraph" -> TtsCommand.NextParagraph
        "Pause" -> TtsCommand.Pause
        "Resume" -> TtsCommand.Resume
        "Play" -> TtsCommand.Play
        "StopService" -> TtsCommand.StopService
        else -> null
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_read),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_read_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    // ── 音频焦点（MoRealm：音频被抢占自动暂停）──────────────────────────────

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (TtsEventBus.snapshot().isPlaying) {
                TtsEventBus.send(TtsCommand.Pause)
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (TtsEventBus.snapshot().isPlaying.not()) {
                // 不自动恢复，避免打断用户其它操作
            }
        }
    }

    private fun requestAudioFocus() {
        if (focusRequested) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            audioManager.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        focusRequested = true
    }

    private fun abandonAudioFocus() {
        if (!focusRequested) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                audioManager.abandonAudioFocus(focusChangeListener)
            }
        }
        focusRequested = false
    }

    // ── 唤醒锁 ──────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "a-readtext::readAloud",
            ).apply { setReferenceCounted(false) }
        }
        wakeLock?.acquire()
        requestAudioFocus()
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        abandonAudioFocus()
    }

    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                if (TtsEventBus.snapshot().isPlaying) TtsEventBus.send(TtsCommand.Pause)
            }
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1024
        private const val CHANNEL_ID = "read_aloud"
        private const val ACTION_COMMAND = "com.example.areadtext.action.TTS_COMMAND"
        private const val EXTRA_COMMAND = "command"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TtsReadAloudService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TtsReadAloudService::class.java))
        }
    }
}
