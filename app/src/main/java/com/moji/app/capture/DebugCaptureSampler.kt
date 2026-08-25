package com.moji.app.capture

import android.content.Context
import com.moji.app.MojiApplication
import com.moji.app.data.CandidateStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object DebugCaptureSampler {
    private val writeLock = Mutex()

    suspend fun record(
        context: Context,
        snapshot: PaymentSnapshot,
        parsed: ParsedPayment,
        status: CandidateStatus?
    ) {
        try {
            val app = context.applicationContext as MojiApplication
            if (app.settings.backupValues().debugCaptureUntil < System.currentTimeMillis()) return
            val sample = JSONObject()
                .put("capturedAt", System.currentTimeMillis())
                .put("source", snapshot.source)
                .put("platform", parsed.platform.name)
                .put("eventWindowId", snapshot.eventWindowId ?: JSONObject.NULL)
                .put("rootWindowId", snapshot.rootWindowId ?: JSONObject.NULL)
                .put("windowMismatch", snapshot.eventWindowId != null && snapshot.rootWindowId != null &&
                    snapshot.eventWindowId != snapshot.rootWindowId)
                .put("accessibilityEventType", snapshot.accessibilityEventType ?: JSONObject.NULL)
                .put("textCount", snapshot.texts.size)
                .put("textShape", JSONArray(snapshot.texts.take(MAX_TEXT_FEATURES).map(::redact)))
                .put("pageType", parsed.pageType)
                .put("paymentRelevant", parsed.paymentRelevant)
                .put("successful", parsed.successful)
                .put("amountPresent", parsed.amountMinor != null)
                .put("merchantPresent", parsed.merchant != null)
                .put("confidence", parsed.confidence.toDouble())
                .put("resultCode", parsed.resultCode)
                .put("decision", status?.name ?: "IGNORED")
            writeSample(context, sample)
        } catch (_: Exception) {
            // Debug sampling must never block or break automatic accounting.
        }
    }

    suspend fun recordEmptyAccessibilityTree(
        context: Context,
        packageName: String,
        eventWindowId: Int,
        rootWindowId: Int,
        accessibilityEventType: Int
    ) {
        try {
            val app = context.applicationContext as MojiApplication
            if (app.settings.backupValues().debugCaptureUntil < System.currentTimeMillis()) return
            val sample = JSONObject()
                .put("capturedAt", System.currentTimeMillis())
                .put("source", "ACCESSIBILITY")
                .put("platform", if (packageName == "com.tencent.mm") "WECHAT" else "ALIPAY")
                .put("eventWindowId", eventWindowId)
                .put("rootWindowId", rootWindowId)
                .put("windowMismatch", eventWindowId != rootWindowId)
                .put("accessibilityEventType", accessibilityEventType)
                .put("textCount", 0)
                .put("textShape", JSONArray())
                .put("pageType", "UNREADABLE_WINDOW")
                .put("resultCode", "EMPTY_ACCESSIBILITY_TREE")
                .put("decision", "IGNORED")
            writeSample(context, sample)
        } catch (_: Exception) {
            // Debug sampling must never block or break automatic accounting.
        }
    }

    suspend fun pruneIfExpired(context: Context) {
        val app = context.applicationContext as MojiApplication
        if (app.settings.backupValues().debugCaptureUntil >= System.currentTimeMillis()) return
        withContext(Dispatchers.IO) { context.filesDir.resolve(FILE_NAME).delete() }
    }

    private fun redact(raw: String): String {
        val text = raw.trim()
        val keywords = SAFE_KEYWORDS.filter(text::contains)
        if (keywords.isNotEmpty()) return "KEYWORD:${keywords.joinToString("+")}"
        if (AMOUNT_SHAPE.containsMatchIn(text)) return "AMOUNT"
        if (text.any(Char::isDigit) && text.none(Char::isLetter)) return "NUMERIC:${text.length}"
        return "TEXT:${text.length}:${LocalPaymentParser.sha256(text).take(10)}"
    }

    private suspend fun writeSample(context: Context, sample: JSONObject) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val file = context.applicationContext.filesDir.resolve(FILE_NAME)
            val previous = if (file.exists()) file.readLines().takeLast(MAX_SAMPLES - 1) else emptyList()
            file.writeText((previous + sample.toString()).joinToString("\n", postfix = "\n"))
        }
    }

    private const val FILE_NAME = "debug-capture.jsonl"
    private const val MAX_SAMPLES = 80
    private const val MAX_TEXT_FEATURES = 64
    private val AMOUNT_SHAPE = Regex("[¥￥]|金额|[0-9]+\\.[0-9]{1,2}")
    private val SAFE_KEYWORDS = listOf(
        "支付成功", "付款成功", "交易成功", "支付失败", "付款失败", "退款成功", "已退款",
        "支付", "付款", "交易", "退款", "转账", "返回商家", "完成", "关闭"
    )
}
