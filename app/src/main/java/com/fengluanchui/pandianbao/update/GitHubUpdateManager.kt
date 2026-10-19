package com.fengluanchui.pandianbao.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class GitHubRelease(
    val tagName: String,
    val versionName: String,
    val notes: String,
    val downloadUrl: String,
    val expectedSize: Long,
    val sha256: String
)

/** Public GitHub Release updater. It never embeds a GitHub token. */
class GitHubUpdateManager(private val context: Context) {
    private val preferences = context.getSharedPreferences("update_settings", Context.MODE_PRIVATE)

    var repository: String
        get() = preferences.getString("github_repository", "") ?: ""
        set(value) = preferences.edit().putString("github_repository", value.trim()).apply()

    suspend fun checkLatest(): GitHubRelease? = withContext(Dispatchers.IO) {
        val repo = repository.trim().removePrefix("https://github.com/").removeSuffix(".git").trim('/')
        require(repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "请先填写 GitHub 仓库，格式为 owner/repo" }
        val response = request("https://api.github.com/repos/$repo/releases/latest")
        if (response.code == 404) return@withContext null
        check(response.code in 200..299) { "检查更新失败：GitHub 返回 ${response.code}" }
        val release = JSONObject(response.body)
        val asset = (0 until release.getJSONArray("assets").length())
            .asSequence()
            .map { release.getJSONArray("assets").getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?: error("该 Release 没有 APK 文件")
        val digest = asset.optString("digest").removePrefix("sha256:").lowercase()
        require(digest.matches(Regex("[a-f0-9]{64}"))) { "该 Release 的 APK 没有 SHA-256 digest，拒绝下载安装" }
        GitHubRelease(
            tagName = release.optString("tag_name"),
            versionName = release.optString("tag_name").removePrefix("v"),
            notes = release.optString("body"),
            downloadUrl = asset.getString("browser_download_url"),
            expectedSize = asset.optLong("size", -1),
            sha256 = digest
        )
    }

    fun isNewer(release: GitHubRelease): Boolean = compareVersions(release.versionName, context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0") > 0

    suspend fun downloadAndInstall(release: GitHubRelease) = withContext(Dispatchers.IO) {
        val updateDirectory = File(context.cacheDir, "updates").apply { mkdirs() }
        val temporary = File(updateDirectory, "pandianbao-${release.versionName}.apk.part")
        val target = File(updateDirectory, "pandianbao-${release.versionName}.apk")
        temporary.delete()
        target.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = URL(release.downloadUrl).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/octet-stream")
        connection.connect()
        check(connection.responseCode in 200..299) { "下载更新失败：HTTP ${connection.responseCode}" }
        connection.inputStream.use { input -> FileOutputStream(temporary).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        } }
        check(release.expectedSize < 0 || temporary.length() == release.expectedSize) { "下载文件大小与 GitHub Release 不一致" }
        check(digest.digest().joinToString("") { "%02x".format(it) } == release.sha256) { "APK SHA-256 校验失败" }
        check(packageInfo(temporary.path).packageName == context.packageName) { "下载的安装包不属于盘点宝" }
        check(signerFingerprints(temporary.path) == signerFingerprints(null)) { "更新 APK 的签名与当前应用不一致" }
        check(temporary.renameTo(target)) { "无法准备更新安装包" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun signerFingerprints(archivePath: String?): Set<String> {
        val packageInfo = packageInfo(archivePath)
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION") packageInfo.signatures ?: emptyArray()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun packageInfo(archivePath: String?): PackageInfo {
        return if (archivePath == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.packageManager.getPackageArchiveInfo(archivePath, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            else @Suppress("DEPRECATION") context.packageManager.getPackageArchiveInfo(archivePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } ?: error("无法读取 APK 信息")
    }

    private fun request(url: String): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "PanDianBao-Android")
        return try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResponse(code, body)
        } finally { connection.disconnect() }
    }

    private data class HttpResponse(val code: Int, val body: String)
    private fun compareVersions(left: String, right: String): Int {
        val a = left.removePrefix("v").split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val b = right.removePrefix("v").split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        return (0 until maxOf(a.size, b.size)).map { (a.getOrElse(it) { 0 }).compareTo(b.getOrElse(it) { 0 }) }.firstOrNull { it != 0 } ?: 0
    }
}
