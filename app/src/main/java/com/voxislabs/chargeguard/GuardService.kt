package com.voxislabs.chargeguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.BatteryManager
import android.os.CountDownTimer
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.app.KeyguardManager
import androidx.core.app.NotificationCompat
import java.util.Locale
import kotlin.math.abs

class GuardService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private lateinit var notificationManager: NotificationManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var powerManager: PowerManager

    private var isArmed: Boolean = false
    private var isCharging: Boolean = false
    private var countdownTimer: CountDownTimer? = null
    private var graceSecondsRemaining: Int = 0

    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var hasLastSample = false
    private var lastDisturbanceAt = 0L

    private var toneGenerator: ToneGenerator? = null
    private var textToSpeech: TextToSpeech? = null
    private var alarmActive = false
    private var speakerRouteForced = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var sensitivityThreshold = DEFAULT_DISTURBANCE_THRESHOLD
    private var gracePeriodSeconds = DEFAULT_GRACE_PERIOD_SECONDS
    private var armMode = ARM_MODE_AUTO_LOCK
    private var triggerMode = TRIGGER_MODE_CHARGING
    private var pendingAutoArm = false
    private var lastMovementAt = 0L
    private var calibrating = false
    private var calibrationDeltaSum = 0f
    private var calibrationSampleCount = 0
    private var calibrationTimer: CountDownTimer? = null

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_BATTERY_CHANGED -> {
                    isCharging = currentChargingState(intent)
                    if (armMode == ARM_MODE_AUTO_LOCK && isCharging && keyguardManager.isDeviceLocked) {
                        startPendingAutoArm("Charging while locked")
                    }
                    refreshNotification()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    isCharging = false
                    cancelPendingAutoArm()
                    if (triggerMode == TRIGGER_MODE_UNPLUG) {
                        onDisturbance("Power disconnected")
                    }
                    refreshNotification()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    if (armMode == ARM_MODE_AUTO_LOCK && isCharging) {
                        startPendingAutoArm("Phone locked")
                    }
                    refreshNotification()
                }
                Intent.ACTION_USER_PRESENT -> {
                    // Device was unlocked. Reset protection until the next lock event.
                    disarmGuard(stopService = false)
                    refreshNotification()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        powerManager = getSystemService(POWER_SERVICE) as PowerManager

        createChannel()
        initSpeech()
        loadSettings()
        isCharging = currentChargingState(null)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(powerReceiver, filter)

        if (armMode == ARM_MODE_AUTO_LOCK && isCharging && keyguardManager.isDeviceLocked) {
            startPendingAutoArm("Startup while locked")
        }

        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> {
                // No-op, used to ensure monitoring service is alive.
            }
            ACTION_ARM -> armGuard()
            ACTION_DISARM -> disarmGuard(stopService = false)
            ACTION_UPDATE_SETTINGS -> {
                loadSettings()
                if (armMode == ARM_MODE_AUTO_LOCK && isCharging && keyguardManager.isDeviceLocked && !isArmed) {
                    startPendingAutoArm("Mode changed")
                }
                if (armMode == ARM_MODE_MANUAL) {
                    cancelPendingAutoArm()
                }
            }
            ACTION_CALIBRATE -> startCalibration()
        }

        refreshNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCalibration()
        disarmGuard(stopService = false)
        unregisterReceiver(powerReceiver)
        textToSpeech?.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent) {
        val monitorMotionForAlarm =
            isArmed &&
                !alarmActive &&
                triggerMode == TRIGGER_MODE_CHARGING &&
                isCharging
        if (!calibrating && !pendingAutoArm && !monitorMotionForAlarm) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (!hasLastSample) {
            lastX = x
            lastY = y
            lastZ = z
            hasLastSample = true
            return
        }

        val delta = abs(x - lastX) + abs(y - lastY) + abs(z - lastZ)
        lastX = x
        lastY = y
        lastZ = z

        if (calibrating) {
            calibrationDeltaSum += delta
            calibrationSampleCount += 1
            return
        }

        val now = System.currentTimeMillis()
        if (pendingAutoArm) {
            if (delta > sensitivityThreshold) {
                lastMovementAt = now
            }
            if (isCharging && now - lastMovementAt >= AUTO_ARM_STILLNESS_MS) {
                armGuard()
                refreshNotification(extra = "Guard armed after stillness")
            }
            return
        }

        if (delta > sensitivityThreshold && now - lastDisturbanceAt > DISTURBANCE_COOLDOWN_MS) {
            lastDisturbanceAt = now
            onDisturbance("Motion detected")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun armGuard() {
        if (isArmed) return
        cancelPendingAutoArm()
        isArmed = true
        saveArmedState(true)
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun disarmGuard(stopService: Boolean) {
        cancelPendingAutoArm()
        isArmed = false
        saveArmedState(false)
        stopCalibration()
        stopCountdown()
        stopAlarm()
        sensorManager.unregisterListener(this)
        hasLastSample = false
        if (stopService) stopSelf()
    }

    private fun onDisturbance(reason: String) {
        if (!isArmed || alarmActive || graceSecondsRemaining > 0) return
        startGraceCountdown(reason)
    }

    private fun startGraceCountdown(reason: String) {
        graceSecondsRemaining = gracePeriodSeconds
        refreshNotification(extra = "$reason. Unlock within $gracePeriodSeconds s")

        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer((gracePeriodSeconds * 1000L), 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                graceSecondsRemaining = (millisUntilFinished / 1000L).toInt() + 1
                refreshNotification(extra = "Disturbance detected. Unlock in $graceSecondsRemaining s")
            }

            override fun onFinish() {
                graceSecondsRemaining = 0
                if (keyguardManager.isDeviceLocked) {
                    triggerAlarm()
                } else {
                    refreshNotification(extra = "Unlocked in time. Guard still armed")
                }
            }
        }.start()
    }

    private fun stopCountdown() {
        countdownTimer?.cancel()
        countdownTimer = null
        graceSecondsRemaining = 0
    }

    private fun triggerAlarm() {
        if (alarmActive) return
        alarmActive = true
        acquireWakeLock()

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        maximizeAlarmRelatedStreams(audioManager)
        forceBuiltInSpeaker(audioManager)

        toneGenerator?.release()
        toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 15_000)

        textToSpeech?.speak(
            "Warning. This phone is being stolen.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            "steal-warning"
        )

        refreshNotification(extra = "ALARM ACTIVE. Unlock phone to stop.")
    }

    private fun stopAlarm() {
        alarmActive = false
        toneGenerator?.stopTone()
        toneGenerator?.release()
        toneGenerator = null

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        clearForcedSpeaker(audioManager)

        releaseWakeLock()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Charge Guard",
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(extra: String? = null): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            2,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val appIntent = Intent(this, MainActivity::class.java)
        val appPendingIntent = PendingIntent.getActivity(
            this,
            1,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val status = when {
            alarmActive -> "Alarm sounding"
            graceSecondsRemaining > 0 -> "Unlock in $graceSecondsRemaining seconds"
            pendingAutoArm && isCharging -> "Waiting 3s after stillness to arm"
            isArmed && isCharging -> "Armed and charging"
            isArmed && !isCharging -> "Armed, waiting for charging"
            else -> "Disarmed"
        }

        val text = extra ?: status

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Charge Guard")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(isArmed || alarmActive)
            .setContentIntent(appPendingIntent)
                .addAction(android.R.drawable.ic_menu_view, "Open App", openAppPendingIntent)
            .build()
    }

    private fun refreshNotification(extra: String? = null) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(extra))
        publishUiState()
    }

    private fun initSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.US
                textToSpeech?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        if (alarmActive) {
                            textToSpeech?.speak(
                                "Warning. This phone is being stolen.",
                                TextToSpeech.QUEUE_ADD,
                                null,
                                "steal-warning"
                            )
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = Unit
                })
            }
        }
    }

    private fun currentChargingState(intent: Intent?): Boolean {
        val batteryIntent = intent ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun saveArmedState(value: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ARMED, value)
            .apply()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        sensitivityThreshold = prefs.getFloat(KEY_SENSITIVITY_THRESHOLD, DEFAULT_DISTURBANCE_THRESHOLD)
        gracePeriodSeconds = prefs
            .getInt(KEY_GRACE_SECONDS, DEFAULT_GRACE_PERIOD_SECONDS)
            .coerceIn(5, 12)
        armMode = prefs.getString(KEY_ARM_MODE, ARM_MODE_AUTO_LOCK) ?: ARM_MODE_AUTO_LOCK
        triggerMode = prefs.getString(KEY_TRIGGER_MODE, TRIGGER_MODE_CHARGING) ?: TRIGGER_MODE_CHARGING
    }

    private fun startCalibration() {
        if (calibrating) return

        calibrating = true
        calibrationDeltaSum = 0f
        calibrationSampleCount = 0
        hasLastSample = false

        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        refreshNotification(extra = "Calibration started. Keep phone still for 4 seconds.")

        calibrationTimer?.cancel()
        calibrationTimer = object : CountDownTimer(4_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) = Unit

            override fun onFinish() {
                finishCalibration()
            }
        }.start()
    }

    private fun finishCalibration() {
        if (!calibrating) return
        calibrating = false

        val sampleCount = calibrationSampleCount.coerceAtLeast(1)
        val avgDelta = calibrationDeltaSum / sampleCount
        val calibratedThreshold = (avgDelta * 3.5f + 0.9f).coerceIn(1.5f, 8.0f)

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putFloat(KEY_SENSITIVITY_THRESHOLD, calibratedThreshold)
            .apply()

        loadSettings()
        if (!isArmed) {
            sensorManager.unregisterListener(this)
            hasLastSample = false
        }
        refreshNotification(extra = "Calibration done. Sensitivity threshold set to ${"%.2f".format(calibratedThreshold)}")
    }

    private fun stopCalibration() {
        calibrating = false
        calibrationTimer?.cancel()
        calibrationTimer = null
    }

    private fun startPendingAutoArm(reason: String) {
        if (armMode != ARM_MODE_AUTO_LOCK || isArmed || alarmActive || !isCharging) return
        pendingAutoArm = true
        lastMovementAt = System.currentTimeMillis()
        hasLastSample = false
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        refreshNotification(extra = "$reason. Arming 3s after motion stops")
    }

    private fun cancelPendingAutoArm() {
        pendingAutoArm = false
        if (!isArmed && !calibrating) {
            sensorManager.unregisterListener(this)
            hasLastSample = false
        }
    }

    private fun currentUiState(): String {
        return when {
            alarmActive -> UI_STATE_ALARM
            pendingAutoArm && isCharging -> UI_STATE_PENDING
            isArmed && isCharging -> UI_STATE_ARMED
            else -> UI_STATE_MONITORING
        }
    }

    private fun publishUiState() {
        val uiState = currentUiState()
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_UI_STATE, uiState)
            .apply()

        sendBroadcast(Intent(ACTION_UI_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_UI_STATE, uiState)
        })
    }

    private fun maximizeAlarmRelatedStreams(audioManager: AudioManager) {
        val streams = listOf(
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING
        )
        streams.forEach { stream ->
            val max = audioManager.getStreamMaxVolume(stream)
            audioManager.setStreamVolume(stream, max, 0)
        }
    }

    private fun forceBuiltInSpeaker(audioManager: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val builtinSpeaker = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (builtinSpeaker != null) {
                speakerRouteForced = audioManager.setCommunicationDevice(builtinSpeaker)
            }
            return
        }

        @Suppress("DEPRECATION")
        run {
            audioManager.isSpeakerphoneOn = true
            speakerRouteForced = true
        }
    }

    private fun clearForcedSpeaker(audioManager: AudioManager) {
        if (!speakerRouteForced) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            run {
                audioManager.isSpeakerphoneOn = false
            }
        }
        speakerRouteForced = false
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "chargeguard:alarm"
        ).apply { acquire(30_000L) }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "charge_guard_channel"
        private const val NOTIFICATION_ID = 2201
        private const val DEFAULT_GRACE_PERIOD_SECONDS = 7
        private const val DEFAULT_DISTURBANCE_THRESHOLD = 3.2f
        private const val DISTURBANCE_COOLDOWN_MS = 4_000L
        private const val AUTO_ARM_STILLNESS_MS = 3_000L

        const val ACTION_START_MONITORING = "com.voxislabs.chargeguard.action.START_MONITORING"
        const val ACTION_ARM = "com.voxislabs.chargeguard.action.ARM"
        const val ACTION_DISARM = "com.voxislabs.chargeguard.action.DISARM"
        const val ACTION_UPDATE_SETTINGS = "com.voxislabs.chargeguard.action.UPDATE_SETTINGS"
        const val ACTION_CALIBRATE = "com.voxislabs.chargeguard.action.CALIBRATE"
        const val ACTION_UI_STATE_CHANGED = "com.voxislabs.chargeguard.action.UI_STATE_CHANGED"

        const val PREFS = "guard_prefs"
        const val KEY_ARMED = "armed"
        const val KEY_SENSITIVITY_THRESHOLD = "sensitivity_threshold"
        const val KEY_GRACE_SECONDS = "grace_seconds"
        const val KEY_ARM_MODE = "arm_mode"
        const val KEY_TRIGGER_MODE = "trigger_mode"
        const val KEY_PIN_ENABLED = "pin_enabled"
        const val KEY_PIN_CODE = "pin_code"
        const val KEY_UI_STATE = "ui_state"

        const val EXTRA_UI_STATE = "extra_ui_state"

        const val ARM_MODE_AUTO_LOCK = "auto_lock"
        const val ARM_MODE_MANUAL = "manual"

        const val TRIGGER_MODE_CHARGING = "charging"
        const val TRIGGER_MODE_UNPLUG = "unplug"

        const val UI_STATE_MONITORING = "monitoring"
        const val UI_STATE_PENDING = "pending_auto_arm"
        const val UI_STATE_ARMED = "armed"
        const val UI_STATE_ALARM = "alarm"
    }
}
