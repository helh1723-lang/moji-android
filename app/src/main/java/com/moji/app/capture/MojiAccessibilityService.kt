package com.moji.app.capture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.moji.app.MojiApplication
import com.moji.app.settings.UserSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.lang.ref.WeakReference

class MojiAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val consent = AtomicBoolean(false)
    private lateinit var processor: CaptureProcessor
    private var settingsJob: Job? = null
    private val pendingSubmissions = mutableMapOf<Int, Job>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        connectedService = WeakReference(this)
        processor = CaptureProcessor(this)
        val settings = (application as MojiApplication).settings
        settingsJob = serviceScope.launch { settings.values.collect { consent.set(it.captureConsent) } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!consent.get()) return
        val packageName = event?.packageName?.toString() ?: return
        if (packageName !in ALLOWED_PACKAGES) return
        val root = rootInActiveWindow ?: return
        val texts = ArrayList<String>(32)
        collectTexts(root, texts, depth = 0, budget = intArrayOf(160, 2_000))
        if (texts.isEmpty()) {
            serviceScope.launch {
                DebugCaptureSampler.recordEmptyAccessibilityTree(
                    context = this@MojiAccessibilityService,
                    packageName = packageName,
                    eventWindowId = event.windowId,
                    rootWindowId = root.windowId,
                    accessibilityEventType = event.eventType
                )
            }
            return
        }
        if (texts.none { text -> PAYMENT_HINTS.any(text::contains) }) return
        val now = System.currentTimeMillis()
        val snapshot = PaymentSnapshot(
            packageName = packageName,
            texts = texts,
            receivedAt = now,
            source = "ACCESSIBILITY",
            pageInstanceHash = LocalPaymentParser.sha256(
                "$packageName|${root.windowId}|${texts.take(32).joinToString("|")}"
            ),
            eventWindowId = event.windowId,
            rootWindowId = root.windowId,
            accessibilityEventType = event.eventType
        )
        pendingSubmissions.remove(root.windowId)?.cancel()
        pendingSubmissions[root.windowId] = serviceScope.launch {
            delay(PAGE_SETTLE_DELAY_MS)
            pendingSubmissions.remove(root.windowId)
            processor.submit(snapshot)
        }
    }

    private fun collectTexts(node: AccessibilityNodeInfo, output: MutableList<String>, depth: Int, budget: IntArray) {
        if (depth > 12 || budget[0]-- <= 0 || budget[1] <= 0) return
        val value = node.text?.toString()?.trim().orEmpty()
        if (value.isNotEmpty()) {
            val safe = value.take(budget[1].coerceAtMost(160))
            output += safe
            budget[1] -= safe.length
        }
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        if (description.isNotEmpty() && description != value && budget[1] > 0) {
            val safe = description.take(budget[1].coerceAtMost(160))
            output += safe
            budget[1] -= safe.length
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectTexts(child, output, depth + 1, budget)
        }
    }

    override fun onInterrupt() = Unit
    override fun onDestroy() {
        if (connectedService?.get() === this) connectedService = null
        settingsJob?.cancel()
        pendingSubmissions.values.forEach(Job::cancel)
        pendingSubmissions.clear()
        if (::processor.isInitialized) processor.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var connectedService: WeakReference<MojiAccessibilityService>? = null

        fun connectedInstance(): MojiAccessibilityService? = connectedService?.get()

        private val ALLOWED_PACKAGES = setOf("com.tencent.mm", "com.eg.android.AlipayGphone")
        private val PAYMENT_HINTS = listOf("支付", "付款", "收款", "交易", "退款", "转账")
        private const val PAGE_SETTLE_DELAY_MS = 650L
    }
}
