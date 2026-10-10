package com.mohammad.hadaa

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var groups: LinearLayout
    private lateinit var levelRow: LinearLayout
    private lateinit var playButton: TextView
    private lateinit var playLabel: TextView
    private lateinit var volumeBar: SeekBar
    private lateinit var volumeValue: TextView
    private lateinit var batteryCard: View
    private lateinit var glow: View

    private var selectedId: String = SoundPresets.all[0].id
    private var level: Int = 1
    private val cards = mutableMapOf<String, LinearLayout>()
    private val levelButtons = mutableListOf<TextView>()

    private val askNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Der Dienst läuft auch ohne sichtbare Benachrichtigung weiter */ }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            renderPlayState(i?.getBooleanExtra(PlayerService.EXTRA_PLAYING, false) ?: false)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        groups = findViewById(R.id.groups)
        levelRow = findViewById(R.id.levelRow)
        playButton = findViewById(R.id.playButton)
        playLabel = findViewById(R.id.playLabel)
        volumeBar = findViewById(R.id.volumeBar)
        volumeValue = findViewById(R.id.volumeValue)
        batteryCard = findViewById(R.id.batteryCard)
        glow = findViewById(R.id.glow)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }

        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        selectedId = prefs.getString(Prefs.KEY_PRESET, SoundPresets.all[0].id)!!
        level = prefs.getInt(Prefs.KEY_LEVEL, 1)
        val vol = prefs.getInt(Prefs.KEY_VOLUME, 65)

        buildLevels()
        buildGroups()
        highlight(selectedId)
        markLevel()

        volumeBar.progress = vol
        volumeValue.text = "$vol%"
        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                volumeValue.text = "$p%"
                prefs.edit().putInt(Prefs.KEY_VOLUME, p).apply()
                if (PlayerService.isPlaying) {
                    send(PlayerService.ACTION_SET_VOLUME) {
                        it.putExtra(PlayerService.EXTRA_VOLUME, p)
                    }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        playButton.setOnClickListener { toggle() }
        playLabel.setOnClickListener { toggle() }
        findViewById<View>(R.id.batteryButton).setOnClickListener { openBatterySettings() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(PlayerService.BROADCAST_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }
        renderPlayState(PlayerService.isPlaying)
        if (PlayerService.isPlaying) highlight(PlayerService.activePresetId)
        batteryCard.visibility = if (isIgnoringBatteryOptimizations()) View.GONE else View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
    }

    // ---------- UI-Aufbau ----------

    private fun buildLevels() {
        Levels.all.forEachIndexed { index, lvl ->
            val b = TextView(this).apply {
                text = getString(lvl.nameRes)
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(14), dp(4), dp(14))
                isClickable = true
                isFocusable = true
                setOnClickListener { chooseLevel(index) }
            }
            b.layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(dp(4), 0, dp(4), 0) }
            levelButtons.add(b)
            levelRow.addView(b)
        }
    }

    private fun buildGroups() {
        Group.values().forEach { g ->
            val items = SoundPresets.inGroup(g)
            if (items.isEmpty()) return@forEach

            groups.addView(TextView(this).apply {
                text = getString(groupName(g))
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_faint))
                setPadding(0, dp(16), 0, dp(8))
            })

            val grid = GridLayout(this).apply {
                columnCount = 4
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            items.forEach { preset ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.card_idle)
                    setPadding(dp(4), dp(14), dp(4), dp(12))
                    isClickable = true
                    isFocusable = true
                }
                card.addView(TextView(this).apply {
                    text = preset.emoji
                    textSize = 26f
                    gravity = Gravity.CENTER
                })
                card.addView(TextView(this).apply {
                    text = getString(preset.nameRes)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
                    setPadding(0, dp(6), 0, 0)
                })
                card.layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                }
                card.setOnClickListener { choose(preset) }
                cards[preset.id] = card
                grid.addView(card)
            }
            groups.addView(grid)
        }
    }

    private fun groupName(g: Group) = when (g) {
        Group.PURE -> R.string.g_pure
        Group.LOUD -> R.string.g_loud
        Group.HOME -> R.string.g_home
        Group.MOVE -> R.string.g_move
    }

    // ---------- Auswahl ----------

    private fun choose(preset: SoundPreset) {
        selectedId = preset.id
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .edit().putString(Prefs.KEY_PRESET, preset.id).apply()
        highlight(preset.id)
        if (PlayerService.isPlaying) {
            send(PlayerService.ACTION_SET_PRESET) {
                it.putExtra(PlayerService.EXTRA_PRESET, preset.id)
            }
        } else {
            start()
        }
    }

    private fun chooseLevel(index: Int) {
        level = index
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .edit().putInt(Prefs.KEY_LEVEL, index).apply()
        markLevel()
        if (PlayerService.isPlaying) {
            send(PlayerService.ACTION_SET_LEVEL) {
                it.putExtra(PlayerService.EXTRA_LEVEL, index)
            }
        }
    }

    private fun highlight(id: String) {
        cards.forEach { (key, view) ->
            val on = key == id
            view.setBackgroundResource(if (on) R.drawable.card_selected else R.drawable.card_idle)
            (view.getChildAt(1) as TextView).setTextColor(
                ContextCompat.getColor(this, if (on) R.color.accent else R.color.text_dim)
            )
        }
    }

    private fun markLevel() {
        levelButtons.forEachIndexed { i, b ->
            val on = i == level
            b.setBackgroundResource(if (on) R.drawable.seg_on else R.drawable.seg_off)
            b.setTextColor(ContextCompat.getColor(this, if (on) R.color.accent else R.color.text_dim))
            b.setPadding(dp(4), dp(14), dp(4), dp(14))
        }
    }

    private fun renderPlayState(playing: Boolean) {
        if (playing) {
            playButton.setBackgroundResource(R.drawable.btn_stop)
            playButton.text = "■"
            playLabel.setText(R.string.label_stop)
            glow.visibility = View.VISIBLE
            glow.animate().alpha(1f).scaleX(1.12f).scaleY(1.12f).setDuration(1600).start()
        } else {
            playButton.setBackgroundResource(R.drawable.btn_play)
            playButton.text = "▶"
            playLabel.setText(R.string.label_play)
            glow.animate().alpha(0f).scaleX(1f).scaleY(1f).setDuration(400).start()
        }
    }

    // ---------- Steuerung ----------

    private fun toggle() {
        if (PlayerService.isPlaying) stop() else start()
    }

    private fun start() {
        val i = Intent(this, PlayerService::class.java)
            .setAction(PlayerService.ACTION_START)
            .putExtra(PlayerService.EXTRA_PRESET, selectedId)
            .putExtra(PlayerService.EXTRA_VOLUME, volumeBar.progress)
            .putExtra(PlayerService.EXTRA_LEVEL, level)
        ContextCompat.startForegroundService(this, i)
        renderPlayState(true)
    }

    private fun stop() {
        send(PlayerService.ACTION_STOP) {}
        renderPlayState(false)
    }

    private fun send(action: String, block: (Intent) -> Unit) {
        val i = Intent(this, PlayerService::class.java).setAction(action)
        block(i)
        ContextCompat.startForegroundService(this, i)
    }

    // ---------- Akku-Optimierung / تحسين البطارية ----------

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun openBatterySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        }
    }
}
