package com.voxislabs.chargeguard

import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.gms.ads.AdView

class MainActivity : AppCompatActivity() {
    private lateinit var descriptionText: TextView
    private lateinit var disclaimerText: TextView
    private lateinit var settleHintText: TextView
    private lateinit var stateText: TextView
    private lateinit var modeText: TextView
    private lateinit var armButton: Button
    private lateinit var disarmButton: Button
    private lateinit var armModeSwitch: Switch
    private lateinit var sensitivityLabel: TextView
    private lateinit var sensitivitySeekBar: SeekBar
    private lateinit var countdownLabel: TextView
    private lateinit var countdownSeekBar: SeekBar
    private lateinit var calibrateButton: Button
    private lateinit var pinSwitch: Switch
    private lateinit var pinInput: EditText
    private lateinit var savePinButton: Button
    private lateinit var premiumStatusText: TextView
    private lateinit var buyPremiumButton: Button
    private lateinit var restorePurchaseButton: Button
    private lateinit var adView: AdView

    private lateinit var monetizationManager: MonetizationManager

    private val prefs by lazy { getSharedPreferences(GuardService.PREFS, MODE_PRIVATE) }

    private val uiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != GuardService.ACTION_UI_STATE_CHANGED) return
            val uiState = intent.getStringExtra(GuardService.EXTRA_UI_STATE)
                ?: GuardService.UI_STATE_MONITORING
            updateModeText(uiState)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ContextCompat.startForegroundService(this, Intent(this, GuardService::class.java).apply {
            action = GuardService.ACTION_START_MONITORING
        })

        stateText = findViewById(R.id.stateText)
        descriptionText = findViewById(R.id.descriptionText)
        disclaimerText = findViewById(R.id.disclaimerText)
        settleHintText = findViewById(R.id.settleHintText)
        modeText = findViewById(R.id.modeText)
        armButton = findViewById(R.id.armButton)
        disarmButton = findViewById(R.id.disarmButton)
        armModeSwitch = findViewById(R.id.armModeSwitch)
        sensitivityLabel = findViewById(R.id.sensitivityLabel)
        sensitivitySeekBar = findViewById(R.id.sensitivitySeekBar)
        countdownLabel = findViewById(R.id.countdownLabel)
        countdownSeekBar = findViewById(R.id.countdownSeekBar)
        calibrateButton = findViewById(R.id.calibrateButton)
        pinSwitch = findViewById(R.id.pinSwitch)
        pinInput = findViewById(R.id.pinInput)
        savePinButton = findViewById(R.id.savePinButton)
        premiumStatusText = findViewById(R.id.premiumStatusText)
        buyPremiumButton = findViewById(R.id.buyPremiumButton)
        restorePurchaseButton = findViewById(R.id.restorePurchaseButton)
        adView = findViewById(R.id.adView)

        monetizationManager = MonetizationManager(
            activity = this,
            prefs = prefs,
            premiumStatusText = premiumStatusText,
            buyPremiumButton = buyPremiumButton,
            restorePurchaseButton = restorePurchaseButton,
            adView = adView
        )
        monetizationManager.initialize()

        setupSensitivityUi()
        setupCountdownUi()
        setupPinUi()
        setupArmModeUi()

        armButton.setOnClickListener {
            val armIntent = Intent(this, GuardService::class.java).apply {
                action = GuardService.ACTION_ARM
            }
            ContextCompat.startForegroundService(this, armIntent)
            updateStateText(true)
        }

        disarmButton.setOnClickListener {
            if (isPinEnabled()) {
                showPinPromptAndDisarm()
            } else {
                disarmWithoutPin()
            }
        }

        calibrateButton.setOnClickListener {
            val calibrateIntent = Intent(this, GuardService::class.java).apply {
                action = GuardService.ACTION_CALIBRATE
            }
            ContextCompat.startForegroundService(this, calibrateIntent)
            Toast.makeText(this, getString(R.string.calibration_started), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val armed = getSharedPreferences("guard_prefs", MODE_PRIVATE)
            .getBoolean(GuardService.KEY_ARMED, false)
        updateStateText(armed)
        refreshSettingsUiFromPrefs()
        updateModeText(
            prefs.getString(GuardService.KEY_UI_STATE, GuardService.UI_STATE_MONITORING)
                ?: GuardService.UI_STATE_MONITORING
        )
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            uiStateReceiver,
            IntentFilter(GuardService.ACTION_UI_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(uiStateReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        monetizationManager.onDestroy()
    }

    private fun updateStateText(armed: Boolean) {
        stateText.text = if (armed) {
            getString(R.string.status_armed)
        } else {
            getString(R.string.status_disarmed)
        }
    }

    private fun setupSensitivityUi() {
        sensitivitySeekBar.max = 70
        sensitivitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = progressToThreshold(progress)
                sensitivityLabel.text = getString(R.string.sensitivity_value, threshold)
                if (fromUser) {
                    prefs.edit().putFloat(GuardService.KEY_SENSITIVITY_THRESHOLD, threshold).apply()
                    startService(Intent(this@MainActivity, GuardService::class.java).apply {
                        action = GuardService.ACTION_UPDATE_SETTINGS
                    })
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun setupPinUi() {
        pinSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(GuardService.KEY_PIN_ENABLED, checked).apply()
            updatePinInputEnabled(checked)
        }

        savePinButton.setOnClickListener {
            val pin = pinInput.text.toString().trim()
            if (pin.length < 4 || pin.any { !it.isDigit() }) {
                Toast.makeText(this, getString(R.string.pin_invalid), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit().putString(GuardService.KEY_PIN_CODE, pin).apply()
            pinInput.setText("")
            Toast.makeText(this, getString(R.string.pin_saved), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupArmModeUi() {
        armModeSwitch.setOnCheckedChangeListener { _, checked ->
            val mode = if (checked) GuardService.ARM_MODE_AUTO_LOCK else GuardService.ARM_MODE_MANUAL
            prefs.edit().putString(GuardService.KEY_ARM_MODE, mode).apply()
            armButton.isEnabled = mode == GuardService.ARM_MODE_MANUAL
            armButton.alpha = if (armButton.isEnabled) 1f else 0.5f
            updateArmModeCopy(mode)

            startService(Intent(this@MainActivity, GuardService::class.java).apply {
                action = GuardService.ACTION_UPDATE_SETTINGS
            })
        }
    }

    private fun setupCountdownUi() {
        countdownSeekBar.max = 7
        countdownSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = progressToCountdown(progress)
                countdownLabel.text = getString(R.string.countdown_value, seconds)
                if (fromUser) {
                    prefs.edit().putInt(GuardService.KEY_GRACE_SECONDS, seconds).apply()
                    startService(Intent(this@MainActivity, GuardService::class.java).apply {
                        action = GuardService.ACTION_UPDATE_SETTINGS
                    })
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun refreshSettingsUiFromPrefs() {
        val threshold = prefs.getFloat(GuardService.KEY_SENSITIVITY_THRESHOLD, 3.2f)
        sensitivitySeekBar.progress = thresholdToProgress(threshold)
        sensitivityLabel.text = getString(R.string.sensitivity_value, threshold)

        val graceSeconds = prefs.getInt(GuardService.KEY_GRACE_SECONDS, 7).coerceIn(5, 12)
        countdownSeekBar.progress = countdownToProgress(graceSeconds)
        countdownLabel.text = getString(R.string.countdown_value, graceSeconds)

        val pinEnabled = isPinEnabled()
        pinSwitch.isChecked = pinEnabled
        updatePinInputEnabled(pinEnabled)

        val mode = prefs.getString(GuardService.KEY_ARM_MODE, GuardService.ARM_MODE_AUTO_LOCK)
            ?: GuardService.ARM_MODE_AUTO_LOCK
        armModeSwitch.isChecked = mode == GuardService.ARM_MODE_AUTO_LOCK
        armButton.isEnabled = mode == GuardService.ARM_MODE_MANUAL
        armButton.alpha = if (armButton.isEnabled) 1f else 0.5f
        updateArmModeCopy(mode)
    }

    private fun updatePinInputEnabled(enabled: Boolean) {
        pinInput.isEnabled = enabled
        savePinButton.isEnabled = enabled
    }

    private fun isPinEnabled(): Boolean {
        return prefs.getBoolean(GuardService.KEY_PIN_ENABLED, false) &&
            !prefs.getString(GuardService.KEY_PIN_CODE, "").isNullOrBlank()
    }

    private fun showPinPromptAndDisarm() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.pin_prompt_hint)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.enter_pin_title)
            .setView(input)
            .setPositiveButton(R.string.disarm_label) { _, _ ->
                val entered = input.text?.toString()?.trim().orEmpty()
                val saved = prefs.getString(GuardService.KEY_PIN_CODE, "").orEmpty()
                if (entered == saved) {
                    disarmWithoutPin()
                } else {
                    Toast.makeText(this, getString(R.string.pin_incorrect), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun disarmWithoutPin() {
        val disarmIntent = Intent(this, GuardService::class.java).apply {
            action = GuardService.ACTION_DISARM
        }
        startService(disarmIntent)
        updateStateText(false)
    }

    private fun progressToThreshold(progress: Int): Float {
        return ((progress + 10) / 10f)
    }

    private fun thresholdToProgress(threshold: Float): Int {
        return ((threshold * 10f).toInt() - 10).coerceIn(0, 70)
    }

    private fun progressToCountdown(progress: Int): Int {
        return progress + 5
    }

    private fun countdownToProgress(seconds: Int): Int {
        return (seconds - 5).coerceIn(0, 7)
    }

    private fun updateModeText(uiState: String) {
        val armMode = prefs.getString(GuardService.KEY_ARM_MODE, GuardService.ARM_MODE_AUTO_LOCK)
            ?: GuardService.ARM_MODE_AUTO_LOCK
        val effectiveState = if (
            armMode == GuardService.ARM_MODE_MANUAL &&
            uiState == GuardService.UI_STATE_PENDING
        ) {
            GuardService.UI_STATE_MONITORING
        } else {
            uiState
        }

        modeText.text = when (effectiveState) {
            GuardService.UI_STATE_PENDING -> getString(R.string.mode_pending_auto_arm)
            GuardService.UI_STATE_ARMED -> getString(R.string.mode_armed)
            GuardService.UI_STATE_ALARM -> getString(R.string.mode_alarm)
            else -> getString(R.string.mode_monitoring)
        }
    }

    private fun updateArmModeCopy(mode: String) {
        val auto = mode == GuardService.ARM_MODE_AUTO_LOCK
        descriptionText.text = if (auto) {
            getString(R.string.desc_auto)
        } else {
            getString(R.string.desc_manual)
        }
        disclaimerText.text = getString(R.string.disclaimer_charging_only)
        settleHintText.text = if (auto) {
            getString(R.string.settle_hint_auto)
        } else {
            getString(R.string.settle_hint_manual)
        }
    }
}
