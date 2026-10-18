package com.nirzor.voicebubble.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nirzor.voicebubble.BuildConfig
import com.nirzor.voicebubble.R
import com.nirzor.voicebubble.VoiceBubbleApp
import com.nirzor.voicebubble.data.BubbleThemes
import com.nirzor.voicebubble.data.SecureKeyManager
import com.nirzor.voicebubble.data.SupportedLanguages
import com.nirzor.voicebubble.service.GeminiApiClient
import com.nirzor.voicebubble.service.KeyTestState
import com.nirzor.voicebubble.ui.components.LanguageSelectorDialog
import com.nirzor.voicebubble.updater.UpdateInfo
import com.nirzor.voicebubble.updater.UpdateManager
import com.nirzor.voicebubble.updater.UpdateStatus
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onRequestOverlayPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = VoiceBubbleApp.instance
    val prefs = app.preferences
    val keyManager = remember { SecureKeyManager.getInstance(context) }
    val scope = rememberCoroutineScope()

    val selectedLangCode by prefs.selectedLanguage.collectAsState()
    val bubbleSize by prefs.bubbleSizeDp.collectAsState()
    val bubbleOpacity by prefs.bubbleOpacity.collectAsState()
    val bubbleColorTheme by prefs.bubbleColorTheme.collectAsState()
    val autoCopy by prefs.autoCopy.collectAsState()
    val haptic by prefs.hapticFeedback.collectAsState()
    val sound by prefs.soundFeedback.collectAsState()
    val dockToEdge by prefs.dockToEdge.collectAsState()
    val keepAlwaysOn by prefs.keepBubbleAlwaysOn.collectAsState()
    val startFromTile by prefs.startListeningFromTile.collectAsState()
    val aiPolishEnabled by prefs.aiPolishEnabled.collectAsState()
    val directGeminiAudio by prefs.directGeminiAudio.collectAsState()
    val retentionDays by prefs.historyRetentionDays.collectAsState()

    val currentTheme = remember(bubbleColorTheme) { BubbleThemes.getThemeById(bubbleColorTheme) }
    val currentLang = remember(selectedLangCode) { SupportedLanguages.getLanguageByCode(selectedLangCode) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    // Gemini API Key State
    var apiKeyText by remember { mutableStateOf(keyManager.getGeminiApiKey() ?: "") }
    var isKeyVisible by remember { mutableStateOf(false) }
    var testKeyState by remember { mutableStateOf(KeyTestState.IDLE) }

    // Self Updater State
    val updateManager = remember { UpdateManager.getInstance(context) }
    val updateStatus by updateManager.status.collectAsState()
    var showUnknownSourcesDialog by remember { mutableStateOf(false) }
    var pendingUpdateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    val unknownSourcesSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val info = pendingUpdateInfo
        pendingUpdateInfo = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.canRequestPackageInstalls()
        ) {
            // Resume the requested update automatically after the user grants the install permission.
            if (info != null) updateManager.startDownload(info)
        } else {
            Toast.makeText(
                context,
                "Voice Bubble-এর জন্য 'Install unknown apps' অনুমতি চালু করে আবার চেষ্টা করুন।",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section: Self Updater (GitHub Releases)
        SectionCard(title = stringResource(R.string.updater_section_title)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(
                                R.string.updater_current_version,
                                BuildConfig.VERSION_NAME,
                                BuildConfig.VERSION_CODE
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Release repo: engrnirzorme02",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Button(
                        onClick = {
                            updateManager.checkForUpdates(isAutoCheck = false)
                        },
                        enabled = updateStatus !is UpdateStatus.Checking && updateStatus !is UpdateStatus.Downloading,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("check_update_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = stringResource(R.string.updater_check_btn))
                    }
                }

                // Updater State presentation
                when (val currentStatus = updateStatus) {
                    is UpdateStatus.Checking -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.updater_status_checking),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    is UpdateStatus.UpToDate -> {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF10B981).copy(alpha = 0.12f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF047857), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.updater_status_up_to_date, currentStatus.currentVersion),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF047857),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    is UpdateStatus.UpdateAvailable -> {
                        val info = currentStatus.info
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = stringResource(R.string.updater_status_available, info.versionName),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (info.changelog.isNotBlank()) {
                                    Text(
                                        text = info.changelog,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 4
                                    )
                                }
                                Button(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                                            !context.packageManager.canRequestPackageInstalls()
                                        ) {
                                            pendingUpdateInfo = info
                                            showUnknownSourcesDialog = true
                                        } else {
                                            updateManager.startDownload(info)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(text = stringResource(R.string.updater_download_btn))
                                }
                            }
                        }
                    }

                    is UpdateStatus.Downloading -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.updater_status_downloading, currentStatus.progressPercent),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            LinearProgressIndicator(
                                progress = { currentStatus.progressPercent / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    is UpdateStatus.Verifying -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.updater_status_verifying),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    is UpdateStatus.ReadyToInstall -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.updater_status_ready),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Button(
                                onClick = { updateManager.installApk(currentStatus.apkFile) }
                            ) {
                                Text(text = stringResource(R.string.updater_install_btn))
                            }
                        }
                    }

                    is UpdateStatus.Installing -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "ইনস্টলেশন শুরু হয়েছে...", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    is UpdateStatus.Installed -> {
                        Text(
                            text = stringResource(R.string.updater_installed_toast, currentStatus.version),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF047857),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    is UpdateStatus.Error -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.updater_err_prefix, currentStatus.message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            if (currentStatus.canRetry) {
                                OutlinedButton(
                                    onClick = { updateManager.checkForUpdates(false) },
                                    modifier = Modifier.size(width = 140.dp, height = 36.dp)
                                ) {
                                    Text(text = stringResource(R.string.updater_retry_btn), fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    UpdateStatus.Idle -> {}
                }
            }
        }

        // Section: Gemini AI API Key & Polishing (Phase D.1)
        SectionCard(title = stringResource(R.string.settings_gemini_section)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_gemini_enable_title),
                    description = stringResource(R.string.settings_gemini_enable_desc),
                    icon = Icons.Default.AutoAwesome,
                    checked = aiPolishEnabled,
                    onCheckedChange = { prefs.setAiPolishEnabled(it) }
                )

                HorizontalDivider()

                Text(
                    text = stringResource(R.string.gemini_key_keystore_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Masked API Key input
                OutlinedTextField(
                    value = apiKeyText,
                    onValueChange = {
                        apiKeyText = it
                        keyManager.saveGeminiApiKey(it.trim())
                        testKeyState = KeyTestState.IDLE
                    },
                    label = { Text(stringResource(R.string.gemini_key_label)) },
                    placeholder = { Text(stringResource(R.string.gemini_key_hint)) },
                    visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Key, contentDescription = null)
                    },
                    trailingIcon = {
                        IconButton(onClick = { isKeyVisible = !isKeyVisible }) {
                            Icon(
                                imageVector = if (isKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (isKeyVisible) "Hide Key" else "Show Key"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("gemini_key_input"),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                if (apiKeyText.isBlank()) {
                    Text(
                        text = stringResource(R.string.gemini_key_no_key_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (apiKeyText.isBlank()) {
                                testKeyState = KeyTestState.INVALID
                                return@Button
                            }
                            testKeyState = KeyTestState.TESTING
                            scope.launch {
                                testKeyState = GeminiApiClient.testApiKey(apiKeyText.trim())
                            }
                        },
                        enabled = testKeyState != KeyTestState.TESTING && apiKeyText.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("test_gemini_key_btn")
                    ) {
                        if (testKeyState == KeyTestState.TESTING) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.gemini_test_btn_testing))
                        } else {
                            Text(stringResource(R.string.gemini_test_btn_idle))
                        }
                    }

                    // Status pill
                    when (testKeyState) {
                        KeyTestState.VALID -> {
                            Text(
                                text = stringResource(R.string.gemini_test_valid),
                                color = Color(0xFF047857),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        KeyTestState.INVALID -> {
                            Text(
                                text = stringResource(R.string.gemini_test_invalid),
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        KeyTestState.NETWORK_ERROR -> {
                            Text(
                                text = stringResource(R.string.gemini_test_network_error),
                                color = Color(0xFFD97706),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        else -> {}
                    }
                }

                HorizontalDivider()

                // Direct Gemini Audio (Phase G optional placeholder)
                SettingsSwitchRow(
                    title = stringResource(R.string.gemini_direct_audio_title),
                    description = stringResource(R.string.gemini_direct_audio_desc),
                    icon = Icons.Default.Mic,
                    checked = directGeminiAudio,
                    onCheckedChange = { prefs.setDirectGeminiAudio(it) }
                )
                Text(
                    text = stringResource(R.string.gemini_direct_audio_disabled_notice),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Section: Floating Bubble Appearance Customization (Phase E.1)
        SectionCard(title = stringResource(R.string.settings_appearance_section)) {
            // Live Bubble Preview
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_preview_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size((bubbleSize + 28).dp)
                            .padding(4.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = currentTheme.composeAccentColor.copy(alpha = 0.2f),
                            border = BorderStroke(2.dp, currentTheme.composeAccentColor.copy(alpha = 0.6f)),
                            modifier = Modifier.size((bubbleSize * 1.35f).dp)
                        ) {}

                        Surface(
                            shape = CircleShape,
                            color = currentTheme.composePrimaryColor.copy(alpha = bubbleOpacity),
                            shadowElevation = 6.dp,
                            modifier = Modifier.size(bubbleSize.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size((bubbleSize * 0.52f).dp)
                                )
                            }
                        }
                    }
                }
            }

            // Size Slider: 48 to 100 dp
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.settings_size_label, bubbleSize),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = bubbleSize.toFloat(),
                    onValueChange = { prefs.setBubbleSize(it.roundToInt()) },
                    valueRange = 48f..100f,
                    steps = 52,
                    modifier = Modifier.testTag("bubble_size_slider")
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Opacity Slider: 0.3 to 1.0
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.settings_opacity_label, (bubbleOpacity * 100).toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = bubbleOpacity,
                    onValueChange = { prefs.setBubbleOpacity(it) },
                    valueRange = 0.3f..1.0f,
                    modifier = Modifier.testTag("bubble_opacity_slider")
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Color Themes
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.settings_theme_label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BubbleThemes.themes.forEach { theme ->
                        val isSelected = theme.id == bubbleColorTheme
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = theme.composePrimaryColor,
                            border = if (isSelected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier
                                .size(48.dp)
                                .clickable { prefs.setBubbleColorTheme(theme.id) }
                        ) {
                            if (isSelected) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Dock to Edge Switch
            SettingsSwitchRow(
                title = stringResource(R.string.settings_dock_edge_title),
                description = stringResource(R.string.settings_dock_edge_desc),
                icon = Icons.Default.Layers,
                checked = dockToEdge,
                onCheckedChange = { prefs.setDockToEdge(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Keep Bubble Always On Switch (Phase B.2)
            SettingsSwitchRow(
                title = stringResource(R.string.settings_always_on_title),
                description = stringResource(R.string.settings_always_on_desc),
                icon = Icons.Default.Opacity,
                checked = keepAlwaysOn,
                onCheckedChange = { prefs.setKeepBubbleAlwaysOn(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Start Listening from Tile Switch (Phase B.1)
            SettingsSwitchRow(
                title = stringResource(R.string.settings_tile_start_mic_title),
                description = stringResource(R.string.settings_tile_start_mic_desc),
                icon = Icons.Default.Mic,
                checked = startFromTile,
                onCheckedChange = { prefs.setStartListeningFromTile(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Reset Position
            SettingsActionRow(
                title = stringResource(R.string.settings_reset_pos_title),
                subtitle = stringResource(R.string.settings_reset_pos_desc),
                icon = Icons.Default.Refresh,
                onClick = { prefs.setBubblePosition(-1, -1) }
            )
        }

        // Section: Speech & Clipboard Settings
        SectionCard(title = stringResource(R.string.settings_speech_section)) {
            SettingsActionRow(
                title = stringResource(R.string.settings_lang_title),
                subtitle = "${currentLang.flag} ${currentLang.displayName}",
                icon = Icons.Default.Language,
                onClick = { showLanguageDialog = true }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsSwitchRow(
                title = stringResource(R.string.settings_auto_copy_title),
                description = stringResource(R.string.settings_auto_copy_desc),
                icon = Icons.Default.ContentCopy,
                checked = autoCopy,
                onCheckedChange = { prefs.setAutoCopy(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsSwitchRow(
                title = stringResource(R.string.settings_haptic_title),
                description = stringResource(R.string.settings_haptic_desc),
                icon = Icons.Default.Vibration,
                checked = haptic,
                onCheckedChange = { prefs.setHapticFeedback(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsSwitchRow(
                title = stringResource(R.string.settings_sound_title),
                description = stringResource(R.string.settings_sound_desc),
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                checked = sound,
                onCheckedChange = { prefs.setSoundFeedback(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Retention Days Option (Phase D.3)
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = stringResource(R.string.settings_retention_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val options = listOf(7, 30, 90, -1)
                    options.forEach { days ->
                        val isSelected = retentionDays == days
                        val label = if (days == -1) stringResource(R.string.settings_retention_never) else stringResource(R.string.settings_retention_days_format, days)
                        OutlinedButton(
                            onClick = { prefs.setHistoryRetentionDays(days) },
                            colors = if (isSelected) ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.primary
                            ) else ButtonDefaults.outlinedButtonColors(),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                        ) {
                            Text(text = label, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }
        }

        // Section: System Overlay link
        SectionCard(title = stringResource(R.string.perm_overlay_title)) {
            SettingsActionRow(
                title = stringResource(R.string.perm_overlay_title),
                subtitle = stringResource(R.string.perm_overlay_desc),
                icon = Icons.Default.Settings,
                onClick = onRequestOverlayPermission
            )
        }

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

    if (showUnknownSourcesDialog) {
        AlertDialog(
            onDismissRequest = {
                showUnknownSourcesDialog = false
                pendingUpdateInfo = null
            },
            title = { Text(stringResource(R.string.updater_perm_dialog_title)) },
            text = { Text(stringResource(R.string.updater_perm_dialog_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showUnknownSourcesDialog = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}")
                            )
                            unknownSourcesSettingsLauncher.launch(intent)
                        }
                    }
                ) {
                    Text(stringResource(R.string.updater_perm_dialog_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUnknownSourcesDialog = false
                    pendingUpdateInfo = null
                }) {
                    Text(stringResource(R.string.history_clear_cancel))
                }
            }
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.5.sp
                )
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.5.sp
                )
            }
        }

        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = "Navigate",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
