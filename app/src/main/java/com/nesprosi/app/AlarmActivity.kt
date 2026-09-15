package com.nesprosi.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
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
    private lateinit var emoji: TextView
    private lateinit var big: TextView
    private lateinit var text: TextView
    private lateinit var holdBtn: View
    private lateinit var holdProgress: ProgressBar
    private lateinit var awakeBtn: Button

    private val handler = Handler(Looper.getMainLooper())
    private var flashAnimator: ValueAnimator? = null
    private var holdAnimator: ValueAnimator? = null
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
        emoji = findViewById(R.id.alarmEmoji)
        big = findViewById(R.id.alarmBig)
        text = findViewById(R.id.alarmText)
        holdBtn = findViewById(R.id.holdBtn)
        holdProgress = findViewById(R.id.holdProgress)
        awakeBtn = findViewById(R.id.awakeBtn)

        setupHold()
        awakeBtn.setOnClickListener {
            TripService.confirmAwake(this)
            finish()
        }
        // случайное нажатие «Назад» не выключает будильник
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
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
        // для TalkBack: двойное касание с удержанием тоже работает, а простое нажатие ничего не делает
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
        flashAnimator?.cancel()
        holdAnimator?.cancel()
        super.onDestroy()
    }

    private fun render(s: TripService.State) {
        val name = s.dest?.name ?: getString(R.string.dest_fallback)
        when {
            s.phase == TripService.Phase.ALARM -> {
                val (icon, title, body) = when (s.reason) {
                    TripService.Reason.BACKUP -> Triple("⏰", R.string.alarm_backup_big, getString(R.string.alarm_backup_body))
                    TripService.Reason.PASSED -> Triple("⚠️", R.string.alarm_passed_big, getString(R.string.alarm_passed_text, name))
                    else -> Triple("⏰", R.string.alarm_big, getString(R.string.alarm_arriving, name))
                }
                emoji.text = icon
                big.setText(title)
                text.text = body
                holdBtn.visibility = View.VISIBLE
                awakeBtn.visibility = View.GONE
                startFlashing()
            }
            s.phase == TripService.Phase.CHECK && s.checkDeadline != null -> {
                val left = ceil((s.checkDeadline - System.currentTimeMillis()) / 1000.0).toInt().coerceAtLeast(0)
                emoji.text = "🙂"
                big.setText(R.string.check_big)
                text.text = getString(R.string.check_countdown, left)
                holdBtn.visibility = View.GONE
                awakeBtn.visibility = View.VISIBLE
                stopFlashing()
                root.setBackgroundColor(Color.rgb(20, 28, 48))
            }
            else -> finish()
        }
    }

    private fun startFlashing() {
        if (flashAnimator != null) return
        flashAnimator = ValueAnimator.ofObject(ArgbEvaluator(), Color.rgb(209, 0, 0), Color.rgb(40, 0, 0)).apply {
            duration = 600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { root.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
    }

    private fun stopFlashing() {
        flashAnimator?.cancel()
        flashAnimator = null
    }
}
