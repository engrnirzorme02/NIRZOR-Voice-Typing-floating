package com.nirzor.voicebubble.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.nirzor.voicebubble.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import java.util.Locale

class SpeechEngine(private val context: Context) {

    private val TAG = "SpeechEngine"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    val stateMachine = BubbleStateMachine()
    val speechState: StateFlow<BubbleState> = stateMachine.state

    private var speechRecognizer: SpeechRecognizer? = null
    private var startTimeMs: Long = 0L
    private var recognizerStartJob: Job? = null
    private var startRequestId = 0L
    private var isStartPending = false
    private var hasRetriedBusy = false
    private var hasRetriedOnline = false
    private var hasRetriedOnDevice = false
    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator init failed", e)
        }

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun isRecognitionAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context) || isOnDeviceRecognitionAvailable()
    }

    private fun isOnDeviceRecognitionAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }

    private fun scheduleErrorReset() {
        scope.launch {
            delay(2500L)
            if (stateMachine.state.value is BubbleState.Error) {
                stateMachine.resetToIdle()
            }
        }
    }

    fun startSession(
        languageCode: String = "bn-BD",
        playTone: Boolean = true,
        enableHaptic: Boolean = true,
        onPartialResult: ((String) -> Unit)? = null,
        onFinalResult: ((rawText: String, lang: String, durationMs: Long) -> Unit)? = null,
        onErrorOccurred: ((errorMessage: String, errorCode: Int) -> Unit)? = null
    ) {
        // Reserve the state immediately so rapid taps cannot queue parallel recognizers.
        when (stateMachine.state.value) {
            is BubbleState.Error, is BubbleState.Done -> stateMachine.resetToIdle()
            else -> Unit
        }

        if (isStartPending || !stateMachine.canAcceptTap()) {
            Log.d(TAG, "Ignoring tap while a speech session is starting or active")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val errorMsg = context.getString(R.string.speech_err_permissions)
            stateMachine.moveToError(errorMsg, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            onErrorOccurred?.invoke(errorMsg, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            scheduleErrorReset()
            return
        }

        val defaultRecognizerAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        val onDeviceRecognizerAvailable = isOnDeviceRecognitionAvailable()
        if (!defaultRecognizerAvailable && !onDeviceRecognizerAvailable) {
            val errorMsg = context.getString(R.string.speech_not_available)
            stateMachine.moveToError(errorMsg, -1)
            onErrorOccurred?.invoke(errorMsg, -1)
            scheduleErrorReset()
            return
        }

        if (!stateMachine.startListening()) return

        hasRetriedBusy = false
        hasRetriedOnline = false
        hasRetriedOnDevice = false

        cleanupRecognizer()
        scheduleRecognizerStart(
            delayMillis = 300L,
            languageCode = languageCode,
            playTone = playTone,
            enableHaptic = enableHaptic,
            onPartialResult = onPartialResult,
            onFinalResult = onFinalResult,
            onErrorOccurred = onErrorOccurred,
            isRetry = false,
            forceOnDevice = !defaultRecognizerAvailable
        )
    }

    private fun scheduleRecognizerStart(
        delayMillis: Long,
        languageCode: String,
        playTone: Boolean,
        enableHaptic: Boolean,
        onPartialResult: ((String) -> Unit)?,
        onFinalResult: ((rawText: String, lang: String, durationMs: Long) -> Unit)?,
        onErrorOccurred: ((errorMessage: String, errorCode: Int) -> Unit)?,
        isRetry: Boolean,
        forceOnDevice: Boolean
    ) {
        recognizerStartJob?.cancel()
        val requestId = ++startRequestId
        isStartPending = true

        recognizerStartJob = scope.launch {
            delay(delayMillis)
            if (requestId != startRequestId) return@launch

            internalStartRecognizer(
                languageCode = languageCode,
                playTone = playTone,
                enableHaptic = enableHaptic,
                onPartialResult = onPartialResult,
                onFinalResult = onFinalResult,
                onErrorOccurred = onErrorOccurred,
                isRetry = isRetry,
                forceOnDevice = forceOnDevice,
                requestId = requestId
            )
        }
    }

    private fun invalidatePendingStart() {
        startRequestId += 1
        recognizerStartJob?.cancel()
        recognizerStartJob = null
        isStartPending = false
    }

    private fun internalStartRecognizer(
        languageCode: String,
        playTone: Boolean,
        enableHaptic: Boolean,
        onPartialResult: ((String) -> Unit)?,
        onFinalResult: ((rawText: String, lang: String, durationMs: Long) -> Unit)?,
        onErrorOccurred: ((errorMessage: String, errorCode: Int) -> Unit)?,
        isRetry: Boolean,
        forceOnDevice: Boolean,
        requestId: Long
    ) {
        mainHandler.post {
            // Delayed callbacks from an older attempt must not start or overwrite a newer session.
            if (requestId != startRequestId || !isStartPending) return@post
            isStartPending = false
            recognizerStartJob = null

            if (isRetry) {
                if (!stateMachine.restartListening()) {
                    Log.w(TAG, "Skipping recognizer retry because the session is no longer active")
                    return@post
                }
            } else if (stateMachine.state.value !is BubbleState.Listening) {
                return@post
            }

            if (enableHaptic) triggerHaptic(50)
            if (playTone) playBeep(ToneGenerator.TONE_PROP_BEEP)

            startTimeMs = System.currentTimeMillis()

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)

                if (languageCode != "default") {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
                } else {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                }
                if (forceOnDevice) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }

            try {
                val recognizer = if (forceOnDevice) {
                    if (!isOnDeviceRecognitionAvailable()) {
                        throw IllegalStateException("On-device speech recognition is unavailable")
                    }
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }

                speechRecognizer = recognizer.apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            Log.d(TAG, "SpeechRecognizer onReadyForSpeech")
                        }

                        override fun onBeginningOfSpeech() {
                            Log.d(TAG, "SpeechRecognizer onBeginningOfSpeech")
                        }

                        override fun onRmsChanged(rmsdB: Float) {
                            if (requestId != startRequestId) return
                            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                            stateMachine.updateRms(normalized)
                        }

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            if (requestId != startRequestId) return
                            Log.d(TAG, "SpeechRecognizer onEndOfSpeech")
                            stateMachine.moveToProcessing()
                        }

                        override fun onError(error: Int) {
                            if (requestId != startRequestId) return
                            Log.e(TAG, "SpeechRecognizer onError: code=$error, language=$languageCode, onDevice=$forceOnDevice")

                            // Retry one busy-service error without allowing overlapping start jobs.
                            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && !hasRetriedBusy) {
                                hasRetriedBusy = true
                                cleanupRecognizer()
                                onErrorOccurred?.invoke(context.getString(R.string.speech_err_busy), error)
                                scheduleRecognizerStart(
                                    delayMillis = 400L,
                                    languageCode = languageCode,
                                    playTone = playTone,
                                    enableHaptic = enableHaptic,
                                    onPartialResult = onPartialResult,
                                    onFinalResult = onFinalResult,
                                    onErrorOccurred = onErrorOccurred,
                                    isRetry = true,
                                    forceOnDevice = forceOnDevice
                                )
                                return
                            }

                            val isNetworkFailure =
                                error == SpeechRecognizer.ERROR_NETWORK ||
                                    error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                                    error == SpeechRecognizer.ERROR_SERVER

                            // A brief online retry recovers transient Speech Services failures.
                            // If it fails again, try the on-device recognizer when Android exposes one.
                            if (!forceOnDevice && isNetworkFailure && !hasRetriedOnline) {
                                hasRetriedOnline = true
                                Log.w(TAG, "Speech service network failure; retrying online once")
                                cleanupRecognizer()
                                onErrorOccurred?.invoke(
                                    context.getString(R.string.speech_retrying_network),
                                    error
                                )
                                scheduleRecognizerStart(
                                    delayMillis = 500L,
                                    languageCode = languageCode,
                                    playTone = playTone,
                                    enableHaptic = enableHaptic,
                                    onPartialResult = onPartialResult,
                                    onFinalResult = onFinalResult,
                                    onErrorOccurred = onErrorOccurred,
                                    isRetry = true,
                                    forceOnDevice = false
                                )
                                return
                            }

                            if (
                                !forceOnDevice &&
                                !hasRetriedOnDevice &&
                                isNetworkFailure &&
                                isOnDeviceRecognitionAvailable()
                            ) {
                                hasRetriedOnDevice = true
                                Log.w(TAG, "Online speech retry failed; switching to on-device recognition")
                                cleanupRecognizer()
                                onErrorOccurred?.invoke(
                                    context.getString(R.string.speech_retrying_offline),
                                    error
                                )
                                scheduleRecognizerStart(
                                    delayMillis = 350L,
                                    languageCode = languageCode,
                                    playTone = playTone,
                                    enableHaptic = enableHaptic,
                                    onPartialResult = onPartialResult,
                                    onFinalResult = onFinalResult,
                                    onErrorOccurred = onErrorOccurred,
                                    isRetry = true,
                                    forceOnDevice = true
                                )
                                return
                            }

                            val errorMsg = if (forceOnDevice && isNetworkFailure) {
                                context.getString(R.string.speech_err_offline_unavailable)
                            } else {
                                getErrorMessage(error)
                            }
                            invalidatePendingStart()
                            cleanupRecognizer()
                            stateMachine.moveToError(errorMsg, error)
                            if (enableHaptic) triggerHapticDouble()
                            onErrorOccurred?.invoke(errorMsg, error)
                            scheduleErrorReset()
                        }

                        override fun onResults(results: Bundle?) {
                            if (requestId != startRequestId) return
                            val duration = System.currentTimeMillis() - startTimeMs
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = matches?.firstOrNull()?.trim() ?: ""

                            if (text.isNotBlank()) {
                                invalidatePendingStart()
                                if (enableHaptic) triggerHapticSuccess()
                                if (playTone) playBeep(ToneGenerator.TONE_PROP_ACK)
                                onFinalResult?.invoke(text, languageCode, duration)
                            } else {
                                val msg = context.getString(R.string.speech_err_no_match)
                                invalidatePendingStart()
                                cleanupRecognizer()
                                stateMachine.moveToError(msg, SpeechRecognizer.ERROR_NO_MATCH)
                                onErrorOccurred?.invoke(msg, SpeechRecognizer.ERROR_NO_MATCH)
                                scheduleErrorReset()
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            if (requestId != startRequestId) return
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val partial = matches?.firstOrNull()?.trim() ?: ""
                            if (partial.isNotBlank()) {
                                stateMachine.updatePartialText(partial)
                                onPartialResult?.invoke(partial)
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    startListening(intent)
                }
            } catch (e: Exception) {
                if (requestId != startRequestId) return@post
                Log.e(TAG, "Failed to start speech recognizer", e)
                invalidatePendingStart()
                cleanupRecognizer()
                val errorMsg = context.getString(R.string.speech_err_unknown, -1)
                stateMachine.moveToError(errorMsg, -1)
                onErrorOccurred?.invoke(errorMsg, -1)
                scheduleErrorReset()
            }
        }
    }

    fun stopSession() {
        mainHandler.post {
            if (isStartPending) {
                invalidatePendingStart()
                cleanupRecognizer()
                stateMachine.resetToIdle()
                return@post
            }

            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping speech recognizer", e)
            }
        }
    }

    fun cancelSession() {
        mainHandler.post {
            invalidatePendingStart()
            cleanupRecognizer()
            stateMachine.resetToIdle()
        }
    }

    fun destroy() {
        mainHandler.post {
            invalidatePendingStart()
            cleanupRecognizer()
            stateMachine.resetToIdle()
            try {
                toneGenerator?.release()
                toneGenerator = null
            } catch (e: Exception) {
                Log.w(TAG, "ToneGenerator release error", e)
            }
            scope.cancel()
        }
    }

    private fun cleanupRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up SpeechRecognizer", e)
        }
    }

    private fun playBeep(toneType: Int) {
        try {
            toneGenerator?.startTone(toneType, 120)
        } catch (e: Exception) {
            Log.w(TAG, "Tone play error", e)
        }
    }

    private fun triggerHaptic(durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haptic error", e)
        }
    }

    private fun triggerHapticSuccess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 40, 60, 80)
                val amplitudes = intArrayOf(0, 180, 0, 255)
                vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 40, 60, 80), -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haptic error", e)
        }
    }

    private fun triggerHapticDouble() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 60, 80, 60)
                val amplitudes = intArrayOf(0, 200, 0, 200)
                vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 60, 80, 60), -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haptic error", e)
        }
    }

    private fun getErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> context.getString(R.string.speech_err_network_timeout)
            SpeechRecognizer.ERROR_NETWORK -> context.getString(R.string.speech_err_network)
            SpeechRecognizer.ERROR_AUDIO -> context.getString(R.string.speech_err_audio)
            SpeechRecognizer.ERROR_SERVER -> context.getString(R.string.speech_err_server)
            SpeechRecognizer.ERROR_CLIENT -> context.getString(R.string.speech_err_client)
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> context.getString(R.string.speech_err_speech_timeout)
            SpeechRecognizer.ERROR_NO_MATCH -> context.getString(R.string.speech_err_no_match)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> context.getString(R.string.speech_err_busy_final)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> context.getString(R.string.speech_err_permissions)
            else -> context.getString(R.string.speech_err_unknown, errorCode)
        }
    }
}
