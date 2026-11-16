package io.github.jqssun.gpssetter.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.ui.ActivitySettings
import io.github.jqssun.gpssetter.ui.VoiceCommandActivity

class VoiceCommandOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingButton: FrameLayout? = null
    private var buttonIcon: ImageView? = null
    private var entranceAnimator: AnimatorSet? = null
    private var removalAnimator: AnimatorSet? = null
    private var unregisterActiveLocationListener: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!PrefManager.isVoiceFloatingButtonEnabled || !Settings.canDrawOverlays(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        showFloatingButton()
        unregisterActiveLocationListener?.invoke()
        unregisterActiveLocationListener = PrefManager.registerActiveLocationChangeListener {
            mainHandler.post(::updateButtonState)
        }
        updateButtonState()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterActiveLocationListener?.invoke()
        unregisterActiveLocationListener = null
        entranceAnimator?.cancel()
        entranceAnimator = null
        removalAnimator?.cancel()
        removalAnimator = null
        floatingButton?.let { windowManager?.removeView(it) }
        floatingButton = null
        buttonIcon = null
        windowManager = null
        super.onDestroy()
    }

    private fun showFloatingButton() {
        if (floatingButton != null) return

        val buttonSize = (56 * resources.displayMetrics.density).toInt()
        val iconSize = (28 * resources.displayMetrics.density).toInt()
        val button = HoldProgressButton(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@VoiceCommandOverlayService, R.color.primary))
            }
            contentDescription = getString(R.string.voice_floating_button)
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setPadding(0, 0, 0, 0)
            importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        button.addView(
            icon,
            FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
        )
        buttonIcon = icon

        val displayMetrics = resources.displayMetrics
        val maxX = (displayMetrics.widthPixels - buttonSize).coerceAtLeast(0)
        val maxY = (displayMetrics.heightPixels - buttonSize).coerceAtLeast(0)
        val savedX = PrefManager.voiceFloatingButtonX
        val savedY = PrefManager.voiceFloatingButtonY
        val targetY = (if (savedY >= 0) savedY else maxY / 2).coerceIn(0, maxY)
        val layoutParams = WindowManager.LayoutParams(
            buttonSize,
            buttonSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (if (savedX >= 0) savedX else maxX - (16 * displayMetrics.density).toInt())
                .coerceIn(0, maxX)
            y = -buttonSize
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        button.setOnTouchListener(DragTouchListener(button, layoutParams, maxX, maxY))
        windowManager?.addView(button, layoutParams)
        floatingButton = button
        button.alpha = 0f
        entranceAnimator = AnimatorSet().apply {
            playTogether(
                ValueAnimator.ofInt(-buttonSize, targetY).apply {
                    addUpdateListener {
                        layoutParams.y = it.animatedValue as Int
                        windowManager?.updateViewLayout(button, layoutParams)
                    }
                },
                ObjectAnimator.ofFloat(button, View.ALPHA, 0f, 1f)
            )
            duration = BUTTON_ENTRANCE_ANIMATION_DURATION_MS
            interpolator = android.view.animation.DecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (entranceAnimator === animation) entranceAnimator = null
                }
            })
            start()
        }
    }

    private fun updateButtonState() {
        val active = PrefManager.isStarted || PrefManager.hasActiveAppProfile
        floatingButton?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                ContextCompat.getColor(
                    this@VoiceCommandOverlayService,
                    if (active) R.color.voice_active else R.color.voice_inactive
                )
            )
        }
        buttonIcon?.setImageResource(R.drawable.ic_mic)
        floatingButton?.contentDescription = getString(
            if (active) R.string.voice_floating_button_active
            else R.string.voice_floating_button
        )
    }

    private inner class DragTouchListener(
        private val button: HoldProgressButton,
        private val layoutParams: WindowManager.LayoutParams,
        private val maxX: Int,
        private val maxY: Int
    ) : View.OnTouchListener {
        private val touchSlop = ViewConfiguration.get(this@VoiceCommandOverlayService).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var downTime = 0L
        private var startX = 0
        private var startY = 0
        private var isTouchActive = false
        private var isDragging = false
        private var isLongPressTriggered = false
        private var holdAnimator: ValueAnimator? = null

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downTime = event.eventTime
                    startX = layoutParams.x
                    startY = layoutParams.y
                    isTouchActive = true
                    isDragging = false
                    isLongPressTriggered = false
                    startHoldProgress()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isLongPressTriggered) {
                        val deltaX = (event.rawX - downRawX).toInt()
                        val deltaY = (event.rawY - downRawY).toInt()
                        if (!isDragging &&
                            (kotlin.math.abs(deltaX) > touchSlop || kotlin.math.abs(deltaY) > touchSlop)
                        ) {
                            isDragging = true
                            cancelHoldProgress()
                        }
                        if (isDragging) {
                            layoutParams.x = (startX + deltaX).coerceIn(0, maxX)
                            layoutParams.y = (startY + deltaY).coerceIn(0, maxY)
                            windowManager?.updateViewLayout(button, layoutParams)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    isTouchActive = false
                    if (!isLongPressTriggered && isDragging) {
                        PrefManager.voiceFloatingButtonX = layoutParams.x
                        PrefManager.voiceFloatingButtonY = layoutParams.y
                    } else if (!isLongPressTriggered &&
                        event.eventTime - downTime >= HOLD_TO_DISABLE_DURATION_MS
                    ) {
                        triggerDisable()
                    } else if (!isLongPressTriggered) {
                        startActivity(
                            Intent(this@VoiceCommandOverlayService, VoiceCommandActivity::class.java)
                                .putExtra(
                                    VoiceCommandActivity.EXTRA_MAP_WAS_VISIBLE,
                                    io.github.jqssun.gpssetter.ui.BaseMapActivity.isMapVisible
                                )
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    cancelHoldProgress()
                }
                MotionEvent.ACTION_CANCEL -> {
                    isTouchActive = false
                    isDragging = false
                    cancelHoldProgress()
                }
            }
            return true
        }

        private fun startHoldProgress() {
            holdAnimator?.cancel()
            holdAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = HOLD_TO_DISABLE_DURATION_MS
                interpolator = LinearInterpolator()
                addUpdateListener { button.holdProgress = it.animatedValue as Float }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (holdAnimator === animation) holdAnimator = null
                        if (isTouchActive && !isDragging) triggerDisable()
                    }
                })
                start()
            }
        }

        private fun cancelHoldProgress() {
            holdAnimator?.cancel()
            holdAnimator = null
            button.holdProgress = 0f
        }

        private fun triggerDisable() {
            if (isLongPressTriggered || isDragging) return
            isLongPressTriggered = true
            cancelHoldProgress()
            button.holdProgress = 1f
            button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            PrefManager.isVoiceFloatingButtonEnabled = false
            animateButtonAway(button, layoutParams)
        }
    }

    private fun animateButtonAway(
        button: View,
        layoutParams: WindowManager.LayoutParams
    ) {
        entranceAnimator?.cancel()
        entranceAnimator = null
        val startY = layoutParams.y
        val targetY = -button.height
        removalAnimator = AnimatorSet().apply {
            playTogether(
                ValueAnimator.ofInt(startY, targetY).apply {
                    addUpdateListener {
                        layoutParams.y = it.animatedValue as Int
                        windowManager?.updateViewLayout(button, layoutParams)
                    }
                },
                ObjectAnimator.ofFloat(button, View.ALPHA, 1f, 0f),
                ObjectAnimator.ofFloat(button, View.SCALE_X, 1f, 0.65f),
                ObjectAnimator.ofFloat(button, View.SCALE_Y, 1f, 0.65f)
            )
            duration = BUTTON_DISMISS_ANIMATION_DURATION_MS
            interpolator = AccelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (removalAnimator === animation) {
                        removalAnimator = null
                        stopSelf()
                    }
                }
            })
            start()
        }
    }

    private class HoldProgressButton(context: Context) : FrameLayout(context) {
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3 * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
        }
        private val progressBounds = RectF()

        var holdProgress = 0f
            set(value) {
                field = value
                invalidate()
            }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (holdProgress > 0f) {
                val inset = progressPaint.strokeWidth
                progressBounds.set(inset, inset, width - inset, height - inset)
                canvas.drawArc(
                    progressBounds,
                    -90f,
                    360f * holdProgress,
                    false,
                    progressPaint
                )
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.voice_floating_button),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val settingsIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ActivitySettings::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.voice_floating_button))
            .setContentText(getString(R.string.voice_floating_button_notification))
            .setContentIntent(settingsIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "voice.command.floating"
        private const val NOTIFICATION_ID = 125
        private const val HOLD_TO_DISABLE_DURATION_MS = 1_000L
        private const val BUTTON_ENTRANCE_ANIMATION_DURATION_MS = 450L
        private const val BUTTON_DISMISS_ANIMATION_DURATION_MS = 300L

        fun sync(context: Context, enabled: Boolean) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, VoiceCommandOverlayService::class.java)
            if (enabled && Settings.canDrawOverlays(appContext)) {
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.stopService(intent)
            }
        }
    }
}
