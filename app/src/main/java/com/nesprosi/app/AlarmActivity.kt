package com.nesprosi.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.ceil

/**
 * Экран будильника поверх экрана блокировки.
 * Будильник выключается только удержанием кнопки 2 секунды — случайное касание во сне не сработает.
 * Через минуту экран спрашивает «Вы проснулись?», и без ответа будильник звонит снова.
 */
class AlarmActivity : AppCompatActivity() {

    private lateinit var root: View
    private lateinit var alarmContainer: View
    private lateinit var checkContainer: View
    private lateinit var big: TextView
    private lateinit var text: TextView
    private lateinit var mode: TextView
    private lateinit var icon: ImageView
    private lateinit var holdBtn: View
    private lateinit var holdProgress: ProgressBar

    private val handler = Handler(Looper.getMainLooper())
    private var pulse: AnimatorSet? = null
    private var holdAnimator: ValueAnimator? = null
    private var shownMode: Boolean? = null // true — будильник, false — вопрос
    private val listener: (TripService.State) -> Unit = { render(it) }
    private val countdown = object : Runnable {
        override fun run() {
            render(TripService.state)
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alarm)

        root = findViewById(R.id.alarmRoot)
        alarmContainer = findViewById(R.id.alarmContainer)
        checkContainer = findViewById(R.id.checkContainer)
        big = findViewById(R.id.alarmBig)
        text = findViewById(R.id.alarmText)
        mode = findViewById(R.id.alarmMode)
        icon = findViewById(R.id.alarmIcon)
        holdBtn = findViewById(R.id.holdBtn)
        holdProgress = findViewById(R.id.holdProgress)

        setupHold()
        findViewById<View>(R.id.awakeBtn).setOnClickListener {
            TripService.confirmAwake(this)
            finish()
        }
        // случайное нажатие «Назад» не выключает будильник
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        val prefs = Prefs(this)
        val (modeText, modeIcon) = when {
            prefs.vibrationOnly -> R.string.alarm_mode_vibration to R.drawable.ic_vibration
            prefs.headphonesOnly -> R.string.alarm_mode_headphones to R.drawable.ic_headphones
            else -> R.string.alarm_mode_loud to R.drawable.ic_volume
        }
        mode.setText(modeText)
        mode.setCompoundDrawablesRelativeWithIntrinsicBounds(modeIcon, 0, 0, 0)
        mode.compoundDrawablesRelative[0]?.mutate()?.setTint(0xFFFFFFFF.toInt())
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHold() {
        holdBtn.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    holdAnimator?.cancel()
                    holdAnimator = ValueAnimator.ofInt(0, 1000).apply {
                        duration = 2000
                        addUpdateListener { holdProgress.progress = it.animatedValue as Int }
                        addListener(object : AnimatorListenerAdapter() {
                            private var cancelled = false
                            override fun onAnimationCancel(animation: Animator) { cancelled = true }
                            override fun onAnimationEnd(animation: Animator) {
                                if (!cancelled) TripService.dismiss(this@AlarmActivity)
                            }
                        })
                        start()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    holdAnimator?.cancel()
                    holdProgress.progress = 0
                    if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    true
                }
                else -> true
            }
        }
        // простое нажатие ничего не делает — только удержание
        holdBtn.setOnClickListener {}
    }

    override fun onStart() {
        super.onStart()
        TripService.addListener(listener)
        handler.post(countdown)
    }

    override fun onStop() {
        TripService.removeListener(listener)
        handler.removeCallbacks(countdown)
        super.onStop()
    }

    override fun onDestroy() {
        pulse?.cancel()
        holdAnimator?.cancel()
        super.onDestroy()
    }

    private fun render(s: TripService.State) {
        val name = s.dest?.name ?: getString(R.string.dest_fallback)
        when {
            s.phase == TripService.Phase.ALARM -> {
                showMode(alarm = true)
                when (s.reason) {
                    TripService.Reason.BACKUP -> {
                        big.setText(R.string.alarm_backup_big)
                        text.text = getString(R.string.alarm_backup_body)
                        icon.setImageResource(R.drawable.ic_timer)
                        root.setBackgroundResource(R.drawable.alarm_bg)
                    }
                    TripService.Reason.PASSED -> {
                        big.setText(R.string.alarm_passed_big)
                        text.text = getString(R.string.alarm_passed_text, name)
                        icon.setImageResource(R.drawable.ic_warning)
                        root.setBackgroundResource(R.drawable.alarm_passed_bg)
                    }
                    else -> {
                        big.setText(R.string.alarm_big)
                        text.text = s.distance?.let { getString(R.string.alarm_distance, Geo.formatDistance(this, it), name) }
                            ?: getString(R.string.alarm_arriving, name)
                        icon.setImageResource(R.drawable.ic_bell)
                        root.setBackgroundResource(R.drawable.alarm_bg)
                    }
                }
            }
            s.phase == TripService.Phase.CHECK && s.checkDeadline != null -> {
                showMode(alarm = false)
                val left = ceil((s.checkDeadline - System.currentTimeMillis()) / 1000.0).toInt().coerceAtLeast(0)
                findViewById<TextView>(R.id.checkSubtitle).text = getString(R.string.check_subtitle, name)
                findViewById<TextView>(R.id.checkCountdown).text = getString(R.string.check_countdown, left)
                val distanceRow = findViewById<View>(R.id.checkDistanceRow)
                distanceRow.visibility = if (s.distance != null) View.VISIBLE else View.GONE
                s.distance?.let { findViewById<TextView>(R.id.checkDistance).text = Geo.formatDistance(this, it) }
            }
            else -> finish()
        }
    }

    private fun showMode(alarm: Boolean) {
        if (shownMode == alarm) return
        shownMode = alarm
        alarmContainer.visibility = if (alarm) View.VISIBLE else View.GONE
        checkContainer.visibility = if (alarm) View.GONE else View.VISIBLE
        if (alarm) startPulse() else {
            pulse?.cancel()
            root.setBackgroundResource(R.drawable.check_bg)
        }
    }

    /** Колокол и кольца вокруг него «дышат», чтобы экран было видно краем глаза. */
    private fun startPulse() {
        if (pulse != null) return
        fun breathe(view: View, from: Float, to: Float, delay: Long) = listOf(
            ObjectAnimator.ofFloat(view, View.SCALE_X, from, to),
            ObjectAnimator.ofFloat(view, View.SCALE_Y, from, to),
        ).onEach {
            it.duration = 700
            it.startDelay = delay
            it.repeatMode = ValueAnimator.REVERSE
            it.repeatCount = ValueAnimator.INFINITE
        }
        pulse = AnimatorSet().apply {
            playTogether(
                breathe(findViewById(R.id.ringOuter), 0.9f, 1.08f, 0) +
                    breathe(findViewById(R.id.ringInner), 0.92f, 1.06f, 150) +
                    breathe(findViewById(R.id.bellCircle), 0.96f, 1.04f, 300) +
                    listOf(ObjectAnimator.ofFloat(icon, View.ROTATION, -14f, 14f).apply {
                        duration = 180
                        repeatMode = ValueAnimator.REVERSE
                        repeatCount = ValueAnimator.INFINITE
                    })
            )
            start()
        }
    }
}
