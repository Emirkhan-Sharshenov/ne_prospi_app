package com.nesprosi.app

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/** Экран будильника: показывается поверх экрана блокировки и включает дисплей. */
class AlarmActivity : AppCompatActivity() {

    private var animator: ValueAnimator? = null
    private val listener: (TripService.State) -> Unit = {
        if (it.phase != TripService.Phase.ALARM) finish()
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

        val name = TripService.state.dest?.name ?: "ваша остановка"
        findViewById<TextView>(R.id.alarmText).text = "Вы подъезжаете:\n$name"
        findViewById<Button>(R.id.awakeBtn).setOnClickListener {
            TripService.stop(this)
            finish()
        }
        // случайное нажатие «Назад» не выключает будильник
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        val root = findViewById<View>(R.id.alarmRoot)
        animator = ValueAnimator.ofObject(ArgbEvaluator(), Color.rgb(209, 0, 0), Color.rgb(40, 0, 0)).apply {
            duration = 600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { root.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
    }

    override fun onStart() {
        super.onStart()
        TripService.addListener(listener)
        if (TripService.state.phase != TripService.Phase.ALARM) finish()
    }

    override fun onStop() {
        TripService.removeListener(listener)
        super.onStop()
    }

    override fun onDestroy() {
        animator?.cancel()
        super.onDestroy()
    }
}
