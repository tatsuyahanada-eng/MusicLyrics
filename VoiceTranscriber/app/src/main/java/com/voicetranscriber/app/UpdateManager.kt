package com.voicetranscriber.app

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * サーバー上のバージョン定義ファイルを見て、アプリの自動アップデートを行う仕組み。
 *
 * サーバーには以下のような JSON を 1 つ置いておく（URL は strings.xml の
 * `update_manifest_url` に設定）:
 *
 * ```json
 * {
 *   "versionCode": 2,
 *   "versionName": "1.1",
 *   "apkUrl": "https://example.com/whispr/voice-transcriber.apk",
 *   "notes": "バグ修正と新機能"
 * }
 * ```
 *
 * `versionCode` が端末にインストール済みの値より大きいときだけ更新と判定する。
 */
object UpdateManager {

    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val notes: String,
    )

    /** インストール済みアプリの versionCode。 */
    fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    /** サーバーの定義ファイルを取得。失敗時は null。 */
    suspend fun fetchLatest(manifestUrl: String): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
            }
            conn.inputStream.bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                UpdateInfo(
                    versionCode = json.getLong("versionCode"),
                    versionName = json.optString("versionName", ""),
                    apkUrl = json.getString("apkUrl"),
                    notes = json.optString("notes", ""),
                )
            }
        }.getOrNull()
    }

    /** 端末より新しいバージョンがあれば返す。なければ null。 */
    suspend fun checkForUpdate(context: Context, manifestUrl: String): UpdateInfo? {
        if (manifestUrl.isBlank()) return null
        val latest = fetchLatest(manifestUrl) ?: return null
        return if (latest.versionCode > currentVersionCode(context)) latest else null
    }

    /** APK をダウンロードしてファイルを返す。失敗時は null。 */
    suspend fun downloadApk(context: Context, apkUrl: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
            }
            conn.connect()
            val dir = context.getExternalFilesDir(null)
            val file = File(dir, "update.apk")
            conn.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file
        }.getOrNull()
    }

    /** ダウンロード済み APK のインストーラを起動する。 */
    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
