package com.treechat.workbench.rag

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * 云端 API 厂商 Embedding / 语义向量客户端。
 *
 * 兼容标准 OpenAI 格式的 /embeddings 接口（支持 OpenAI, 阿里云百炼, 智谱 AI, DeepSeek/SiliconFlow 等）。
 * 负责批量提取文本向量并在端侧计算余弦相似度。
 */
class CloudEmbeddingClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) {

    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()

    /**
     * 批量获取文本列表的向量嵌入。
     *
     * @param texts 待向量化的文本片段列表
     * @param config 云端 API 配置参数
     * @return 按输入顺序排列的 FloatArray 向量列表
     */
    suspend fun getEmbeddings(
        texts: List<String>,
        config: CloudRagConfig
    ): List<FloatArray> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        require(config.isValidForApi()) { "云端 API 配置不完整，请检查 Base URL、API Key 与 Embedding 模型名称" }

        val endpoint = config.baseUrl.trim().trimEnd('/') + "/embeddings"
        val results = ArrayList<FloatArray>(texts.size)

        // 分批请求，防止一次性发送文本过多导致超出厂商请求体限制
        val batchSize = config.batchSize.coerceIn(1, 64)
        for (i in texts.indices step batchSize) {
            val batch = texts.subList(i, minOf(i + batchSize, texts.size))
            val batchVectors = fetchBatchEmbeddings(endpoint, batch, config)
            results.addAll(batchVectors)
        }

        results
    }

    /**
     * 单个文本的向量化（例如检索时用户提问的 Query 向量）。
     */
    suspend fun getQueryEmbedding(
        query: String,
        config: CloudRagConfig
    ): FloatArray? = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext null
        val vectors = getEmbeddings(listOf(trimmed), config)
        vectors.firstOrNull()
    }

    private fun fetchBatchEmbeddings(
        endpoint: String,
        batch: List<String>,
        config: CloudRagConfig
    ): List<FloatArray> {
        val payload = JsonObject().apply {
            addProperty("model", config.embeddingModel)
            add("input", gson.toJsonTree(batch))
            if (config.dimensions != null && config.dimensions > 0) {
                addProperty("dimensions", config.dimensions)
            }
        }

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${config.apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(mediaTypeJson))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("云端 Embedding API 请求失败 (${response.code}): $bodyString")
            }

            val json = gson.fromJson(bodyString, JsonObject::class.java)
            val dataArray = json.getAsJsonArray("data")
                ?: throw IOException("响应中缺失 data 向量数组字段: $bodyString")

            // OpenAI 规范中 data 数组包含 index 和 embedding，按 index 排序以保证与原文本顺序一致
            val sortedList = dataArray.map { elem ->
                val obj = elem.asJsonObject
                val index = obj.get("index")?.asInt ?: 0
                val embeddingArr = obj.getAsJsonArray("embedding")
                val floatVec = FloatArray(embeddingArr.size()) { idx ->
                    embeddingArr.get(idx).asFloat
                }
                index to floatVec
            }.sortedBy { it.first }.map { it.second }

            return sortedList
        }
    }

    companion object {
        /**
         * 计算两个向量的余弦相似度（Cosine Similarity）。
         */
        fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
            if (v1.isEmpty() || v2.isEmpty() || v1.size != v2.size) return 0f
            var dot = 0f
            var norm1 = 0f
            var norm2 = 0f
            for (i in v1.indices) {
                val a = v1[i]
                val b = v2[i]
                dot += a * b
                norm1 += a * a
                norm2 += b * b
            }
            if (norm1 <= 0f || norm2 <= 0f) return 0f
            return (dot / (sqrt(norm1.toDouble()) * sqrt(norm2.toDouble()))).toFloat()
        }

        /**
         * 将 FloatArray 编码为逗号分隔字符串，便于存入 SQLite 数据库字段
         */
        fun encodeVector(vec: FloatArray): String {
            return buildString {
                for (i in vec.indices) {
                    if (i > 0) append(',')
                    append(vec[i])
                }
            }
        }

        /**
         * 从逗号分隔字符串解码为 FloatArray
         */
        fun decodeVector(encoded: String?): FloatArray {
            if (encoded.isNullOrBlank()) return FloatArray(0)
            val parts = encoded.split(',')
            val result = FloatArray(parts.size)
            for (i in parts.indices) {
                result[i] = parts[i].toFloatOrNull() ?: 0f
            }
            return result
        }
    }
}
