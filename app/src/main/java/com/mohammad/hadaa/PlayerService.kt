package com.mohammad.hadaa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat

/**
 * Der eigentliche Fix: Die Wiedergabe lebt in einem Foreground Service mit
 * - dauerhafter Benachrichtigung (Android darf den Prozess dann nicht beenden)
 * - foregroundServiceType="mediaPlayback"
 * - PARTIAL_WAKE_LOCK, damit die CPU bei ausgeschaltetem Bildschirm weiterrechnet
 * - START_STICKY, damit der Dienst im Notfall neu gestartet wird
 *
 * هذا هو الإصلاح الحقيقي: التشغيل يعيش داخل خدمة أمامية بإشعار دائم،
 * مع قفل استيقاظ للمعالج حتى لا يتوقف الصوت عند إطفاء الشاشة.
 */
class PlayerService : Service(), AudioManager.OnAudioFocusChangeListener {

    companion object {
        const val CHANNEL_ID = "hadaa_playback"
        const val NOTIF_ID = 4711

        const val ACTION_START = "com.mohammad.hadaa.START"
        const val ACTION_STOP = "com.mohammad.hadaa.STOP"
        const val ACTION_SET_PRESET = "com.mohammad.hadaa.SET_PRESET"
        const val ACTION_SET_VOLUME = "com.mohammad.hadaa.SET_VOLUME"
        const val ACTION_SET_LEVEL = "com.mohammad.hadaa.SET_LEVEL"

        const val EXTRA_PRESET = "preset"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_LEVEL = "level"

        /** Broadcast an die UI / بث إلى الواجهة */
        const val BROADCAST_STATE = "com.mohammad.hadaa.STATE"
        const val EXTRA_PLAYING = "playing"

        @Volatile var isPlaying: Boolean = false
            private set
        @Volatile var activePresetId: String = SoundPresets.all[0].id
            private set
    }

    private val engine = NoiseEngine()
    private var session: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        createSession()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        engine.setPreset(SoundPresets.byId(prefs.getString(Prefs.KEY_PRESET, null)))
        engine.setVolume(prefs.getInt(Prefs.KEY_VOLUME, 65))
        engine.setLevel(prefs.getInt(Prefs.KEY_LEVEL, 1))
        activePresetId = engine.currentPreset().id
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_PRESET -> {
                val p = SoundPresets.byId(intent.getStringExtra(EXTRA_PRESET))
                engine.setPreset(p)
                activePresetId = p.id
                if (isPlaying) { updateSession(); updateNotification() }
            }
            ACTION_SET_VOLUME -> engine.setVolume(intent.getIntExtra(EXTRA_VOLUME, 65))
            ACTION_SET_LEVEL -> {
                engine.setLevel(intent.getIntExtra(EXTRA_LEVEL, 1))
                if (isPlaying) { updateSession(); updateNotification() }
            }
            ACTION_STOP -> {
                // Android verlangt startForeground() nach startForegroundService(),
                // auch wenn wir sofort wieder aufhören.
                if (!isPlaying) startForegroundCompat()
                stopPlayback()
                return START_NOT_STICKY
            }
            else -> {
                intent?.getStringExtra(EXTRA_PRESET)?.let {
                    val p = SoundPresets.byId(it)
                    engine.setPreset(p)
                    activePresetId = p.id
                }
                if (intent?.hasExtra(EXTRA_VOLUME) == true) {
                    engine.setVolume(intent.getIntExtra(EXTRA_VOLUME, 65))
                }
                if (intent?.hasExtra(EXTRA_LEVEL) == true) {
                    engine.setLevel(intent.getIntExtra(EXTRA_LEVEL, 1))
                }
                startPlayback()
            }
        }
        // START_STICKY: Sollte das System den Dienst doch beenden, wird er neu gestartet.
        return START_STICKY
    }

    private fun startPlayback() {
        if (isPlaying) {
            updateNotification()
            return
        }

        startForegroundCompat()

        if (!requestFocus()) {
            // Trotzdem spielen – der Nutzer hat es ausdrücklich gestartet.
        }

        acquireWakeLock()
        engine.start()
        isPlaying = true
        updateSession()
        updateNotification()
        broadcastState()
    }

    private fun stopPlayback() {
        engine.stop()
        isPlaying = false
        updateSession()
        abandonFocus()
        releaseWakeLock()
        broadcastState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- Media Session ----------
    // Dadurch erscheint das Wiedergabefenster auf dem Sperrbildschirm.
    // بفضلها تظهر نافذة التشغيل على شاشة القفل مع زر الإيقاف.
    private fun createSession() {
        if (session != null) return
        session = MediaSessionCompat(this, "hadaa").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = startPlayback()
                override fun onPause() = stopPlayback()
                override fun onStop() = stopPlayback()
            })
            isActive = true
        }
    }

    private fun updateSession() {
        val s = session ?: return
        val preset = engine.currentPreset()
        s.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(
                    MediaMetadataCompat.METADATA_KEY_TITLE,
                    "${preset.emoji}  ${getString(preset.nameRes)}"
                )
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getString(R.string.notif_text))
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, getString(R.string.app_name))
                .build()
        )
        s.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_PAUSE)
                .setState(
                    if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_STOPPED,
                    0L, 1f
                )
                .build()
        )
        s.isActive = isPlaying
    }

    // ---------- Wake Lock ----------
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hadaa:playback").apply {
            setReferenceCounted(false)
            acquire()   // ohne Timeout – wird beim Stoppen freigegeben
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    // ---------- Audio Focus ----------
    private fun requestFocus(): Boolean {
        val am = audioManager ?: return false
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(this)
            .build()
        focusRequest = req
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    override fun onAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> engine.setDuck(true)
            AudioManager.AUDIOFOCUS_GAIN -> engine.setDuck(false)
            AudioManager.AUDIOFOCUS_LOSS -> stopPlayback()
        }
    }

    // ---------- Notification ----------
    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val preset = engine.currentPreset()

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PlayerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${preset.emoji}  ${getString(preset.nameRes)}")
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            // زر الإيقاف الكبير في الشريط المنسدل
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_stop, getString(R.string.action_stop), stop
                ).build()
            )
            // MediaStyle يُظهر الزر أيضًا في الحالة المطوية وعلى شاشة القفل
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .setDeleteIntent(stop)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundCompat() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification())
    }

    private fun broadcastState() {
        sendBroadcast(
            Intent(BROADCAST_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_PLAYING, isPlaying)
        )
    }

    override fun onDestroy() {
        engine.stop()
        isPlaying = false
        releaseWakeLock()
        abandonFocus()
        session?.isActive = false
        session?.release()
        session = null
        super.onDestroy()
    }

    /** Wird vom System aufgerufen, wenn die App aus den "Letzten Apps" gewischt wird. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Nichts tun: Der Ton soll weiterlaufen.
        // لا نوقف شيئًا — الصوت يجب أن يستمر.
    }
}

object Prefs {
    const val NAME = "hadaa_prefs"
    const val KEY_PRESET = "preset"
    const val KEY_VOLUME = "volume"
    const val KEY_LEVEL = "level"
}
