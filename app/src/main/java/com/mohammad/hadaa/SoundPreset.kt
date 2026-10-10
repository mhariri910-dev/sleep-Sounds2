package com.mohammad.hadaa

/**
 * Klangvorlagen / قوالب الأصوات
 * src: Grundrauschen — WHITE / PINK / BROWN
 * lp: 0..1 (klein = dumpf), stages: 1..3, highpass: 0..1
 */
enum class NoiseKind { WHITE, PINK, BROWN }

enum class Group { PURE, LOUD, HOME, MOVE }

data class SoundPreset(
    val id: String,
    val nameRes: Int,
    val emoji: String,
    val group: Group,
    val src: NoiseKind = NoiseKind.WHITE,
    val lowpass: Float,
    val stages: Int = 2,
    val makeup: Float = 1f,
    val highpass: Float = 0f,
    val modDepth: Float = 0f,
    val modRateHz: Float = 0f,
    val hum: Float = 0f,
    val humHz: Float = 0f,
    val droplets: Boolean = false,
    val heartbeatBpm: Float = 0f
)

/** قوة الضجيج: السعة ودرجة الضغط — الضغط يرفع الصوت المحسوس كثيرًا */
data class Level(val amp: Float, val drive: Float, val nameRes: Int)

object Levels {
    val all = listOf(
        Level(0.45f, 1f, R.string.lvl_soft),
        Level(0.82f, 1f, R.string.lvl_normal),
        Level(0.97f, 9f, R.string.lvl_loud)
    )
    fun at(i: Int): Level = all.getOrElse(i) { all[1] }
}

object SoundPresets {

    val all: List<SoundPreset> = listOf(
        // ---------- ضجيج نقي ----------
        SoundPreset("white", R.string.s_white, "🌫️", Group.PURE,
            NoiseKind.WHITE, lowpass = 0.98f, stages = 1, makeup = 1.0f, highpass = 0.02f),
        SoundPreset("pink", R.string.s_pink, "🌸", Group.PURE,
            NoiseKind.PINK, lowpass = 0.98f, stages = 1, makeup = 1.0f),
        SoundPreset("brown", R.string.s_brown, "🟤", Group.PURE,
            NoiseKind.BROWN, lowpass = 0.98f, stages = 1, makeup = 1.0f),
        SoundPreset("shush", R.string.s_shush, "🤫", Group.PURE,
            NoiseKind.WHITE, lowpass = 0.50f, stages = 2, makeup = 1.8f,
            highpass = 0.45f, modDepth = 0.80f, modRateHz = 0.5f),

        // ---------- قوي جدًا ----------
        SoundPreset("waterfall", R.string.s_waterfall, "🏔️", Group.LOUD,
            NoiseKind.WHITE, lowpass = 0.70f, stages = 1, makeup = 1.4f,
            highpass = 0.12f, modDepth = 0.06f, modRateHz = 0.25f),
        SoundPreset("storm", R.string.s_storm, "🌪️", Group.LOUD,
            NoiseKind.BROWN, lowpass = 0.80f, stages = 1, makeup = 1.3f,
            modDepth = 0.35f, modRateHz = 0.25f, hum = 0.02f, humHz = 40f),
        SoundPreset("jet", R.string.s_jet, "🛫", Group.LOUD,
            NoiseKind.WHITE, lowpass = 0.45f, stages = 1, makeup = 1.8f,
            highpass = 0.08f, modDepth = 0.04f, modRateHz = 0.125f, hum = 0.09f, humHz = 132f),
        SoundPreset("engine", R.string.s_engine, "🛠️", Group.LOUD,
            NoiseKind.WHITE, lowpass = 0.09f, stages = 2, makeup = 5.0f,
            modDepth = 0.10f, modRateHz = 3.0f, hum = 0.13f, humHz = 86f),

        // ---------- أجهزة المنزل ----------
        SoundPreset("hairdryer", R.string.s_hairdryer, "💨", Group.HOME,
            NoiseKind.WHITE, lowpass = 0.16f, stages = 2, makeup = 3.4f,
            modDepth = 0.04f, modRateHz = 0.75f, hum = 0.055f, humHz = 118f),
        SoundPreset("vacuum", R.string.s_vacuum, "🧹", Group.HOME,
            NoiseKind.WHITE, lowpass = 0.13f, stages = 2, makeup = 3.0f,
            modDepth = 0.05f, modRateHz = 0.5f, hum = 0.090f, humHz = 102f),
        SoundPreset("fan", R.string.s_fan, "🌀", Group.HOME,
            NoiseKind.WHITE, lowpass = 0.10f, stages = 2, makeup = 4.6f,
            modDepth = 0.14f, modRateHz = 6.5f, hum = 0.030f, humHz = 96f),
        SoundPreset("ac", R.string.s_ac, "❄️", Group.HOME,
            NoiseKind.PINK, lowpass = 0.35f, stages = 2, makeup = 1.6f,
            modDepth = 0.03f, modRateHz = 0.25f),
        SoundPreset("washer", R.string.s_washer, "🫧", Group.HOME,
            NoiseKind.WHITE, lowpass = 0.07f, stages = 3, makeup = 6.2f,
            modDepth = 0.30f, modRateHz = 1.5f, hum = 0.040f, humHz = 74f),

        // ---------- حركة وماء ----------
        SoundPreset("car", R.string.s_car, "🚗", Group.MOVE,
            NoiseKind.WHITE, lowpass = 0.045f, stages = 3, makeup = 7.5f,
            modDepth = 0.18f, modRateHz = 0.0625f, hum = 0.050f, humHz = 62f),
        SoundPreset("train", R.string.s_train, "🚆", Group.MOVE,
            NoiseKind.BROWN, lowpass = 0.60f, stages = 2, makeup = 1.2f,
            modDepth = 0.22f, modRateHz = 2.5f, hum = 0.040f, humHz = 54f),
        SoundPreset("plane", R.string.s_plane, "✈️", Group.MOVE,
            NoiseKind.BROWN, lowpass = 0.50f, stages = 2, makeup = 1.2f,
            modDepth = 0.06f, modRateHz = 0.125f, hum = 0.050f, humHz = 88f),
        SoundPreset("rain", R.string.s_rain, "🌧️", Group.MOVE,
            NoiseKind.WHITE, lowpass = 0.30f, stages = 2, makeup = 2.3f,
            highpass = 0.35f, modDepth = 0.10f, modRateHz = 0.125f, droplets = true),
        SoundPreset("river", R.string.s_river, "🏞️", Group.MOVE,
            NoiseKind.WHITE, lowpass = 0.42f, stages = 2, makeup = 2.0f,
            highpass = 0.25f, modDepth = 0.12f, modRateHz = 0.625f, droplets = true),
        SoundPreset("waves", R.string.s_waves, "🌊", Group.MOVE,
            NoiseKind.PINK, lowpass = 0.45f, stages = 2, makeup = 1.6f,
            modDepth = 0.72f, modRateHz = 0.125f),
        SoundPreset("heartbeat", R.string.s_heartbeat, "💗", Group.MOVE,
            NoiseKind.BROWN, lowpass = 0.50f, stages = 2, makeup = 1.0f,
            heartbeatBpm = 67.5f)
    )

    fun byId(id: String?): SoundPreset =
        all.firstOrNull { it.id == id } ?: all[0]

    fun inGroup(g: Group): List<SoundPreset> = all.filter { it.group == g }
}
