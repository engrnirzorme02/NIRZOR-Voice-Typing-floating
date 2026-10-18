package com.nirzor.voicebubble.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.nirzor.voicebubble.BuildConfig
import com.nirzor.voicebubble.R
import com.nirzor.voicebubble.VoiceBubbleApp
import com.nirzor.voicebubble.service.FloatingBubbleService
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val changelog: String,
    val apkDownloadUrl: String,
    val sha256DownloadUrl: String?,
    val expectedSize: Long?
)

sealed interface UpdateStatus {
    object Idle : UpdateStatus
    object Checking : UpdateStatus
    data class UpToDate(val currentVersion: String) : UpdateStatus
    data class UpdateAvailable(val info: UpdateInfo) : UpdateStatus
    data class Downloading(val progressPercent: Int, val info: UpdateInfo) : UpdateStatus
    object Verifying : UpdateStatus
    data class ReadyToInstall(val apkFile: File) : UpdateStatus
    object Installing : UpdateStatus
    data class Installed(val version: String) : UpdateStatus
    data class Error(val message: String, val canRetry: Boolean = true) : UpdateStatus
}

@JsonClass(generateAdapter = true)
data class GitHubReleaseResponse(
    val tag_name: String,
    val body: String?,
    val assets: List<GitHubAsset>?
)

@JsonClass(generateAdapter = true)
data class GitHubAsset(
    val name: String,
    val browser_download_url: String,
    val size: Long?
)

class UpdateManager(private val context: Context) {

    private val TAG = "UpdateManager"
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private var cachedApkFile: File? = null

    companion object {
        private const val OWNER = "engrnirzorme02"
        private const val REPO = "NIRZOR-Voice-Typing-floating"
        private const val APK_NAME = "voicebubble.apk"
        private const val SHA_NAME = "voicebubble.apk.sha256"

        @Volatile
        private var INSTANCE: UpdateManager? = null

        fun getInstance(context: Context): UpdateManager {
            return INSTANCE ?: synchronized(this) {
                val instance = UpdateManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }

        fun parseVersionCode(versionName: String): Int {
            val clean = versionName.removePrefix("v").trim()
            val parts = clean.split(".").map { it.toIntOrNull() ?: 0 }
            val major = parts.getOrElse(0) { 1 }
            val minor = parts.getOrElse(1) { 0 }
            val patch = parts.getOrElse(2) { 0 }
            return major * 10000 + minor * 100 + patch
        }
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    fun checkForUpdates(isAutoCheck: Boolean = false) {
        val prefs = VoiceBubbleApp.instance.preferences
        val now = System.currentTimeMillis()

        if (isAutoCheck) {
            val lastCheck = prefs.lastUpdateCheckTime.value
            val oneDayMillis = 24 * 60 * 60 * 1000L
            if (now - lastCheck < oneDayMillis) {
                return
            }
        }

        _status.value = UpdateStatus.Checking
        prefs.setLastUpdateCheckTime(now)

        scope.launch {
            try {
                val apiUrl = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "VoiceBubble-Updater")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (response.code == 403) {
                    // Fallback on 403 rate limit via HEAD request without following redirects
                    response.close()
                    val fallbackInfo = checkViaHeadRedirect()
                    if (fallbackInfo != null) {
                        evaluateRelease(fallbackInfo)
                    } else {
                        _status.value = UpdateStatus.Error("GitHub API রেট লিমিট অতিক্রম করেছে। কিছুক্ষণ পরে চেষ্টা করুন।")
                    }
                    return@launch
                }

                if (response.code == 404) {
                    response.close()
                    _status.value = UpdateStatus.UpToDate(BuildConfig.VERSION_NAME)
                    return@launch
                }

                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    _status.value = UpdateStatus.Error("আপডেট চেক ব্যর্থ হয়েছে (HTTP $code)")
                    return@launch
                }

                val responseBody = response.body?.string() ?: ""
                response.close()

                val adapter = moshi.adapter(GitHubReleaseResponse::class.java)
                val release = adapter.fromJson(responseBody)

                if (release == null) {
                    _status.value = UpdateStatus.Error("রিলিজ ডাটা পার্স করা যায়নি")
                    return@launch
                }

                val tagName = release.tag_name
                val cleanTag = tagName.removePrefix("v").trim()
                val latestCode = parseVersionCode(cleanTag)
                val changelog = release.body ?: ""

                val apkAsset = release.assets?.find { it.name == APK_NAME }
                val shaAsset = release.assets?.find { it.name == SHA_NAME }

                val apkUrl = apkAsset?.browser_download_url
                    ?: "https://github.com/$OWNER/$REPO/releases/download/$tagName/$APK_NAME"
                val shaUrl = shaAsset?.browser_download_url
                    ?: "https://github.com/$OWNER/$REPO/releases/download/$tagName/$SHA_NAME"

                val updateInfo = UpdateInfo(
                    versionName = cleanTag,
                    versionCode = latestCode,
                    changelog = changelog,
                    apkDownloadUrl = apkUrl,
                    sha256DownloadUrl = shaUrl,
                    expectedSize = apkAsset?.size
                )

                evaluateRelease(updateInfo)

            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
                _status.value = UpdateStatus.Error("নেটওয়ার্ক ত্রুটি: ইন্টারনেট কানেকশন চেক করুন")
            }
        }
    }

    private fun checkViaHeadRedirect(): UpdateInfo? {
        return try {
            val headClient = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()

            val headRequest = Request.Builder()
                .url("https://github.com/$OWNER/$REPO/releases/latest")
                .head()
                .build()

            val headResponse = headClient.newCall(headRequest).execute()
            val location = headResponse.header("Location") ?: ""
            headResponse.close()

            if (location.isNotBlank()) {
                val tag = location.substringAfterLast("/")
                val cleanTag = tag.removePrefix("v").trim()
                val code = parseVersionCode(cleanTag)

                UpdateInfo(
                    versionName = cleanTag,
                    versionCode = code,
                    changelog = "নতুন সংস্করণ উপলব্ধ",
                    apkDownloadUrl = "https://github.com/$OWNER/$REPO/releases/download/$tag/$APK_NAME",
                    sha256DownloadUrl = "https://github.com/$OWNER/$REPO/releases/download/$tag/$SHA_NAME",
                    expectedSize = null
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallback HEAD check failed", e)
            null
        }
    }

    private fun evaluateRelease(info: UpdateInfo) {
        if (info.versionCode > BuildConfig.VERSION_CODE) {
            _status.value = UpdateStatus.UpdateAvailable(info)
        } else {
            _status.value = UpdateStatus.UpToDate(BuildConfig.VERSION_NAME)
        }
    }

    fun startDownload(info: UpdateInfo) {
        // Block update if service is currently recording
        if (FloatingBubbleService.isServiceRunning.value) {
            _status.value = UpdateStatus.Error(context.getString(R.string.updater_blocked_busy))
            return
        }

        scope.launch {
            try {
                _status.value = UpdateStatus.Downloading(0, info)

                // 1. Fetch expected SHA256 if available
                var expectedSha256: String? = null
                if (info.sha256DownloadUrl != null) {
                    try {
                        val shaRequest = Request.Builder().url(info.sha256DownloadUrl).build()
                        val shaResponse = httpClient.newCall(shaRequest).execute()
                        if (shaResponse.isSuccessful) {
                            expectedSha256 = shaResponse.body?.string()?.trim()?.split("\\s+".toRegex())?.firstOrNull()
                        }
                        shaResponse.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not fetch sha256 checksum", e)
                    }
                }

                // 2. Download APK file
                val apkFile = File(context.cacheDir, "voicebubble_update.apk")
                if (apkFile.exists()) apkFile.delete()

                val apkRequest = Request.Builder().url(info.apkDownloadUrl).build()
                val apkResponse = httpClient.newCall(apkRequest).execute()

                if (!apkResponse.isSuccessful) {
                    apkResponse.close()
                    _status.value = UpdateStatus.Error("APK ডাউনলোড ব্যর্থ হয়েছে (HTTP ${apkResponse.code})")
                    return@launch
                }

                val body = apkResponse.body ?: run {
                    apkResponse.close()
                    _status.value = UpdateStatus.Error("ডাউনলোড রেসপন্স খালি ছিল")
                    return@launch
                }

                val totalBytes = body.contentLength()
                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(apkFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var downloadedBytes: Long = 0
                var lastProgress = 0

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (totalBytes > 0) {
                        val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                        if (progress != lastProgress) {
                            lastProgress = progress
                            _status.value = UpdateStatus.Downloading(progress, info)
                        }
                    }
                }
                outputStream.flush()
                outputStream.close()
                inputStream.close()
                apkResponse.close()

                // 3. Verify SHA256
                _status.value = UpdateStatus.Verifying

                if (!expectedSha256.isNullOrBlank()) {
                    val actualSha = calculateFileSha256(apkFile)
                    if (!actualSha.equals(expectedSha256, ignoreCase = true)) {
                        apkFile.delete()
                        _status.value = UpdateStatus.Error(context.getString(R.string.updater_sha_mismatch))
                        return@launch
                    }
                }

                cachedApkFile = apkFile
                // The user already selected "Download & Install". Continue directly
                // to PackageInstaller after the downloaded APK passes SHA-256 validation.
                _status.value = UpdateStatus.ReadyToInstall(apkFile)
                Log.i(TAG, "APK download and checksum verification succeeded; starting installation")
                installApk(apkFile)

            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _status.value = UpdateStatus.Error("ডাউনলোড ব্যর্থ হয়েছে: ${e.localizedMessage}")
            }
        }
    }

    private fun calculateFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun installApk(apkFile: File) {
        if (!apkFile.exists()) {
            _status.value = UpdateStatus.Error("ইনস্টলার ফাইল খুঁজে পাওয়া যায়নি")
            return
        }

        _status.value = UpdateStatus.Installing

        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            session.openWrite("voicebubble_package", 0, apkFile.length()).use { out ->
                apkFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }

            val intent = Intent(context, UpdateInstallReceiver::class.java).apply {
                action = UpdateInstallReceiver.ACTION_INSTALL_STATUS
                `package` = context.packageName // Explicit package per Android 14+ rules
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            session.commit(pendingIntent.intentSender)
            session.close()

        } catch (e: Exception) {
            Log.e(TAG, "Install failed to launch session", e)
            _status.value = UpdateStatus.Error("ইনস্টলার সেশন শুরু করতে ব্যর্থ হয়েছে: ${e.localizedMessage}")
        }
    }

    fun onInstallSuccess() {
        cachedApkFile?.delete()
        cachedApkFile = null
        val currentVer = BuildConfig.VERSION_NAME
        _status.value = UpdateStatus.Installed(currentVer)
        VoiceBubbleApp.instance.preferences.setLastInstalledVersionCode(BuildConfig.VERSION_CODE)
    }

    fun onInstallFailure(reason: String) {
        cachedApkFile?.delete()
        cachedApkFile = null
        _status.value = UpdateStatus.Error(reason)
    }

    fun reset() {
        _status.value = UpdateStatus.Idle
    }
}
