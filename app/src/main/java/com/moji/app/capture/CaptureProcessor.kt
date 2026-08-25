package com.moji.app.capture

import android.content.Context
import com.moji.app.MojiApplication
import com.moji.app.data.CandidateStatus
import com.moji.app.data.CaptureEventEntity
import com.moji.app.data.TransactionCandidateEntity
import com.moji.app.data.TransactionEntity
import com.moji.app.data.TransactionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

internal fun determineCandidateStatus(snapshot: PaymentSnapshot, parsed: ParsedPayment): CandidateStatus? = when {
    !parsed.paymentRelevant -> null
    parsed.failed -> CandidateStatus.PAYMENT_FAILED
    parsed.amountMinor == null -> CandidateStatus.PARSE_FAILED
    parsed.confidence < 0.60f -> null
    snapshot.source == LocalPaymentParser.SOURCE_NOTIFICATION &&
        parsed.successful && parsed.direction != null && parsed.confidence >= 0.75f -> CandidateStatus.PARSED
    snapshot.source == LocalPaymentParser.SOURCE_NOTIFICATION -> null
    parsed.direction == null || !parsed.successful || parsed.confidence < 0.85f -> CandidateStatus.PENDING_REVIEW
    else -> CandidateStatus.PARSED
}

class CaptureProcessor(private val context: Context) {
    private val repository = (context.applicationContext as MojiApplication).repository
    private val registry = ParserRegistry()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recentPages = ConcurrentHashMap<String, Long>()

    fun submit(snapshot: PaymentSnapshot) {
        val previous = recentPages.put(snapshot.pageInstanceHash, snapshot.receivedAt)
        if (previous != null && snapshot.receivedAt - previous < SAME_PAGE_SUPPRESSION_MS) return
        recentPages.entries.removeIf { snapshot.receivedAt - it.value > PAGE_CACHE_RETENTION_MS }
        scope.launch {
            if (snapshot.source == LocalPaymentParser.SOURCE_NOTIFICATION) delay(NOTIFICATION_FALLBACK_DELAY_MS)
            process(snapshot)
        }
    }

    fun close() = scope.cancel()

    private suspend fun process(snapshot: PaymentSnapshot) {
        val parsed = registry.parse(snapshot) ?: return
        val now = System.currentTimeMillis()
        val event = CaptureEventEntity(
            platform = parsed.platform.name,
            eventType = snapshot.source,
            pageType = parsed.pageType,
            receivedAt = snapshot.receivedAt,
            parserVersion = parsed.parserVersion,
            resultCode = parsed.resultCode,
            contentHash = LocalPaymentParser.sha256(snapshot.texts.joinToString("|").take(2_000)),
            pageInstanceHash = snapshot.pageInstanceHash,
            retentionUntil = now + CAPTURE_EVENT_RETENTION_MS
        )
        val status = determineCandidateStatus(snapshot, parsed)
        DebugCaptureSampler.record(context, snapshot, parsed, status)
        if (status == null || status == CandidateStatus.PAYMENT_FAILED || status == CandidateStatus.PARSE_FAILED) {
            repository.saveCaptureEvent(event)
            return
        }
        val candidate = TransactionCandidateEntity(
            platform = parsed.platform.name,
            direction = parsed.direction?.name,
            amountMinor = parsed.amountMinor,
            merchant = parsed.merchant,
            occurredAt = snapshot.receivedAt,
            confidence = parsed.confidence,
            conflictCode = parsed.resultCode.takeUnless { it == "PARSED" },
            status = status.name,
            dedupeKey = parsed.dedupeKey
        )
        if (status == CandidateStatus.PARSED && parsed.amountMinor != null && parsed.direction != null) {
            val categoryId = repository.categoryForMerchant(parsed.merchant) ?: "other"
            val recent = repository.recentCaptureMatch(
                parsed.platform.name,
                parsed.direction.name,
                parsed.amountMinor,
                parsed.merchant,
                snapshot.receivedAt
            )
            val effectiveDedupeKey = recent?.dedupeKey ?: parsed.dedupeKey
            val transaction = TransactionEntity(
                direction = parsed.direction.name,
                amountMinor = parsed.amountMinor,
                merchantRaw = parsed.merchant,
                merchantNormalized = com.moji.app.data.MojiRepository.normalizeMerchant(parsed.merchant),
                categoryId = categoryId,
                platform = parsed.platform.name,
                occurredAt = snapshot.receivedAt,
                capturedAt = now,
                source = TransactionSource.AUTO.name,
                confidence = parsed.confidence,
                parserVersion = parsed.parserVersion,
                dedupeKey = effectiveDedupeKey
            )
            repository.postCandidate(candidate.copy(dedupeKey = effectiveDedupeKey), transaction, event)
            if (recent == null) {
                CaptureFeedback.show(context, transaction, repository.categoriesNow())
            }
        } else {
            repository.savePending(candidate, event)
        }
    }

    companion object {
        private const val SAME_PAGE_SUPPRESSION_MS = 8_000L
        private const val PAGE_CACHE_RETENTION_MS = 30_000L
        private const val NOTIFICATION_FALLBACK_DELAY_MS = 1_200L
        private const val CAPTURE_EVENT_RETENTION_MS = 14L * 24 * 60 * 60 * 1000
    }
}
