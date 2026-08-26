package com.moji.app.capture

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.moji.app.MainActivity
import com.moji.app.MojiApplication
import com.moji.app.R
import com.moji.app.data.CategoryEntity
import com.moji.app.data.Direction
import com.moji.app.data.TransactionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.lang.ref.WeakReference

internal fun parseQuickAmountMinor(value: String): Long? = runCatching {
    BigDecimal(value.trim())
        .movePointRight(2)
        .setScale(0, RoundingMode.UNNECESSARY)
        .longValueExact()
}.getOrNull()?.takeIf { it > 0 }

object CaptureFeedback {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeOverlay: WeakReference<ActiveOverlay>? = null

    fun show(context: Context, transaction: TransactionEntity, categories: List<CategoryEntity>) {
        mainHandler.post {
            val overlayService = context as? AccessibilityService
                ?: MojiAccessibilityService.connectedInstance()
            val shown = overlayService != null && showAccessibilityCard(overlayService, transaction, categories)
            if (!shown) showNotification(context, transaction, categories)
        }
    }

    fun dismissFor(service: AccessibilityService) {
        mainHandler.post {
            currentOverlay()?.takeIf { it.root.context === service }?.let { dismissOverlay() }
        }
    }

    private fun showAccessibilityCard(
        service: AccessibilityService,
        transaction: TransactionEntity,
        categories: List<CategoryEntity>
    ): Boolean = runCatching {
        dismissOverlay()
        val density = service.resources.displayMetrics.density
        val palette = palette(service)
        val root = FrameLayout(service).apply {
            setPadding(dp(density, 16), dp(density, 12), dp(density, 16), 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) accessibilityPaneTitle = "自动记账卡片"
        }
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(density, 18), dp(density, 14), dp(density, 18), dp(density, 14))
            background = cardBackground(density, palette)
            elevation = dp(density, 10).toFloat()
        }
        root.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = dp(density, 48)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val manager = service.getSystemService(WindowManager::class.java)
        manager.addView(root, params)
        val overlay = ActiveOverlay(manager, root, params)
        activeOverlay = WeakReference(overlay)
        renderSummary(service, overlay, card, transaction, categories, palette)
        scheduleClose(overlay, CARD_DURATION_MS)
        true
    }.onFailure { error ->
        Log.w(TAG, "Unable to display accessibility feedback card", error)
    }.getOrDefault(false)

    private fun renderSummary(
        service: AccessibilityService,
        overlay: ActiveOverlay,
        card: LinearLayout,
        transaction: TransactionEntity,
        categories: List<CategoryEntity>,
        palette: Palette
    ) {
        card.removeAllViews()
        val density = service.resources.displayMetrics.density
        val merchant = transaction.merchantRaw ?: "未知商户"
        val category = categories.firstOrNull { it.id == transaction.categoryId }
        card.addView(TextView(service).apply {
            text = service.getString(R.string.capture_feedback_title)
            textSize = 15f
            setTextColor(palette.success)
        })
        card.addView(TextView(service).apply {
            text = service.getString(R.string.capture_feedback_detail, merchant, transaction.amountMinor / 100.0)
            textSize = 18f
            setTextColor(palette.primaryText)
            setPadding(0, dp(density, 6), 0, 0)
        })
        card.addView(TextView(service).apply {
            text = "${category?.let { "${it.icon} ${it.name}" } ?: "其他"} · ${platformLabel(transaction.platform)}"
            textSize = 13f
            setTextColor(palette.secondaryText)
            setPadding(0, dp(density, 4), 0, dp(density, 4))
        })
        card.addView(LinearLayout(service).apply {
            gravity = Gravity.END
            addView(actionText(service, "撤销", palette) {
                undo(service, transaction.id)
                dismissOverlay()
            })
            addView(actionText(service, "修改", palette) {
                cancelClose(overlay)
                setOverlayFocusable(service, overlay, true)
                renderQuickEditor(service, overlay, card, transaction, categories, palette)
            })
        })
    }

    private fun renderQuickEditor(
        service: AccessibilityService,
        overlay: ActiveOverlay,
        card: LinearLayout,
        transaction: TransactionEntity,
        categories: List<CategoryEntity>,
        palette: Palette
    ) {
        card.removeAllViews()
        val density = service.resources.displayMetrics.density
        val selectableCategories = categories.filterNot { it.hidden }.ifEmpty {
            listOf(CategoryEntity("other", name = "其他", icon = "◌", sortOrder = 0))
        }
        var categoryIndex = selectableCategories.indexOfFirst { it.id == transaction.categoryId }
            .takeIf { it >= 0 } ?: selectableCategories.indexOfFirst { it.id == "other" }.coerceAtLeast(0)

        card.addView(TextView(service).apply {
            text = "修改这笔账"
            textSize = 16f
            setTextColor(palette.primaryText)
            setPadding(0, 0, 0, dp(density, 8))
        })
        val amountInput = editField(service, "金额（元）", formatAmount(transaction.amountMinor), palette).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val merchantInput = editField(service, "商户（可留空）", transaction.merchantRaw.orEmpty(), palette).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        card.addView(amountInput)
        card.addView(space(service, 8))
        card.addView(merchantInput)
        card.addView(space(service, 8))

        val categoryText = TextView(service).apply {
            textSize = 14f
            setTextColor(palette.primaryText)
            gravity = Gravity.CENTER
        }
        fun updateCategoryText() {
            val selected = selectableCategories[categoryIndex]
            categoryText.text = "${selected.icon}  ${selected.name}"
            categoryText.contentDescription = "分类，${selected.name}"
        }
        updateCategoryText()
        card.addView(LinearLayout(service).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = fieldBackground(density, palette)
            minimumHeight = dp(density, 48)
            addView(actionText(service, "‹", palette) {
                categoryIndex = (categoryIndex - 1 + selectableCategories.size) % selectableCategories.size
                updateCategoryText()
            }, LinearLayout.LayoutParams(dp(density, 48), dp(density, 48)))
            addView(categoryText, LinearLayout.LayoutParams(0, dp(density, 48), 1f))
            addView(actionText(service, "›", palette) {
                categoryIndex = (categoryIndex + 1) % selectableCategories.size
                updateCategoryText()
            }, LinearLayout.LayoutParams(dp(density, 48), dp(density, 48)))
        })

        val errorText = TextView(service).apply {
            textSize = 12f
            setTextColor(palette.error)
            visibility = View.GONE
            setPadding(0, dp(density, 4), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        card.addView(errorText)
        card.addView(LinearLayout(service).apply {
            gravity = Gravity.END
            setPadding(0, dp(density, 4), 0, 0)
            addView(actionText(service, "取消", palette) {
                hideKeyboard(service, amountInput)
                setOverlayFocusable(service, overlay, false)
                renderSummary(service, overlay, card, transaction, categories, palette)
                scheduleClose(overlay, CARD_DURATION_MS)
            })
            addView(actionText(service, "保存", palette) {
                val amountMinor = parseQuickAmountMinor(amountInput.text.toString())
                if (amountMinor == null) {
                    errorText.text = "请输入大于 0、最多两位小数的金额"
                    errorText.visibility = View.VISIBLE
                    amountInput.requestFocus()
                    return@actionText
                }
                errorText.visibility = View.GONE
                val selectedCategory = selectableCategories[categoryIndex]
                ioScope.launch {
                    val result = runCatching {
                        (service.applicationContext as MojiApplication).repository.saveTransaction(
                            existingId = transaction.id,
                            amountMinor = amountMinor,
                            direction = runCatching { Direction.valueOf(transaction.direction) }.getOrDefault(Direction.EXPENSE),
                            merchant = merchantInput.text.toString(),
                            categoryId = selectedCategory.id,
                            occurredAt = transaction.occurredAt,
                            note = transaction.note,
                            includeInStats = transaction.includeInStats,
                            createMerchantRule = false
                        )
                    }
                    mainHandler.post {
                        if (currentOverlay() !== overlay) return@post
                        result.onSuccess {
                            hideKeyboard(service, amountInput)
                            setOverlayFocusable(service, overlay, false)
                            renderSaved(service, card, palette)
                            scheduleClose(overlay, SAVED_DURATION_MS)
                        }.onFailure {
                            errorText.text = "保存失败，请稍后重试"
                            errorText.visibility = View.VISIBLE
                            Log.w(TAG, "Unable to save quick edit", it)
                        }
                    }
                }
            })
        })
        amountInput.requestFocus()
        mainHandler.postDelayed({
            if (currentOverlay() === overlay) {
                service.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(amountInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 120L)
    }

    private fun renderSaved(service: Context, card: LinearLayout, palette: Palette) {
        card.removeAllViews()
        card.gravity = Gravity.CENTER_VERTICAL
        card.addView(TextView(service).apply {
            text = "✓ 已更新"
            textSize = 16f
            setTextColor(palette.success)
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(resources.displayMetrics.density, 48)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
    }

    private fun showNotification(context: Context, transaction: TransactionEntity, categories: List<CategoryEntity>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "自动记账结果", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "支付完成后的自动记账结果"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        val editIntent = editPendingIntent(context, transaction.id)
        val undoIntent = PendingIntent.getBroadcast(
            context,
            transaction.id.hashCode(),
            Intent(context, CaptureActionReceiver::class.java).apply {
                action = ACTION_UNDO
                putExtra(EXTRA_TRANSACTION_ID, transaction.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val merchant = transaction.merchantRaw ?: "未知商户"
        val categoryName = categories.firstOrNull { it.id == transaction.categoryId }?.name ?: "其他"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.capture_feedback_title).trimStart('✓', ' '))
            .setContentText(context.getString(R.string.capture_feedback_detail, merchant, transaction.amountMinor / 100.0))
            .setSubText("$categoryName · ${platformLabel(transaction.platform)}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(editIntent)
            .addAction(0, "撤销", undoIntent)
            .addAction(0, "修改", editIntent)
            .setAutoCancel(true)
            .setSilent(true)
            .setTimeoutAfter(5_000L)
            .build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    private fun editField(context: Context, hintText: String, value: String, palette: Palette) = EditText(context).apply {
        hint = hintText
        setText(value)
        textSize = 15f
        setTextColor(palette.primaryText)
        setHintTextColor(palette.secondaryText)
        background = fieldBackground(resources.displayMetrics.density, palette)
        setPadding(dp(resources.displayMetrics.density, 12), 0, dp(resources.displayMetrics.density, 12), 0)
        minimumHeight = dp(resources.displayMetrics.density, 48)
        isSingleLine = true
        setSelectAllOnFocus(true)
    }

    private fun actionText(context: Context, label: String, palette: Palette, action: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        setTextColor(palette.success)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        minimumWidth = dp(resources.displayMetrics.density, 48)
        minimumHeight = dp(resources.displayMetrics.density, 48)
        setPadding(dp(resources.displayMetrics.density, 8), 0, dp(resources.displayMetrics.density, 8), 0)
        setOnClickListener { action() }
    }

    private fun cardBackground(density: Float, palette: Palette) = GradientDrawable().apply {
        setColor(palette.surface)
        cornerRadius = dp(density, 16).toFloat()
        setStroke(dp(density, 1), palette.border)
    }

    private fun fieldBackground(density: Float, palette: Palette) = GradientDrawable().apply {
        setColor(palette.fieldSurface)
        cornerRadius = dp(density, 12).toFloat()
        setStroke(dp(density, 1), palette.border)
    }

    private fun setOverlayFocusable(service: Context, overlay: ActiveOverlay, focusable: Boolean) {
        overlay.params.flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        runCatching { overlay.manager.updateViewLayout(overlay.root, overlay.params) }
            .onFailure { Log.w(TAG, "Unable to update feedback card focus", it) }
        if (!focusable) hideKeyboard(service, overlay.root)
    }

    private fun scheduleClose(overlay: ActiveOverlay, delayMs: Long) {
        cancelClose(overlay)
        overlay.closeRunnable = Runnable {
            if (currentOverlay() === overlay) dismissOverlay()
        }.also { mainHandler.postDelayed(it, delayMs) }
    }

    private fun cancelClose(overlay: ActiveOverlay) {
        overlay.closeRunnable?.let(mainHandler::removeCallbacks)
        overlay.closeRunnable = null
    }

    private fun undo(context: Context, transactionId: String) {
        ioScope.launch { (context.applicationContext as MojiApplication).repository.softDelete(transactionId) }
    }

    private fun dismissOverlay() {
        val overlay = currentOverlay() ?: return
        cancelClose(overlay)
        hideKeyboard(overlay.root.context, overlay.root)
        runCatching { overlay.manager.removeView(overlay.root) }
        activeOverlay = null
    }

    private fun currentOverlay(): ActiveOverlay? = activeOverlay?.get()

    private fun hideKeyboard(context: Context, view: View) {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    private fun editPendingIntent(context: Context, transactionId: String): PendingIntent = PendingIntent.getActivity(
        context,
        transactionId.hashCode(),
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_EDIT
            putExtra(EXTRA_TRANSACTION_ID, transactionId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun platformLabel(platform: String) = when (platform) {
        "WECHAT" -> "微信"
        "ALIPAY" -> "支付宝"
        else -> platform
    }

    private fun formatAmount(amountMinor: Long) = BigDecimal.valueOf(amountMinor, 2).stripTrailingZeros().toPlainString()
    private fun space(context: Context, heightDp: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(resources.displayMetrics.density, heightDp))
    }
    private fun dp(density: Float, value: Int) = (density * value + 0.5f).toInt()

    private fun palette(context: Context): Palette {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return if (dark) Palette(
            surface = 0xFF1C211F.toInt(), fieldSurface = 0xFF252B28.toInt(),
            primaryText = 0xFFF2F5F3.toInt(), secondaryText = 0xFFBBC4BF.toInt(),
            success = 0xFF67D5A5.toInt(), error = 0xFFFFB4AB.toInt(), border = 0xFF46504B.toInt()
        ) else Palette(
            surface = Color.WHITE, fieldSurface = 0xFFF4F7F5.toInt(),
            primaryText = 0xFF18201C.toInt(), secondaryText = 0xFF5A655F.toInt(),
            success = 0xFF16835A.toInt(), error = 0xFFBA1A1A.toInt(), border = 0xFFD7DED9.toInt()
        )
    }

    private data class Palette(
        val surface: Int,
        val fieldSurface: Int,
        val primaryText: Int,
        val secondaryText: Int,
        val success: Int,
        val error: Int,
        val border: Int
    )

    private data class ActiveOverlay(
        val manager: WindowManager,
        val root: View,
        val params: WindowManager.LayoutParams,
        var closeRunnable: Runnable? = null
    )

    private const val CHANNEL_ID = "moji_capture_v2"
    private const val TAG = "MojiCaptureFeedback"
    private const val CARD_DURATION_MS = 3_000L
    private const val SAVED_DURATION_MS = 1_200L
    const val ACTION_EDIT = "com.moji.app.action.EDIT_CAPTURED_TRANSACTION"
    const val ACTION_UNDO = "com.moji.app.action.UNDO_CAPTURED_TRANSACTION"
    const val EXTRA_TRANSACTION_ID = "captured_transaction_id"
}

class CaptureActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CaptureFeedback.ACTION_UNDO) return
        val transactionId = intent.getStringExtra(CaptureFeedback.EXTRA_TRANSACTION_ID) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { (context.applicationContext as MojiApplication).repository.softDelete(transactionId) }
            pendingResult.finish()
        }
    }
}
