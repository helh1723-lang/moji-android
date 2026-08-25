package com.moji.app.capture

import android.content.Context
import com.moji.app.MojiApplication
import com.moji.app.data.CandidateStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
            val features = redactedTextFeatures(snapshot.texts, MAX_TEXT_FEATURES)
            val sample = JSONObject()
                .put("capturedAt", System.currentTimeMillis())
                .put("source", snapshot.source)
                .put("sourcePackage", snapshot.packageName)
                .put("sourceApp", CaptureSources.appFor(snapshot.packageName).code)
                .put("embeddedPaymentHost", CaptureSources.isEmbeddedPaymentHost(snapshot.packageName))
                .put("platform", parsed.platform.name)
                .put("eventWindowId", snapshot.eventWindowId ?: JSONObject.NULL)
                .put("rootWindowId", snapshot.rootWindowId ?: JSONObject.NULL)
                .put("windowMismatch", snapshot.eventWindowId != null && snapshot.rootWindowId != null &&
                    snapshot.eventWindowId != snapshot.rootWindowId)
                .put("accessibilityEventType", snapshot.accessibilityEventType ?: JSONObject.NULL)
                .putTextFeatures(features)
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
            val sourceApp = CaptureSources.appFor(packageName)
            val sample = JSONObject()
                .put("capturedAt", System.currentTimeMillis())
                .put("source", "ACCESSIBILITY")
                .put("sourcePackage", packageName)
                .put("sourceApp", sourceApp.code)
                .put("embeddedPaymentHost", sourceApp.embeddedPaymentHost)
                .put("platform", sourceApp.platform.name)
                .put("eventWindowId", eventWindowId)
                .put("rootWindowId", rootWindowId)
                .put("windowMismatch", eventWindowId != rootWindowId)
                .put("accessibilityEventType", accessibilityEventType)
                .putTextFeatures(redactedTextFeatures(emptyList()))
                .put("pageType", "UNREADABLE_WINDOW")
                .put("resultCode", "EMPTY_ACCESSIBILITY_TREE")
                .put("decision", "IGNORED")
            writeSample(context, sample)
        } catch (_: Exception) {
            // Debug sampling must never block or break automatic accounting.
        }
    }

    suspend fun recordSourceObservation(
        context: Context,
        packageName: String,
        source: String,
        texts: List<String>,
        eventWindowId: Int? = null,
        rootWindowId: Int? = null,
        accessibilityEventType: Int? = null,
        eventClassName: String? = null,
        scannedWindowCount: Int = 0,
        matchingWindowCount: Int = 0,
        resultCode: String = "EMBEDDED_PAYMENT_HOST_OBSERVED"
    ) {
        try {
            val app = context.applicationContext as MojiApplication
            if (app.settings.backupValues().debugCaptureUntil < System.currentTimeMillis()) return
            val sourceApp = CaptureSources.appFor(packageName)
            val sample = JSONObject()
                .put("capturedAt", System.currentTimeMillis())
                .put("source", source)
                .put("sourcePackage", packageName)
                .put("sourceApp", sourceApp.code)
                .put("embeddedPaymentHost", sourceApp.embeddedPaymentHost)
                .put("platform", sourceApp.platform.name)
                .put("eventWindowId", eventWindowId ?: JSONObject.NULL)
                .put("rootWindowId", rootWindowId ?: JSONObject.NULL)
                .put("windowMismatch", eventWindowId != null && rootWindowId != null && eventWindowId != rootWindowId)
                .put("accessibilityEventType", accessibilityEventType ?: JSONObject.NULL)
                .put("eventClassHash", eventClassName?.let { LocalPaymentParser.sha256(it).take(12) } ?: JSONObject.NULL)
                .put("scannedWindowCount", scannedWindowCount)
                .put("matchingWindowCount", matchingWindowCount)
                .putTextFeatures(redactedTextFeatures(texts, MAX_TEXT_FEATURES))
                .put("pageType", "EMBEDDED_PAYMENT_OBSERVATION")
                .put("resultCode", resultCode)
                .put("decision", "DIAGNOSTIC_ONLY")
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
}

private fun JSONObject.putTextFeatures(features: RedactedTextFeatures): JSONObject = apply {
    put("textCount", features.textCount)
    put("textShape", org.json.JSONArray(features.textShape))
    put("hasCurrencySymbol", features.hasCurrencySymbol)
    put("hasDecimalAmountShape", features.hasDecimalAmountShape)
    put("hasYuanSuffixAmountShape", features.hasYuanSuffixAmountShape)
    put("numericTokenCount", features.numericTokenCount)
}
