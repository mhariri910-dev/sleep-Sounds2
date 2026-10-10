package com.mohammad.hadaa

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Erzeugt den Klang direkt auf dem Gerät (kein Audio-File, keine Schleifen-Naht).
 * Läuft in einem eigenen Thread und schreibt blockierend in den AudioTrack,
 * damit die Wiedergabe nie "endet" und daher auch nie von Android gestoppt wird.
 *
 * يولّد الصوت داخل الجهاز مباشرة — بلا ملفات صوتية وبلا فواصل في التكرار.
 */
class NoiseEngine {

    companion object {
        const val SAMPLE_RATE = 44100
        private const val FRAMES_PER_BUFFER = 1024
    }

    @Volatile private var running = false
    @Volatile private var gain = 0.6f
    @Volatile private var duck = 1.0f
    @Volatile private var preset: SoundPreset = SoundPresets.all[0]
    @Volatile private var level: Level = Levels.at(1)

    private var track: AudioTrack? = null
    private var thread: Thread? = null

    // --- Filterzustände / حالات المرشّحات ---
    private var lp1 = 0f
    private var lp2 = 0f
    private var lp3 = 0f
    private var hp = 0f
    private var prevIn = 0f
    private var modPhase = 0.0
    private var humPhase = 0.0
    private var beatPhase = 0.0
    private var dropEnv = 0f
    private var dropPhase = 0.0
    private var dropFreq = 0.0
    private var fadeIn = 0f

    // --- Rosa / braunes Rauschen ---
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f
    private var b3 = 0f; private var b4 = 0f; private var b5 = 0f; private var b6 = 0f
    private var brown = 0f

    private val rnd = Random(System.nanoTime())

    fun setPreset(p: SoundPreset) {
        if (p.id != preset.id) {
            preset = p
            fadeIn = 0f   // weicher Übergang / انتقال ناعم
        }
    }

    fun setLevel(index: Int) {
        val l = Levels.at(index)
        if (l !== level) {
            level = l
            fadeIn = 0f
        }
    }

    fun currentPreset(): SoundPreset = preset

    /** 0..100 */
    fun setVolume(percent: Int) {
        gain = (percent.coerceIn(0, 100) / 100f)
    }

    fun setDuck(ducked: Boolean) {
        duck = if (ducked) 0.25f else 1.0f
    }

    fun isRunning() = running

    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val bufSize = maxOf(minBuf, FRAMES_PER_BUFFER * 4 * 4)

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track = t
        running = true
        fadeIn = 0f
        t.play()

        thread = Thread {
            val buf = FloatArray(FRAMES_PER_BUFFER)
            while (running) {
                render(buf)
                val written = t.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) break
            }
        }.apply {
            priority = Thread.MAX_PRIORITY
            name = "HadaaNoise"
            start()
        }
    }

    fun stop() {
        running = false
        try { thread?.join(500) } catch (_: InterruptedException) {}
        thread = null
        try {
            track?.pause()
            track?.flush()
            track?.stop()
        } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
    }

    /** Grundrauschen: weiß, rosa (Paul Kellet) oder braun */
    private fun source(kind: NoiseKind): Float {
        val n = rnd.nextFloat() * 2f - 1f
        return when (kind) {
            NoiseKind.WHITE -> n
            NoiseKind.PINK -> {
                b0 = 0.99886f * b0 + n * 0.0555179f
                b1 = 0.99332f * b1 + n * 0.0750759f
                b2 = 0.96900f * b2 + n * 0.1538520f
                b3 = 0.86650f * b3 + n * 0.3104856f
                b4 = 0.55000f * b4 + n * 0.5329522f
                b5 = -0.7616f * b5 - n * 0.0168980f
                val p = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + n * 0.5362f) * 0.33f
                b6 = n * 0.115926f
                p
            }
            NoiseKind.BROWN -> {
                brown = (brown + 0.02f * n) / 1.02f
                brown * 3.5f
            }
        }
    }

    private fun render(out: FloatArray) {
        val p = preset
        val l = level
        val sr = SAMPLE_RATE.toDouble()
        val k = tanh(l.drive)

        for (i in out.indices) {
            val w = source(p.src)

            // Mehrstufiger Tiefpass -> je kleiner lowpass, desto dumpfer
            lp1 += p.lowpass * (w - lp1)
            lp2 += p.lowpass * (lp1 - lp2)
            lp3 += p.lowpass * (lp2 - lp3)
            var s = when (p.stages) {
                1 -> lp1
                2 -> lp2
                else -> lp3
            } * p.makeup

            // Hochpass entfernt dröhnende Tiefstfrequenzen
            if (p.highpass > 0f) {
                hp = p.highpass * (hp + s - prevIn)
                prevIn = s
                s = hp
            }

            // Langsame Amplitudenmodulation (Auto, Ventilator, Wellen)
            if (p.modDepth > 0f) {
                modPhase += 2.0 * PI * p.modRateHz / sr
                if (modPhase > 2 * PI) modPhase -= 2 * PI
                s *= (1f - p.modDepth + p.modDepth * (0.5f + 0.5f * sin(modPhase).toFloat()))
            }

            // Motor-/Föhn-Brummen
            if (p.hum > 0f) {
                humPhase += 2.0 * PI * p.humHz / sr
                if (humPhase > 2 * PI) humPhase -= 2 * PI
                val h = sin(humPhase).toFloat() * 0.7f + sin(humPhase * 2).toFloat() * 0.3f
                s += h * p.hum
            }

            // Regentropfen / قطرات المطر
            if (p.droplets) {
                if (dropEnv < 0.001f && rnd.nextInt(2600) == 0) {
                    dropEnv = 0.35f + rnd.nextFloat() * 0.3f
                    dropPhase = 0.0
                    dropFreq = 900.0 + rnd.nextDouble() * 2200.0
                }
                if (dropEnv > 0.0005f) {
                    dropPhase += 2.0 * PI * dropFreq / sr
                    s += sin(dropPhase).toFloat() * dropEnv * 0.25f
                    dropEnv *= 0.9985f
                }
            }

            // Herzschlag / دقات القلب
            if (p.heartbeatBpm > 0f) {
                beatPhase += p.heartbeatBpm / 60.0 / sr
                if (beatPhase >= 1.0) beatPhase -= 1.0
                val env = heartEnvelope(beatPhase)
                if (env > 0f) {
                    humPhase += 2.0 * PI * 58.0 / sr
                    s += sin(humPhase).toFloat() * env * 0.9f
                }
            }

            // Weiche Kompression: hebt die empfundene Lautstärke deutlich an
            // ضغط ناعم يرفع الصوت المحسوس كثيرًا دون تشويه حاد
            if (l.drive > 1f) s = tanh(s * l.drive) / k
            s *= l.amp

            // Sanftes Einblenden verhindert Knacken beim Umschalten
            if (fadeIn < 1f) fadeIn = minOf(1f, fadeIn + 1f / (SAMPLE_RATE * 0.35f))

            val x = s * gain * duck * fadeIn
            out[i] = if (x > 1f) 1f else if (x < -1f) -1f else x
        }
    }

    /** "lub-dub" Hüllkurve / مغلف نبضة القلب */
    private fun heartEnvelope(ph: Double): Float {
        val lub = exp(-((ph - 0.00) * 38.0) * ((ph - 0.00) * 38.0))
        val dub = exp(-((ph - 0.22) * 46.0) * ((ph - 0.22) * 46.0)) * 0.65
        return ((lub + dub) * 0.55).toFloat()
    }
}
