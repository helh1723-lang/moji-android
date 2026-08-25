package com.moji.app.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.moji.app.MojiApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class MojiNotificationService : NotificationListenerService() {
    private lateinit var processor: CaptureProcessor
    private val embeddedSessionStore by lazy { EmbeddedPaymentSessionStore(this) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val consent = AtomicBoolean(false)
    private val notificationIdentities = NotificationEventIdentityTracker()

    override fun onCreate() {
        super.onCreate()
        processor = CaptureProcessor(this)
        serviceScope.launch { (application as MojiApplication).settings.values.collect { consent.set(it.captureConsent) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!consent.get()) return
        val item = sbn ?: return
        if (item.packageName !in CaptureSources.notificationPackages) return
        val extras = item.notification.extras
        val texts = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        ).map { it.trim() }.filter { it.isNotEmpty() }
        if (texts.none { text -> PAYMENT_HINTS.any(text::contains) }) return
        if (CaptureSources.isEmbeddedPaymentHost(item.packageName)) {
            serviceScope.launch {
                DebugCaptureSampler.recordSourceObservation(
                    context = this@MojiNotificationService,
                    packageName = item.packageName,
                    source = "NOTIFICATION",
                    texts = texts,
                    resultCode = "EMBEDDED_PAYMENT_HOST_NOTIFICATION"
                )
            }
            return
        }
        val now = System.currentTimeMillis()
        val receivedAt = item.postTime.takeIf { it > 0 } ?: now
        val embeddedSession = if (item.packageName == CaptureSources.ALIPAY_PACKAGE) {
            EmbeddedPaymentSessionTracker.recentForNotification(now) ?: embeddedSessionStore.recent(now)
        } else null
        val fusedTexts = fuseEmbeddedHostWithAlipayNotification(texts, embeddedSession)
        val effectivePackage = if (fusedTexts != null) embeddedSession!!.packageName else item.packageName
        val effectiveTexts = fusedTexts ?: texts
        val notificationFingerprint = LocalPaymentParser.sha256(
            "${item.packageName}|${effectiveTexts.joinToString("|")}"
        )
        val sourceEventId = notificationIdentities.resolve(
            notificationKey = item.key,
            fingerprint = notificationFingerprint,
            observedAt = now
        )
        processor.submit(
            PaymentSnapshot(
                packageName = effectivePackage,
                texts = effectiveTexts,
                receivedAt = receivedAt,
                source = "NOTIFICATION",
                pageInstanceHash = LocalPaymentParser.sha256(
                    "$effectivePackage|$sourceEventId|${effectiveTexts.joinToString("|")}"
                ),
                sourceEventId = sourceEventId
            )
        )
    }

    override fun onDestroy() {
        if (::processor.isInitialized) processor.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private val PAYMENT_HINTS = listOf("支付宝", "收银台", "支付", "付款", "收款", "交易", "退款", "转账")
    }
}
