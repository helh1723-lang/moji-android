package com.moji.app.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.moji.app.data.CategoryEntity
import com.moji.app.data.Direction
import com.moji.app.voice.VoiceDraft
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.roundToLong

/** Providers using an OpenAI-compatible Chat Completions endpoint. Models remain editable because availability is account-specific. */
enum class AiProvider(val label: String, val baseUrl: String, val defaultModel: String) {
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    QWEN("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    ZHIPU("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
    VOLCENGINE("火山方舟", "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-2-0-lite-260215"),
    HUNYUAN("腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-turbos-latest"),
    KIMI("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
    QIANFAN("百度千帆", "https://qianfan.baidubce.com/v2", "ernie-4.5-turbo-128k"),
    CUSTOM("OpenAI 兼容自定义", "", "")
}

data class AiConfig(val enabled: Boolean, val provider: AiProvider, val baseUrl: String, val model: String)

/** The encrypted value is app-private; the AES key is non-exportable Android Keystore material. */
class AiCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("moji_ai_credentials", Context.MODE_PRIVATE)

    fun save(apiKey: String) {
        require(apiKey.isNotBlank()) { "API Key 不能为空" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("encrypted_api_key", value).commit()) { "无法保存 API Key" }
    }

    fun read(): String? = runCatching {
        val parts = prefs.getString("encrypted_api_key", null)?.split(':', limit = 2) ?: return null
        if (parts.size != 2) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }.getOrNull()

    fun clear() { prefs.edit().remove("encrypted_api_key").apply() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    private companion object { const val KEY_ALIAS = "moji.ai.api_key.v1" }
}

object AiTextParser {
    fun parse(config: AiConfig, apiKey: String, text: String, categories: List<CategoryEntity>, now: Long = System.currentTimeMillis()): List<VoiceDraft> {
        require(config.enabled) { "AI 文本解析未启用" }
        require(apiKey.isNotBlank()) { "请先在设置中填写 API Key" }
        require(config.baseUrl.startsWith("https://")) { "接口地址必须使用 HTTPS" }
        require(config.model.isNotBlank()) { "请填写模型名称" }
        val visible = categories.filterNot { it.hidden }
        require(visible.isNotEmpty()) { "没有可用分类" }
        val request = JSONObject().apply {
            put("model", config.model)
            put("temperature", 0)
            put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt(visible))).put(
                JSONObject().put("role", "user").put("content", text.trim().take(1_000))
            ))
        }
        val connection = (URL(config.baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 30_000; doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        return try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(request.toString()) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
            if (connection.responseCode !in 200..299) error("AI 服务请求失败（HTTP ${connection.responseCode}）")
            val content = JSONObject(body).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
            if (content.isBlank()) error("AI 服务没有返回可用结果")
            parseResponse(content, visible, now)
        } finally { connection.disconnect() }
    }

    internal fun parseResponse(content: String, categories: List<CategoryEntity>, now: Long): List<VoiceDraft> {
        val json = content.substringAfter('{', "").substringBeforeLast('}', "").takeIf { it.isNotBlank() }
            ?.let { JSONObject("{$it}") } ?: error("AI 返回格式无效")
        val values = json.optJSONArray("transactions") ?: error("AI 未返回账单列表")
        if (values.length() !in 1..20) error("AI 返回账单数量无效")
        val categoryByName = categories.filterNot { it.hidden }.associateBy { it.name.trim() }
        return (0 until values.length()).map { index ->
            val item = values.optJSONObject(index) ?: error("AI 返回了无效账单")
            val amount = item.optDouble("amount", Double.NaN).takeIf { it.isFinite() && it > 0 && it <= 10_000_000 }
                ?.let { (it * 100).roundToLong() }
            val categoryName = item.optString("category", "").trim()
            val direction = when (item.optString("direction", "EXPENSE")) {
                "INCOME" -> Direction.INCOME; "TRANSFER" -> Direction.TRANSFER; else -> Direction.EXPENSE
            }
            VoiceDraft(
                rawText = item.optString("description", "").take(200),
                amountMinor = amount,
                categoryIds = categoryByName[categoryName]?.let { listOf(it.id) }.orEmpty(),
                occurredAt = parseDate(item.optString("occurred_date", ""), now),
                note = item.optString("note", "").trim().take(100).ifBlank { null },
                direction = direction,
                merchant = item.optString("merchant", "").trim().take(80).ifBlank { null }
            )
        }
    }

    private fun parseDate(value: String, now: Long): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(value)?.time
    }.getOrNull() ?: now

    private fun systemPrompt(categories: List<CategoryEntity>) = """
        你是本地记账应用的文本解析器。只解析本次输入；不要解释、不要对话、不要创建或修改分类、不要处理历史账单。
        将可能包含多笔消费或收入的文本拆为账单。只使用以下分类名称之一，不能自创或改写：${categories.joinToString("、") { it.name }}。
        仅输出 JSON，不要 Markdown：{"transactions":[{"amount":12.5,"direction":"EXPENSE|INCOME|TRANSFER","category":"精确分类名或空字符串","occurred_date":"yyyy-MM-dd 或空字符串","merchant":"商户或空字符串","note":"具体商品、服务或收入事项","description":"该笔简短原文"}]}。
        note 必须尽量提取实际买了什么、消费了什么或收入了什么，不能只重复平台或泛化成“消费”。例如“花12块钱在美团买了个烤鸭饭”必须填 merchant="美团"、note="烤鸭饭"；“工资到账5000元”应填 note="工资"。只有确实无法判断时才留空。
        金额单位为人民币元。无法确定的金额或分类填空字符串；不要猜测。
    """.trimIndent()
}
