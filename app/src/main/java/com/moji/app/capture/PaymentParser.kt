package com.moji.app.capture

import com.moji.app.data.Direction
import com.moji.app.data.Platform
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.Locale

data class PaymentSnapshot(
    val packageName: String,
    val texts: List<String>,
    val receivedAt: Long,
    val source: String,
    val pageInstanceHash: String,
    val eventWindowId: Int? = null,
    val rootWindowId: Int? = null,
    val accessibilityEventType: Int? = null,
    val sourceEventId: String? = null
)

data class ParsedPayment(
    val platform: Platform,
    val pageType: String,
    val paymentRelevant: Boolean,
    val direction: Direction?,
    val amountMinor: Long?,
    val merchant: String?,
    val successful: Boolean,
    val failed: Boolean,
    val confidence: Float,
    val resultCode: String,
    val parserVersion: String,
    val dedupeKey: String?
)

interface PaymentParser {
    val version: String
    fun supports(snapshot: PaymentSnapshot): Boolean
    fun parse(snapshot: PaymentSnapshot): ParsedPayment
}

class LocalPaymentParser(
    private val platform: Platform,
    private val packageName: String,
    override val version: String = "local-v3"
) : PaymentParser {
    override fun supports(snapshot: PaymentSnapshot) = snapshot.packageName == packageName

    override fun parse(snapshot: PaymentSnapshot): ParsedPayment {
        val texts = snapshot.texts.map { it.trim() }.filter { it.isNotBlank() }
        val compactPage = texts.joinToString("").replace(WHITESPACE_REGEX, "")
        val failed = FAILURE_WORDS.any(compactPage::contains)
        val refund = REFUND_WORDS.any(compactPage::contains)
        val transfer = TRANSFER_WORDS.any(compactPage::contains)
        val success = !failed && SUCCESS_WORDS.any(compactPage::contains)
        val paymentRelevant = success || failed || refund || transfer
        val amounts = findAmounts(texts)
        val amount = amounts.singleOrNull()
        val merchant = findMerchant(texts, paymentRelevant)
        val direction = when {
            refund -> Direction.INCOME
            transfer -> null
            success -> Direction.EXPENSE
            else -> null
        }
        var confidence = 0.25f
        if (success || failed) confidence += 0.30f
        if (amount != null) confidence += 0.30f
        if (merchant != null) confidence += 0.10f
        if (refund || transfer) confidence += 0.15f
        if (snapshot.source == SOURCE_NOTIFICATION) confidence -= 0.10f
        val pageType = when {
            failed -> "PAYMENT_FAILED"
            refund -> "REFUND"
            transfer -> "TRANSFER"
            success -> "PAYMENT_SUCCESS"
            else -> "UNKNOWN"
        }
        val result = when {
            !paymentRelevant -> "NOT_PAYMENT_RESULT"
            failed -> "PAYMENT_FAILED"
            amounts.size > 1 -> "AMOUNT_CONFLICT"
            amount == null -> "AMOUNT_MISSING"
            direction == null -> "DIRECTION_UNCERTAIN"
            !success -> "SUCCESS_UNCERTAIN"
            else -> "PARSED"
        }
        val dedupe = if (amount != null && paymentRelevant) {
            val captureIdentity = snapshot.sourceEventId
                ?.let { "source:$it" }
                ?: "bucket:${snapshot.receivedAt / 30_000L}"
            sha256(
                "${platform.name}|${direction?.name ?: "UNKNOWN"}|$amount|${normalize(merchant)}|$captureIdentity"
            )
        } else null
        return ParsedPayment(
            platform = platform,
            pageType = pageType,
            paymentRelevant = paymentRelevant,
            direction = direction,
            amountMinor = amount,
            merchant = merchant,
            successful = success,
            failed = failed,
            confidence = confidence.coerceIn(0f, 1f),
            resultCode = result,
            parserVersion = version,
            dedupeKey = dedupe
        )
    }

    private fun findAmounts(texts: List<String>): List<Long> = texts.flatMap { text ->
        AMOUNT_REGEX.findAll(text).mapNotNull { match ->
            runCatching {
                val amountText = match.groupValues.drop(1).firstOrNull(String::isNotEmpty)
                    ?: return@runCatching null
                BigDecimal(amountText)
                    .setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2)
                    .longValueExact()
            }.getOrNull()?.takeIf { it in 1..99_999_999_999L }
        }.toList()
    }.distinct()

    private fun findMerchant(texts: List<String>, paymentRelevant: Boolean): String? {
        texts.forEachIndexed { index, text ->
            MERCHANT_PREFIXES.firstOrNull { text.startsWith(it) }?.let { prefix ->
                text.removePrefix(prefix).trim('：', ':', ' ')
                    .takeIf(::isMerchantCandidate)?.let { return it }
                texts.getOrNull(index + 1)?.takeIf(::isMerchantCandidate)?.let { return it }
            }
        }
        if (!paymentRelevant) return null

        val statusIndex = texts.indexOfFirst(::containsPaymentState)
        val amountIndex = texts.indexOfFirst(AMOUNT_REGEX::containsMatchIn)
        if (statusIndex >= 0 && amountIndex > statusIndex + 1) {
            texts.subList(statusIndex + 1, amountIndex)
                .firstOrNull(::isMerchantCandidate)?.let { return it }
        }
        return texts.firstOrNull(::isMerchantCandidate)
    }

    private fun containsPaymentState(text: String): Boolean {
        val compact = text.replace(WHITESPACE_REGEX, "")
        return SUCCESS_WORDS.any(compact::contains) || FAILURE_WORDS.any(compact::contains) ||
            REFUND_WORDS.any(compact::contains) || TRANSFER_WORDS.any(compact::contains)
    }

    private fun isMerchantCandidate(text: String): Boolean {
        val value = text.trim()
        if (value.length !in 2..50 || AMOUNT_REGEX.containsMatchIn(value)) return false
        val compact = value.replace(WHITESPACE_REGEX, "")
        return CONTROL_WORDS.none { word -> compact == word || compact.contains(word) }
    }

    companion object {
        const val SOURCE_NOTIFICATION = "NOTIFICATION"
        private val AMOUNT_REGEX = Regex(
            "(?:[¥￥]\\s*|金额[:：]?\\s*)([0-9]{1,9}(?:\\.[0-9]{1,2})?)|" +
                "([0-9]{1,9}(?:\\.[0-9]{1,2})?)\\s*元"
        )
        private val SUCCESS_WORDS = listOf("支付成功", "付款成功", "交易成功", "已支付")
        private val FAILURE_WORDS = listOf("支付失败", "付款失败", "已取消", "余额不足")
        private val TRANSFER_WORDS = listOf("转账", "转入", "转出")
        private val REFUND_WORDS = listOf("退款成功", "已退款")
        private val MERCHANT_PREFIXES = listOf(
            "商户名称", "商户", "商家", "收款方", "收款人", "支付给", "付款给", "交易对象"
        )
        private val CONTROL_WORDS = SUCCESS_WORDS + FAILURE_WORDS + TRANSFER_WORDS + REFUND_WORDS + listOf(
            "支付", "付款", "交易", "成功", "失败", "微信支付", "支付详情", "付款方式", "交易时间",
            "订单号", "返回商家", "完成", "关闭"
        )
        private val WHITESPACE_REGEX = Regex("\\s+")
        private fun normalize(value: String?) = value.orEmpty()
            .lowercase(Locale.ROOT)
            .replace(WHITESPACE_REGEX, "")

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

class ParserRegistry {
    private val parsers: List<PaymentParser> = listOf(
        LocalPaymentParser(Platform.WECHAT, "com.tencent.mm"),
        LocalPaymentParser(Platform.ALIPAY, "com.eg.android.AlipayGphone"),
        LocalPaymentParser(Platform.ALIPAY, CaptureSources.TAOBAO_PACKAGE),
        LocalPaymentParser(Platform.ALIPAY, CaptureSources.IDLEFISH_PACKAGE)
    )

    fun parse(snapshot: PaymentSnapshot): ParsedPayment? = parsers.firstOrNull { it.supports(snapshot) }?.parse(snapshot)
}
