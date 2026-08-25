package com.moji.app.capture

import android.content.Context
import com.moji.app.data.Platform

internal data class EmbeddedPaymentSession(
    val packageName: String,
    val observedAt: Long,
    val strongCheckoutEvidence: Boolean
)

internal object EmbeddedPaymentSessionTracker {
    private val lock = Any()
    private var latest: EmbeddedPaymentSession? = null

    fun observeHost(packageName: String, observedAt: Long, strongCheckoutEvidence: Boolean) {
        if (!CaptureSources.isEmbeddedPaymentHost(packageName)) return
        synchronized(lock) {
            val current = latest
            latest = when {
                current?.packageName != packageName || observedAt - current.observedAt > STRONG_SESSION_TTL_MS ->
                    EmbeddedPaymentSession(packageName, observedAt, strongCheckoutEvidence)
                strongCheckoutEvidence -> EmbeddedPaymentSession(packageName, observedAt, true)
                current.strongCheckoutEvidence -> current
                else -> EmbeddedPaymentSession(packageName, observedAt, false)
            }
        }
    }

    fun recentForNotification(now: Long): EmbeddedPaymentSession? = synchronized(lock) {
        latest?.takeIf { session ->
            val age = now - session.observedAt
            age >= 0 && age <= if (session.strongCheckoutEvidence) STRONG_SESSION_TTL_MS else WEAK_SESSION_TTL_MS
        }
    }

    fun resetForTest() = synchronized(lock) { latest = null }

    private const val WEAK_SESSION_TTL_MS = 30_000L
    private const val STRONG_SESSION_TTL_MS = 5 * 60_000L
}

internal class EmbeddedPaymentSessionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun save(session: EmbeddedPaymentSession) {
        if (!session.strongCheckoutEvidence || !CaptureSources.isEmbeddedPaymentHost(session.packageName)) return
        preferences.edit()
            .putString(KEY_PACKAGE, session.packageName)
            .putLong(KEY_OBSERVED_AT, session.observedAt)
            .apply()
    }

    fun recent(now: Long): EmbeddedPaymentSession? {
        val packageName = preferences.getString(KEY_PACKAGE, null) ?: return null
        val observedAt = preferences.getLong(KEY_OBSERVED_AT, 0L)
        val age = now - observedAt
        return EmbeddedPaymentSession(packageName, observedAt, true).takeIf {
            CaptureSources.isEmbeddedPaymentHost(packageName) && age in 0..PERSISTED_SESSION_TTL_MS
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "embedded_payment_session"
        const val KEY_PACKAGE = "package"
        const val KEY_OBSERVED_AT = "observed_at"
        const val PERSISTED_SESSION_TTL_MS = 5 * 60_000L
    }
}

internal class NotificationEventIdentityTracker(
    private val callbackWindowMs: Long = 8_000L
) {
    private data class Entry(
        val fingerprint: String,
        val lastObservedAt: Long,
        val identity: String
    )

    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    fun resolve(notificationKey: String, fingerprint: String, observedAt: Long): String {
        val previous = entries[notificationKey]
        if (previous != null && previous.fingerprint == fingerprint &&
            observedAt - previous.lastObservedAt in 0..callbackWindowMs
        ) {
            entries[notificationKey] = previous.copy(lastObservedAt = observedAt)
            return previous.identity
        }
        val identity = LocalPaymentParser.sha256("$notificationKey|$fingerprint|$observedAt")
        entries[notificationKey] = Entry(fingerprint, observedAt, identity)
        entries.entries.removeIf { observedAt - it.value.lastObservedAt > ENTRY_RETENTION_MS }
        return identity
    }

    private companion object {
        const val ENTRY_RETENTION_MS = 60_000L
    }
}

internal fun hasEmbeddedCheckoutEvidence(texts: List<String>): Boolean {
    val compact = texts.joinToString("").replace(Regex("\\s+"), "")
    if (EMBEDDED_FINAL_STATE_WORDS.any(compact::contains)) return false
    if (EMBEDDED_CHECKOUT_HINTS.any(compact::contains)) return true
    val features = redactedTextFeatures(texts)
    val hasAmount = features.hasCurrencySymbol || features.hasDecimalAmountShape || features.hasYuanSuffixAmountShape
    return hasAmount && EMBEDDED_PAYMENT_ACTIONS.any(compact::contains)
}

internal fun fuseEmbeddedHostWithAlipayNotification(
    texts: List<String>,
    session: EmbeddedPaymentSession?
): List<String>? {
    val activeSession = session ?: return null
    val compact = texts.joinToString("").replace(Regex("\\s+"), "")
    if (NOTIFICATION_REJECT_WORDS.any(compact::contains)) return null
    if (!compact.contains("交易")) return null
    if (!compact.contains("支付") && !activeSession.strongCheckoutEvidence) return null
    val augmented = texts + SYNTHETIC_SUCCESS_MARKER
    val parsed = LocalPaymentParser(
        platform = Platform.ALIPAY,
        packageName = activeSession.packageName,
        version = "embedded-notification-v1"
    ).parse(
        PaymentSnapshot(
            packageName = activeSession.packageName,
            texts = augmented,
            receivedAt = activeSession.observedAt,
            source = LocalPaymentParser.SOURCE_NOTIFICATION,
            pageInstanceHash = "validation"
        )
    )
    return augmented.takeIf {
        parsed.resultCode == "PARSED" && parsed.amountMinor != null && parsed.direction != null
    }
}

private val EMBEDDED_CHECKOUT_HINTS = listOf(
    "支付宝", "收银台", "确认付款", "立即付款", "付款方式"
)
private val EMBEDDED_PAYMENT_ACTIONS = listOf("支付", "付款")
private val EMBEDDED_FINAL_STATE_WORDS = listOf(
    "支付成功", "付款成功", "交易成功", "支付失败", "付款失败", "已取消", "退款成功", "已退款"
)
private val NOTIFICATION_REJECT_WORDS = listOf(
    "支付失败", "付款失败", "已取消", "退款", "收款", "到账", "转账"
)
private const val SYNTHETIC_SUCCESS_MARKER = "支付成功"
