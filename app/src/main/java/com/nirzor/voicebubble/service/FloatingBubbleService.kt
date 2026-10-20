package com.nirzor.voicebubble.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.view.animation.ScaleAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.nirzor.voicebubble.MainActivity
import com.nirzor.voicebubble.R
import com.nirzor.voicebubble.VoiceBubbleApp
import com.nirzor.voicebubble.data.AppPreferences
import com.nirzor.voicebubble.data.BubbleTheme
import com.nirzor.voicebubble.data.BubbleThemes
import com.nirzor.voicebubble.data.UndoRedoManager
import com.nirzor.voicebubble.data.VoiceHistoryEntity
import com.nirzor.voicebubble.speech.BubbleState
import com.nirzor.voicebubble.speech.SpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot

class FloatingBubbleService : Service() {

    private val TAG = "FloatingBubbleService"
    private val NOTIFICATION_ID = 2026
    private val CHANNEL_ID = "voice_bubble_service_channel"

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var floatingRootView: FrameLayout? = null
    private var bubbleContainer: FrameLayout? = null
    private var bubbleCircle: FrameLayout? = null
    private var micIcon: ImageView? = null
    private var progressSpinner: ProgressBar? = null
    private var pulseRing: View? = null
    private var previewPill: LinearLayout? = null
    private var previewPillText: TextView? = null
    private var copiedBadge: LinearLayout? = null
    private var copiedBadgeText: TextView? = null
    private var removeTargetView: FrameLayout? = null
    private var removeTargetIcon: ImageView? = null

    private lateinit var windowParams: WindowManager.LayoutParams
    private lateinit var removeTargetParams: WindowManager.LayoutParams
    private lateinit var speechEngine: SpeechEngine
    private lateinit var appPreferences: AppPreferences
    private lateinit var clipboardManager: ClipboardManager

    private var isViewAttached = false
    private var isRemoveTargetAttached = false
    private var autoDismissJob: Job? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchSlop = 16
    private var isDragging = false
    private var isOverRemoveTarget = false

    companion object {
        const val ACTION_START = "com.nirzor.voicebubble.ACTION_START"
        const val ACTION_STOP = "com.nirzor.voicebubble.ACTION_STOP"
        const val EXTRA_START_LISTENING = "extra_start_listening"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        fun start(context: Context, startListeningImmediately: Boolean = false) {
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_START_LISTENING, startListeningImmediately)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        appPreferences = VoiceBubbleApp.instance.preferences
        speechEngine = SpeechEngine(this)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        updateScreenDimensions()
        createNotificationChannel()

        // The foreground-service promotion must happen in onStartCommand before
        // attempting the overlay attachment. Android can terminate a service that
        // fails to become foreground within the required startup window.
        observeBubbleState()
        observeSettings()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val hasOverlayPermission = Settings.canDrawOverlays(this)
        val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasOverlayPermission || !hasMicPermission) {
            Log.w(
                TAG,
                "Cannot start bubble: overlayPermission=$hasOverlayPermission, microphonePermission=$hasMicPermission"
            )
            _isServiceRunning.value = false
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val notification = createNotification()
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to promote voice bubble to foreground service", e)
            _isServiceRunning.value = false
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            if (floatingRootView == null) {
                initBubbleLayout()
            }
            if (!attachBubbleView()) {
                throw IllegalStateException("Floating bubble view could not be attached")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bubble service started but overlay attachment failed", e)
            _isServiceRunning.value = false
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Publish the running state only after both foreground promotion and overlay
        // attachment have succeeded. This keeps the Home switch and QS tile truthful.
        _isServiceRunning.value = true

        val shouldStartListening = intent?.getBooleanExtra(EXTRA_START_LISTENING, false) ?: false
        if (shouldStartListening) {
            startListeningSession()
        } else {
            scheduleAutoDismiss()
        }

        return START_STICKY
    }

    private fun updateScreenDimensions() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenDimensions()
        clampPositionToScreen()
    }

    private fun clampPositionToScreen() {
        if (!isViewAttached || floatingRootView == null) return
        val bubbleSizePx = dpToPx(appPreferences.bubbleSizeDp.value)
        val maxX = (screenWidth - bubbleSizePx).coerceAtLeast(0)
        val maxY = (screenHeight - bubbleSizePx).coerceAtLeast(0)

        windowParams.x = windowParams.x.coerceIn(0, maxX)
        windowParams.y = windowParams.y.coerceIn(0, maxY)

        try {
            windowManager?.updateViewLayout(floatingRootView, windowParams)
        } catch (e: Exception) {
            Log.w(TAG, "clampPosition error", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initBubbleLayout() {
        val bubbleSizeDp = appPreferences.bubbleSizeDp.value.coerceIn(48, 100)
        val bubbleSizePx = dpToPx(bubbleSizeDp)
        val theme = appPreferences.getSelectedColorTheme()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (appPreferences.bubbleX.value >= 0) appPreferences.bubbleX.value else screenWidth - bubbleSizePx - dpToPx(16)
            y = if (appPreferences.bubbleY.value >= 0) appPreferences.bubbleY.value else screenHeight / 3
        }

        // Root container
        floatingRootView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            clipChildren = false
            clipToPadding = false
        }

        // Bubble container (includes pulse ring and circle)
        bubbleContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            clipChildren = false
            clipToPadding = false
            contentDescription = getString(R.string.bubble_content_desc)
            isClickable = true
            isFocusable = true
        }

        // Pulse Ring (visual indicator during listening)
        val ringSize = (bubbleSizePx * 1.4f).toInt()
        pulseRing = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(ringSize, ringSize).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dpToPx(2), theme.accentColorInt)
            }
            visibility = View.GONE
        }

        // Bubble Circle
        bubbleCircle = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx).apply {
                gravity = Gravity.CENTER
            }
            background = createBubbleBackground(theme.primaryColorInt)
            elevation = dpToPx(6).toFloat()
            alpha = appPreferences.bubbleOpacity.value
        }

        // Mic icon
        micIcon = ImageView(this).apply {
            val iconSize = (bubbleSizePx * 0.52f).toInt()
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Color.WHITE)
        }

        // Processing ProgressBar spinner
        progressSpinner = ProgressBar(this).apply {
            val spinnerSize = (bubbleSizePx * 0.55f).toInt()
            layoutParams = FrameLayout.LayoutParams(spinnerSize, spinnerSize).apply {
                gravity = Gravity.CENTER
            }
            visibility = View.GONE
        }

        bubbleCircle?.addView(micIcon)
        bubbleCircle?.addView(progressSpinner)
        bubbleContainer?.addView(pulseRing)
        bubbleContainer?.addView(bubbleCircle)

        // Partial text preview pill
        previewPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(16).toFloat()
                setColor(Color.parseColor("#E0111827"))
            }
            visibility = View.GONE
        }
        previewPillText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        previewPill?.addView(previewPillText)

        // Copied badge pill
        copiedBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(14).toFloat()
                setColor(Color.parseColor("#E6059669"))
            }
            visibility = View.GONE
        }
        copiedBadgeText = TextView(this).apply {
            text = getString(R.string.bubble_state_copied)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        copiedBadge?.addView(copiedBadgeText)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(copiedBadge)
            addView(previewPill)
            addView(bubbleContainer)
        }
        floatingRootView?.addView(column)

        setupTouchListener()
        setupRemoveTarget()
    }

    private fun setupRemoveTarget() {
        val targetSize = dpToPx(72)
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        removeTargetParams = WindowManager.LayoutParams(
            targetSize, targetSize, layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dpToPx(48)
        }

        removeTargetView = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#99EF4444"))
                setStroke(dpToPx(2), Color.WHITE)
            }
            visibility = View.GONE
        }
        removeTargetIcon = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(32), dpToPx(32)).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(android.R.drawable.ic_menu_delete)
            setColorFilter(Color.WHITE)
        }
        removeTargetView?.addView(removeTargetIcon)
    }

    private fun showRemoveTarget(show: Boolean) {
        if (show) {
            if (!isRemoveTargetAttached && removeTargetView != null) {
                try {
                    windowManager?.addView(removeTargetView, removeTargetParams)
                    isRemoveTargetAttached = true
                } catch (e: Exception) {
                    Log.w(TAG, "Error adding remove target", e)
                }
            }
            removeTargetView?.visibility = View.VISIBLE
        } else {
            removeTargetView?.visibility = View.GONE
            if (isRemoveTargetAttached && removeTargetView != null) {
                try {
                    windowManager?.removeView(removeTargetView)
                    isRemoveTargetAttached = false
                } catch (e: Exception) {
                    Log.w(TAG, "Error removing remove target", e)
                }
            }
        }
    }

    private fun createBubbleBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        bubbleContainer?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    cancelAutoDismiss()
                    initialX = windowParams.x
                    initialY = windowParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    isOverRemoveTarget = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                        showRemoveTarget(true)
                    }

                    if (isDragging) {
                        windowParams.x = initialX + dx
                        windowParams.y = initialY + dy
                        try {
                            windowManager?.updateViewLayout(floatingRootView, windowParams)
                        } catch (e: Exception) {
                            Log.w(TAG, "Update layout error", e)
                        }

                        // Check collision with remove target
                        val removeTargetCenterY = screenHeight - dpToPx(48) - dpToPx(36)
                        val removeTargetCenterX = screenWidth / 2
                        val bubbleCenterX = windowParams.x + dpToPx(appPreferences.bubbleSizeDp.value) / 2
                        val bubbleCenterY = windowParams.y + dpToPx(appPreferences.bubbleSizeDp.value) / 2
                        val distance = hypot((bubbleCenterX - removeTargetCenterX).toDouble(), (bubbleCenterY - removeTargetCenterY).toDouble())

                        val isColliding = distance < dpToPx(60)
                        if (isColliding != isOverRemoveTarget) {
                            isOverRemoveTarget = isColliding
                            removeTargetView?.background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(if (isColliding) Color.parseColor("#E5DC2626") else Color.parseColor("#99EF4444"))
                                setStroke(dpToPx(if (isColliding) 3 else 2), Color.WHITE)
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    showRemoveTarget(false)
                    if (isDragging) {
                        if (isOverRemoveTarget) {
                            // Phase E.1: Drag to remove target stops service
                            stopSelf()
                            return@setOnTouchListener true
                        }

                        // Dock to edge
                        if (appPreferences.dockToEdge.value) {
                            dockBubbleToScreenEdge()
                        } else {
                            appPreferences.setBubblePosition(windowParams.x, windowParams.y)
                        }
                    } else {
                        // Single tap detected!
                        handleBubbleTap()
                    }
                    scheduleAutoDismiss()
                    true
                }
                else -> false
            }
        }
    }

    private fun handleBubbleTap() {
        when (val currentState = speechEngine.speechState.value) {
            is BubbleState.Idle -> startListeningSession()
            is BubbleState.Listening -> speechEngine.stopSession()
            is BubbleState.Error, is BubbleState.Done -> {
                // A new tap should recover immediately after an error or previous result.
                speechEngine.stateMachine.resetToIdle()
                startListeningSession()
            }
            else -> Log.d(TAG, "Tap ignored while recognition is active: $currentState")
        }
    }

    private fun dockBubbleToScreenEdge() {
        val bubbleSizePx = dpToPx(appPreferences.bubbleSizeDp.value)
        val currentCenterX = windowParams.x + bubbleSizePx / 2
        val targetX = if (currentCenterX < screenWidth / 2) {
            dpToPx(8)
        } else {
            screenWidth - bubbleSizePx - dpToPx(8)
        }
        windowParams.x = targetX
        clampPositionToScreen()
        appPreferences.setBubblePosition(windowParams.x, windowParams.y)
    }

    private fun startListeningSession() {
        cancelAutoDismiss()
        copiedBadge?.visibility = View.GONE
        previewPill?.visibility = View.GONE
        speechEngine.startSession(
            languageCode = appPreferences.selectedLanguage.value,
            playTone = appPreferences.soundFeedback.value,
            enableHaptic = appPreferences.hapticFeedback.value,
            onPartialResult = { partial ->
                mainHandler.post {
                    previewPill?.visibility = View.VISIBLE
                    previewPillText?.text = partial
                }
            },
            onFinalResult = { rawText, lang, durationMs ->
                handleFinalSpeechResult(rawText, lang, durationMs)
            },
            onErrorOccurred = { errorMsg, _ ->
                mainHandler.post {
                    previewPill?.visibility = View.VISIBLE
                    previewPillText?.text = errorMsg
                    scheduleAutoDismiss()
                }
            }
        )
    }

    private fun handleFinalSpeechResult(rawText: String, lang: String, durationMs: Long) {
        cancelAutoDismiss()
        serviceScope.launch {
            val aiEnabled = appPreferences.aiPolishEnabled.value
            val polishedText = if (aiEnabled) {
                withContext(Dispatchers.IO) {
                    GeminiApiClient.polishText(this@FloatingBubbleService, rawText)
                }
            } else {
                null
            }

            val finalText = (polishedText ?: rawText).trim()

            // Copy to clipboard
            if (appPreferences.autoCopy.value && finalText.isNotBlank()) {
                val clip = ClipData.newPlainText("Voice Transcription", finalText)
                clipboardManager.setPrimaryClip(clip)
            }

            // Undo / Redo tracking
            UndoRedoManager.recordInput(this@FloatingBubbleService, finalText)

            // Save to Room DB
            try {
                VoiceBubbleApp.instance.repository.insert(
                    VoiceHistoryEntity(
                        text = finalText,
                        language = lang,
                        durationMs = durationMs,
                        rawText = rawText,
                        polishedText = polishedText,
                        createdAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error saving history", e)
            }

            speechEngine.stateMachine.moveToDone(rawText, polishedText, true)

            // Show copied badge
            mainHandler.post {
                copiedBadge?.visibility = View.VISIBLE
                previewPill?.visibility = View.GONE
            }

            delay(2500L)
            mainHandler.post {
                copiedBadge?.visibility = View.GONE
                // Do not reset a newer session that the user started while the badge was visible.
                if (speechEngine.speechState.value is BubbleState.Done) {
                    speechEngine.stateMachine.resetToIdle()
                }
                scheduleAutoDismiss()
            }
        }
    }

    private fun observeBubbleState() {
        serviceScope.launch {
            speechEngine.speechState.collect { state ->
                mainHandler.post {
                    updateBubbleVisualState(state)
                }
            }
        }
    }

    private fun updateBubbleVisualState(state: BubbleState) {
        val theme = appPreferences.getSelectedColorTheme()
        when (state) {
            is BubbleState.Idle -> {
                pulseRing?.visibility = View.GONE
                progressSpinner?.visibility = View.GONE
                micIcon?.visibility = View.VISIBLE
                micIcon?.setImageResource(android.R.drawable.ic_btn_speak_now)
                bubbleCircle?.background = createBubbleBackground(theme.primaryColorInt)
            }

            is BubbleState.Listening -> {
                cancelAutoDismiss()
                progressSpinner?.visibility = View.GONE
                micIcon?.visibility = View.VISIBLE
                pulseRing?.visibility = View.VISIBLE
                bubbleCircle?.background = createBubbleBackground(theme.accentColorInt)
                animatePulseRing(state.rmsDb)
            }

            is BubbleState.Processing -> {
                cancelAutoDismiss()
                pulseRing?.visibility = View.GONE
                micIcon?.visibility = View.GONE
                progressSpinner?.visibility = View.VISIBLE
                bubbleCircle?.background = createBubbleBackground(theme.secondaryColorInt)
            }

            is BubbleState.Done -> {
                cancelAutoDismiss()
                progressSpinner?.visibility = View.GONE
                micIcon?.visibility = View.VISIBLE
                pulseRing?.visibility = View.GONE
                micIcon?.setImageResource(android.R.drawable.checkbox_on_background)
                bubbleCircle?.background = createBubbleBackground(Color.parseColor("#059669"))
            }

            is BubbleState.Error -> {
                cancelAutoDismiss()
                progressSpinner?.visibility = View.GONE
                micIcon?.visibility = View.VISIBLE
                pulseRing?.visibility = View.GONE
                micIcon?.setImageResource(android.R.drawable.stat_notify_error)
                bubbleCircle?.background = createBubbleBackground(Color.parseColor("#DC2626"))
            }
        }
    }

    private fun animatePulseRing(rms: Float) {
        val scale = 1.0f + (rms * 0.4f).coerceIn(0f, 0.4f)
        pulseRing?.animate()
            ?.scaleX(scale)
            ?.scaleY(scale)
            ?.setDuration(120)
            ?.start()
    }

    private fun observeSettings() {
        serviceScope.launch {
            appPreferences.bubbleOpacity.collect { opacity ->
                mainHandler.post {
                    bubbleCircle?.alpha = opacity
                }
            }
        }
        serviceScope.launch {
            appPreferences.bubbleColorTheme.collect {
                mainHandler.post {
                    val theme = appPreferences.getSelectedColorTheme()
                    bubbleCircle?.background = createBubbleBackground(theme.primaryColorInt)
                }
            }
        }
    }

    private fun scheduleAutoDismiss() {
        if (appPreferences.keepBubbleAlwaysOn.value) return
        cancelAutoDismiss()
        autoDismissJob = serviceScope.launch {
            delay(15_000L) // 15 seconds idle timer
            if (speechEngine.speechState.value is BubbleState.Idle) {
                Log.d(TAG, "Auto-dismissing bubble after 15s idle")
                stopSelf()
            }
        }
    }

    private fun cancelAutoDismiss() {
        autoDismissJob?.cancel()
        autoDismissJob = null
    }

    private fun attachBubbleView(): Boolean {
        if (isViewAttached) return true

        val rootView = floatingRootView ?: return false
        val manager = windowManager ?: return false

        return try {
            manager.addView(rootView, windowParams)
            isViewAttached = true
            true
        } catch (e: Exception) {
            isViewAttached = false
            Log.e(TAG, "Failed to attach floating bubble view", e)
            false
        }
    }

    private fun detachBubbleView() {
        if (floatingRootView != null && isViewAttached) {
            try {
                windowManager?.removeView(floatingRootView)
                isViewAttached = false
            } catch (e: Exception) {
                Log.w(TAG, "Error removing floating bubble view", e)
            }
        }
        showRemoveTarget(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAutoDismiss()
        _isServiceRunning.value = false
        speechEngine.destroy()
        detachBubbleView()
        serviceScope.cancel()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
