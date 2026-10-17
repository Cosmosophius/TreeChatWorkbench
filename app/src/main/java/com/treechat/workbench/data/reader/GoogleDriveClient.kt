package com.treechat.workbench.data.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 谷歌云盘（Drive v3）的**软件内直达**客户端。
 *
 * ## 为什么是手写 OAuth 而不是谷歌 SDK
 * 1. 零新依赖：OkHttp 已在工程里，OAuth 授权码流程 + Drive REST 都是纯 HTTP；
 *    离线构建不受影响。
 * 2. **可移植**：本机回环（loopback）授权是 RFC 8252 的标准做法，
 *    Android（浏览器回调 127.0.0.1）与桌面 JVM（同一套 ServerSocket）通用 ——
 *    GoogleSignIn 那套 Play 依赖在桌面根本不存在，不能选。
 * 3. 国行 ROM（ColorOS/OriginOS 等）没有完整 GMS 也能用：授权走浏览器，不依赖 Play 服务。
 *
 * ## 一次性前提（用户操作，仅一次）
 * 在 Google Cloud Console 建 **Android 类型** OAuth 客户端（包名 com.treechat.workbench
 * + 签名 SHA-1），把 Client ID 粘进本软件设置 → 云同步。
 * 授权范围用 `drive.file`：只能看见/管理**本应用创建**的文件（放进"条畅"文件夹），
 * 用户其余网盘内容对软件不可见 —— 最小权限。
 *
 * ## 同步语义（v1）
 * 手动推拉：上传 = 把本地书文件写入"条畅"文件夹（同名覆盖）；
 * 恢复（下载导入）由下一轮补。不做后台自动同步。
 */
class GoogleDriveClient(private val context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("treechat_cloud", Context.MODE_PRIVATE)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------ 登录态

    fun isConfigured(): Boolean =
        prefs.getString(KEY_CLIENT_ID, null)?.isNotBlank() == true

    fun isAuthorized(): Boolean =
        prefs.getString(KEY_REFRESH, null)?.isNotBlank() == true

    fun savedClientEmail(): String? = prefs.getString(KEY_EMAIL, null)

    fun signOut() {
        prefs.edit().remove(KEY_REFRESH).remove(KEY_ACCESS).remove(KEY_EXPIRES).remove(KEY_EMAIL).apply()
    }

    fun saveClientId(id: String) {
        prefs.edit().putString(KEY_CLIENT_ID, id.trim()).apply()
    }

    // ------------------------------------------------------------ 授权（回环流程）

    /**
     * 完整授权：开本机回环端口 → 拉起浏览器走谷歌授权 → 拿授权码换令牌。
     * 成功返回 null；用户取消/失败返回原因文本（界面如实展示）。
     */
    suspend fun authorize(): String? = withContext(Dispatchers.IO) {
        val clientId = prefs.getString(KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() }
            ?: return@withContext "尚未填写 Client ID（设置 → 云同步）"

        val redirectPort = 40000 + (System.currentTimeMillis().toInt() % 20000)
        val redirectUri = "http://127.0.0.1:$redirectPort"
        val scope = "https://www.googleapis.com/auth/drive.file"
        val authUrl = "https://accounts.google.com/o/oauth2/v2/auth" +
            "?client_id=$clientId" +
            "&response_type=code" +
            "&scope=$scope" +
            "&redirect_uri=$redirectUri" +
            "&access_type=offline" +
            "&prompt=consent" +
            "&include_granted_scopes=false"

        // 用裸 ServerSocket 做回环监听：com.sun.net.httpserver 在安卓不存在，
        // 而 ServerSocket 在安卓/桌面 JVM 都是标准件 —— 可移植性优先。
        val server = java.net.ServerSocket(redirectPort, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val codeHolder = arrayOfNulls<String>(1)
        Thread {
            try {
                // 只接受一次回调：解析 ?code= 后回一个极简应答页并关闭
                server.accept().use { socket ->
                    val requestLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
                    val code = requestLine.split(' ').getOrNull(1)
                        ?.split('&')
                        ?.firstOrNull { it.startsWith("code=") }
                        ?.substringAfter("code=")
                    if (code != null) codeHolder[0] = java.net.URLDecoder.decode(code, "UTF-8")
                    val body = "<html><meta charset='utf-8'><body style='font-family:sans-serif;text-align:center;padding-top:40px'>" +
                        "<h2>授权已完成</h2><p>可以回到条畅继续了，这个页面可以关掉。</p></body></html>"
                    val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().use { out ->
                        out.write(resp.toByteArray(Charsets.UTF_8))
                        out.write(body.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                }
                // 兜底再收一个连接（浏览器可能开预连接）：第二次通常无 code，无害
                server.accept().use { it.close() }
            } catch (_: Exception) {
                // 服务器被 stop 时正常关闭
            }
        }.apply { isDaemon = true; start() }
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            val code = suspendCancellableCoroutine { cont ->
                // 授权码到达即恢复；超时由外层 withContext 调用方控制
                Thread {
                    var waited = 0
                    while (codeHolder[0] == null && waited < 180_000) {
                        Thread.sleep(300)
                        waited += 300
                    }
                    cont.resume(codeHolder[0])
                }.apply { isDaemon = true; start() }
            }
            if (code == null) return@withContext "授权超时（未在浏览器完成登录）"
            exchangeToken(clientId, code, redirectUri)
            null
        } finally {
            server.close()
        }
    }

    /** 用授权码换令牌；成功后落盘 refresh/access。失败抛出（调用方转提示）。 */
    private fun exchangeToken(clientId: String, code: String, redirectUri: String) {
        val form = okhttp3.FormBody.Builder()
            .add("code", code)
            .add("client_id", clientId)
            .add("redirect_uri", redirectUri)
            .add("grant_type", "authorization_code")
            .build()
        val resp = http.newCall(
            Request.Builder().url("https://oauth2.googleapis.com/token").post(form).build()
        ).execute()
        resp.use {
            val json = JSONObject(it.body!!.string())
            if (!it.isSuccessful) {
                throw IllegalStateException(json.optString("error_description", "授权失败"))
            }
            prefs.edit()
                .putString(KEY_REFRESH, json.getString("refresh_token"))
                .putString(KEY_ACCESS, json.getString("access_token"))
                .putLong(KEY_EXPIRES, System.currentTimeMillis() + json.getLong("expires_in") * 1000L)
                .apply()
        }
    }

    /** 拿可用的 access token（过期自动刷新；失败抛出）。 */
    private fun accessToken(): String {
        val exp = prefs.getLong(KEY_EXPIRES, 0L)
        val cur = prefs.getString(KEY_ACCESS, null)
        if (cur != null && System.currentTimeMillis() < exp - 60_000L) return cur
        val refresh = prefs.getString(KEY_REFRESH, null)
            ?: throw IllegalStateException("尚未授权谷歌账号")
        val clientId = prefs.getString(KEY_CLIENT_ID, null)
            ?: throw IllegalStateException("尚未填写 Client ID")
        val form = okhttp3.FormBody.Builder()
            .add("client_id", clientId)
            .add("refresh_token", refresh)
            .add("grant_type", "refresh_token")
            .build()
        http.newCall(
            Request.Builder().url("https://oauth2.googleapis.com/token").post(form).build()
        ).execute().use { resp ->
            val json = JSONObject(resp.body!!.string())
            if (!resp.isSuccessful) throw IllegalStateException("令牌刷新失败：${json.optString("error")}")
            val access = json.getString("access_token")
            prefs.edit()
                .putString(KEY_ACCESS, access)
                .putLong(KEY_EXPIRES, System.currentTimeMillis() + json.getLong("expires_in") * 1000L)
                .apply()
            return access
        }
    }

    // ------------------------------------------------------------ Drive v3

    /** 云端条目（列表/上传回执共用）。 */
    data class CloudFile(val id: String, val name: String, val size: Long, val modifiedTime: Long)

    /** 找"条畅"文件夹，没有就建；返回其 fileId。 */
    fun ensureFolderId(): String {
        val token = accessToken()
        val find = http.newCall(
            Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files" +
                    "?q=" + java.net.URLEncoder.encode(
                        "mimeType='application/vnd.google-apps.folder' and name='条畅' and trashed=false",
                        "UTF-8"
                    ) + "&fields=files(id,name)")
                .header("Authorization", "Bearer $token")
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("查询云端文件夹失败：HTTP ${resp.code}")
            JSONObject(resp.body!!.string()).optJSONArray("files")?.let { arr ->
                (0 until arr.length()).firstNotNullOfOrNull { arr.getJSONObject(it).optString("id") }
            }
        } ?: run {
            // 不存在 → 创建
            val meta = JSONObject().put("name", "条畅")
                .put("mimeType", "application/vnd.google-apps.folder")
            http.newCall(
                Request.Builder()
                    .url("https://www.googleapis.com/drive/v3/files?fields=id")
                    .header("Authorization", "Bearer $token")
                    .post(meta.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("创建云端文件夹失败：HTTP ${resp.code}")
                JSONObject(resp.body!!.string()).getString("id")
            }
        }
        return find
    }

    /** 上传/覆盖一个本地文件（同名覆盖：先查同名，有则 PATCH 内容，无则新建）。 */
    fun uploadFile(name: String, source: File): CloudFile {
        val token = accessToken()
        val folderId = ensureFolderId()
        val mime = when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"; "epub" -> "application/epub+zip"
            "txt" -> "text/plain"; "md", "markdown" -> "text/markdown"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "application/octet-stream"
        }

        // 同名查重（同文件夹内）
        val existingId = http.newCall(
            Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files" +
                    "?q=" + java.net.URLEncoder.encode(
                        "'$folderId' in parents and name=${jsonEscapeQuery(name)} and trashed=false",
                        "UTF-8"
                    ) + "&fields=files(id)")
                .header("Authorization", "Bearer $token")
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) null
            else JSONObject(resp.body!!.string()).optJSONArray("files")
                ?.let { arr -> (0 until arr.length()).firstNotNullOfOrNull { arr.getJSONObject(it).optString("id") } }
        }

        return if (existingId != null) {
            // PATCH 内容（v3 约定上传端点）
            val req = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files/$existingId?uploadType=media")
                .header("Authorization", "Bearer $token")
                .patch(source.readBytes().toRequestBody(mime.toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("覆盖云端文件失败：HTTP ${resp.code}")
            }
            CloudFile(existingId, name, source.length(), System.currentTimeMillis())
        } else {
            // multipart 新建：元数据（名字+父文件夹）+ 媒体两段
            val boundary = "talktree" + System.currentTimeMillis()
            val meta = JSONObject()
                .put("name", name)
                .put("parents", org.json.JSONArray().put(folderId))
            val buffer = java.io.ByteArrayOutputStream()
            buffer.write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray(Charsets.UTF_8))
            buffer.write(meta.toString().toByteArray(Charsets.UTF_8))
            buffer.write("\r\n".toByteArray(Charsets.UTF_8))
            buffer.write("--$boundary\r\nContent-Type: $mime\r\n\r\n".toByteArray(Charsets.UTF_8))
            val fileBytes = source.readBytes()
            buffer.write(fileBytes)
            buffer.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))

            val req = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,size,modifiedTime")
                .header("Authorization", "Bearer $token")
                .post(
                    buffer.toByteArray().toRequestBody(
                        "multipart/related; boundary=$boundary".toMediaType()
                    )
                )
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("上传失败：HTTP ${resp.code}")
                val json = JSONObject(resp.body!!.string())
                CloudFile(
                    id = json.getString("id"),
                    name = json.optString("name", name),
                    size = json.optLong("size", fileBytes.size.toLong()),
                    modifiedTime = System.currentTimeMillis()
                )
            }
        }
    }

    private fun jsonEscapeQuery(name: String): String = "'${name.replace("\\", "\\\\").replace("'", "\\'")}'"

    /** 列出"条畅"文件夹里的文件（v1：名字/大小/修改时间）。 */
    fun listFilesInShelf(): List<CloudFile> {
        val token = accessToken()
        val folderId = ensureFolderId()
        return http.newCall(
            Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files" +
                    "?q=" + java.net.URLEncoder.encode(
                        "'$folderId' in parents and trashed=false", "UTF-8"
                    ) + "&fields=" + java.net.URLEncoder.encode(
                        "files(id,name,size,modifiedTime)", "UTF-8"
                    ))
                .header("Authorization", "Bearer $token")
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("列出云端文件失败：HTTP ${resp.code}")
            val arr = JSONObject(resp.body!!.string()).optJSONArray("files")
            (0 until (arr?.length() ?: 0)).map { i ->
                val o = arr!!.getJSONObject(i)
                CloudFile(
                    id = o.getString("id"),
                    name = o.optString("name", ""),
                    size = o.optLong("size", 0L),
                    modifiedTime = parseRfc3339Millis(o.optString("modifiedTime", ""))
                )
            }.sortedByDescending { it.modifiedTime }
        }
    }

    /** 下载一个云端文件到 [dest]。 */
    fun download(fileId: String, dest: File) {
        val token = accessToken()
        http.newCall(
            Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                .header("Authorization", "Bearer $token")
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("下载失败：HTTP ${resp.code}")
            dest.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
        }
    }

    private fun parseRfc3339Millis(v: String): Long = try {
        java.time.OffsetDateTime.parse(v).toInstant().toEpochMilli()
    } catch (_: Exception) {
        0L
    }

    companion object {
        private const val KEY_CLIENT_ID = "gdrive_client_id"
        private const val KEY_REFRESH = "gdrive_refresh"
        private const val KEY_ACCESS = "gdrive_access"
        private const val KEY_EXPIRES = "gdrive_expires"
        private const val KEY_EMAIL = "gdrive_email"
    }
}
