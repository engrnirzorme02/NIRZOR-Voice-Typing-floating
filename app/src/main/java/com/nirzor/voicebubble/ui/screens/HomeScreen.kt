package com.nirzor.voicebubble.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nirzor.voicebubble.BuildConfig
import com.nirzor.voicebubble.R
import com.nirzor.voicebubble.VoiceBubbleApp
import com.nirzor.voicebubble.data.BubbleThemes
import com.nirzor.voicebubble.data.SupportedLanguages
import com.nirzor.voicebubble.data.UndoRedoManager
import com.nirzor.voicebubble.data.VoiceHistoryEntity
import com.nirzor.voicebubble.service.GeminiApiClient
import com.nirzor.voicebubble.speech.BubbleState
import com.nirzor.voicebubble.speech.SpeechEngine
import com.nirzor.voicebubble.ui.components.LanguageSelectorDialog
import com.nirzor.voicebubble.ui.components.LiveWaveformVisualizer
import com.nirzor.voicebubble.ui.components.PermissionCard
import com.nirzor.voicebubble.ui.components.SwiftKeyTipCard
import com.nirzor.voicebubble.updater.UpdateManager
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    isServiceRunning: Boolean,
    hasOverlayPermission: Boolean,
    hasMicPermission: Boolean,
    hasNotificationPermission: Boolean,
    onToggleService: (Boolean) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = VoiceBubbleApp.instance
    val prefs = app.preferences
    val repo = app.repository
    val scope = rememberCoroutineScope()

    val selectedLangCode by prefs.selectedLanguage.collectAsState()
    val currentLang = remember(selectedLangCode) { SupportedLanguages.getLanguageByCode(selectedLangCode) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    val canUndo by UndoRedoManager.canUndo.collectAsState()
    val canRedo by UndoRedoManager.canRedo.collectAsState()
    val bubbleColorTheme by prefs.bubbleColorTheme.collectAsState()
    val currentTheme = remember(bubbleColorTheme) { BubbleThemes.getThemeById(bubbleColorTheme) }

    // Test Pad SpeechEngine
    val testSpeechEngine = remember { SpeechEngine(context) }
    val testSpeechState by testSpeechEngine.speechState.collectAsState()
    val testRms = (testSpeechState as? BubbleState.Listening)?.rmsDb ?: 0f
    var inAppTranscription by remember { mutableStateOf("") }
    var justCopiedInApp by remember { mutableStateOf(false) }

    val isTestListening = testSpeechState is BubbleState.Listening

    DisposableEffect(Unit) {
        onDispose {
            testSpeechEngine.destroy()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Card: Service Master Control
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("service_master_card"),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (isServiceRunning) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val bubbleColor by animateColorAsState(
                            targetValue = if (isServiceRunning) currentTheme.composeAccentColor else MaterialTheme.colorScheme.outline,
                            label = "bubble_color"
                        )
                        Surface(
                            shape = CircleShape,
                            color = bubbleColor.copy(alpha = 0.2f),
                            border = BorderStroke(2.dp, bubbleColor),
                            modifier = Modifier.size(52.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isServiceRunning) Icons.Default.Mic else Icons.Default.MicOff,
                                    contentDescription = stringResource(R.string.header_bubble_status),
                                    tint = if (isServiceRunning) currentTheme.composeAccentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Column {
                            Text(
                                text = stringResource(R.string.header_bubble_status),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FiberManualRecord,
                                    contentDescription = null,
                                    tint = if (isServiceRunning) Color(0xFF10B981) else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(10.dp)
                                )
                                Text(
                                    text = if (isServiceRunning) stringResource(R.string.bubble_status_active) else stringResource(R.string.bubble_status_inactive),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isServiceRunning) Color(0xFF047857) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Switch(
                        checked = isServiceRunning,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                onToggleService(false)
                            } else {
                                when {
                                    !hasOverlayPermission -> {
                                        Toast.makeText(
                                            context,
                                            R.string.perm_required_alert,
                                            Toast.LENGTH_LONG
                                        ).show()
                                        onRequestOverlayPermission()
                                    }
                                    !hasMicPermission -> {
                                        Toast.makeText(
                                            context,
                                            R.string.perm_required_alert,
                                            Toast.LENGTH_LONG
                                        ).show()
                                        onRequestMicPermission()
                                    }
                                    else -> onToggleService(true)
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier.testTag("service_master_switch")
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Language quick badge
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { showLanguageDialog = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = currentLang.flag, fontSize = 20.sp)
                        Text(
                            text = stringResource(R.string.voice_lang_label, currentLang.displayName),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = stringResource(R.string.change_action),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Version & Update Card (Phase E.4)
        Card(
            modifier = Modifier.fillMaxWidth().testTag("version_update_card"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = stringResource(
                                R.string.updater_current_version,
                                BuildConfig.VERSION_NAME,
                                BuildConfig.VERSION_CODE
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "GitHub Releases",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        UpdateManager.getInstance(context).checkForUpdates(isAutoCheck = false)
                        onNavigateToSettings()
                    },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.updater_check_btn),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Permission Card with Onboarding & Autostart Guide (Phase E.2)
        PermissionCard(
            hasOverlayPermission = hasOverlayPermission,
            hasMicPermission = hasMicPermission,
            hasNotificationPermission = hasNotificationPermission,
            onRequestOverlay = onRequestOverlayPermission,
            onRequestMic = onRequestMicPermission,
            onRequestNotification = onRequestNotificationPermission
        )

        // In-App Voice Testing Pad
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("in_app_voice_pad"),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.test_pad_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.test_pad_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (inAppTranscription.isNotBlank()) {
                        IconButton(
                            onClick = {
                                inAppTranscription = ""
                                justCopiedInApp = false
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Waveform Visualizer
                LiveWaveformVisualizer(
                    isListening = isTestListening,
                    rmsLevel = testRms,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Transcription Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(12.dp)
                ) {
                    if (inAppTranscription.isBlank()) {
                        Text(
                            text = if (isTestListening) stringResource(R.string.test_pad_listening_hint) else stringResource(R.string.test_pad_idle_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isTestListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            lineHeight = 20.sp
                        )
                    } else {
                        Text(
                            text = inAppTranscription,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 22.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Action Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (isTestListening) {
                                testSpeechEngine.stopSession()
                            } else {
                                inAppTranscription = ""
                                justCopiedInApp = false
                                testSpeechEngine.startSession(
                                    languageCode = selectedLangCode,
                                    playTone = prefs.soundFeedback.value,
                                    enableHaptic = prefs.hapticFeedback.value,
                                    onPartialResult = { partial ->
                                        inAppTranscription = partial
                                    },
                                    onFinalResult = { rawResult, lang, durationMs ->
                                        inAppTranscription = rawResult
                                        justCopiedInApp = false

                                        scope.launch {
                                            val aiEnabled = prefs.aiPolishEnabled.value
                                            val polished = if (aiEnabled && rawResult.isNotBlank()) {
                                                inAppTranscription = "$rawResult\n\n${context.getString(R.string.test_pad_polishing_indicator)}"
                                                GeminiApiClient.polishText(context, rawResult)
                                            } else {
                                                null
                                            }

                                            val finalText = (polished ?: rawResult).trim()
                                            inAppTranscription = finalText
                                            // Mark the in-app recognizer as complete so the next tap can start a new session.
                                            testSpeechEngine.stateMachine.moveToDone(
                                                rawResult,
                                                polished,
                                                prefs.autoCopy.value && finalText.isNotBlank()
                                            )

                                            if (finalText.isNotBlank()) {
                                                UndoRedoManager.recordInput(context, finalText)
                                            }

                                            if (prefs.autoCopy.value && finalText.isNotBlank()) {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                val clip = ClipData.newPlainText("Voice Transcription", finalText)
                                                clipboard.setPrimaryClip(clip)
                                                justCopiedInApp = true
                                                Toast.makeText(context, R.string.copied_toast, Toast.LENGTH_SHORT).show()
                                            }

                                            try {
                                                repo.insert(
                                                    VoiceHistoryEntity(
                                                        text = finalText,
                                                        language = lang,
                                                        durationMs = durationMs,
                                                        rawText = rawResult,
                                                        polishedText = polished,
                                                        createdAt = System.currentTimeMillis()
                                                    )
                                                )
                                            } catch (e: Exception) {
                                                // Ignore
                                            }
                                        }
                                    },
                                    onErrorOccurred = { errorMsg, _ ->
                                        inAppTranscription = errorMsg
                                    }
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTestListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("in_app_mic_button")
                    ) {
                        Icon(
                            imageVector = if (isTestListening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (isTestListening) "Stop" else "Start",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isTestListening) stringResource(R.string.test_pad_btn_stop) else stringResource(R.string.test_pad_btn_start),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Undo Button
                    IconButton(
                        onClick = {
                            val res = UndoRedoManager.undo(context)
                            inAppTranscription = res.restoredText ?: ""
                            justCopiedInApp = false
                            Toast.makeText(context, res.userMessage, Toast.LENGTH_SHORT).show()
                        },
                        enabled = canUndo,
                        modifier = Modifier.testTag("test_pad_undo_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Undo,
                            contentDescription = "Undo",
                            tint = if (canUndo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        )
                    }

                    // Redo Button
                    IconButton(
                        onClick = {
                            val res = UndoRedoManager.redo(context)
                            inAppTranscription = res.restoredText ?: ""
                            justCopiedInApp = false
                            Toast.makeText(context, res.userMessage, Toast.LENGTH_SHORT).show()
                        },
                        enabled = canRedo,
                        modifier = Modifier.testTag("test_pad_redo_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Redo,
                            contentDescription = "Redo",
                            tint = if (canRedo) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        )
                    }

                    if (inAppTranscription.isNotBlank()) {
                        FilledTonalButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Voice Transcription", inAppTranscription)
                                clipboard.setPrimaryClip(clip)
                                justCopiedInApp = true
                                Toast.makeText(context, R.string.copied_toast, Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("in_app_copy_button")
                        ) {
                            Icon(
                                imageVector = if (justCopiedInApp) Icons.Default.Check else Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                tint = if (justCopiedInApp) Color(0xFF10B981) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = if (justCopiedInApp) stringResource(R.string.test_pad_copied_btn) else stringResource(R.string.test_pad_copy_btn))
                        }
                    }
                }
            }
        }

        // SwiftKey & Gboard Integration Tip
        SwiftKeyTipCard()

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showLanguageDialog) {
        LanguageSelectorDialog(
            currentLanguageCode = selectedLangCode,
            onLanguageSelected = { lang ->
                prefs.setLanguage(lang.code)
            },
            onDismiss = { showLanguageDialog = false }
        )
    }
}
