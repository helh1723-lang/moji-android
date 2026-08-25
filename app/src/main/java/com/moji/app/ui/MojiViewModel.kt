package com.moji.app.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.moji.app.data.BudgetEntity
import com.moji.app.data.CategoryEntity
import com.moji.app.data.Direction
import com.moji.app.data.LedgerSummary
import com.moji.app.data.MojiRepository
import com.moji.app.data.TransactionEntity
import com.moji.app.data.TransactionCandidateEntity
import com.moji.app.data.TransactionWithCategory
import com.moji.app.backup.BackupManager
import com.moji.app.settings.MojiSettings
import com.moji.app.settings.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.Calendar

data class LedgerUiState(
    val rows: List<TransactionWithCategory> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val summary: LedgerSummary = LedgerSummary(),
    val monthStart: Long = startOfMonth(System.currentTimeMillis()),
    val monthEnd: Long = endOfMonth(System.currentTimeMillis()),
    val query: String = ""
)

@OptIn(ExperimentalCoroutinesApi::class)
class MojiViewModel(
    private val repository: MojiRepository,
    private val settingsStore: MojiSettings
) : ViewModel() {
    private val backupManager = BackupManager(repository, settingsStore)
    private val monthOffset = MutableStateFlow(0)
    private val query = MutableStateFlow("")
    val operationMessage = MutableStateFlow<String?>(null)

    val settings: StateFlow<UserSettings> = settingsStore.values.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings()
    )
    val pendingCandidates = repository.pendingCandidates.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )
    val allRows = repository.ledgerRows.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    val uiState: StateFlow<LedgerUiState> = combine(
        repository.ledgerRows,
        repository.categories,
        monthOffset,
        query
    ) { rows, categories, offset, search ->
        val calendar = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MONTH, offset)
        }
        val start = startOfMonth(calendar.timeInMillis)
        val end = endOfMonth(calendar.timeInMillis)
        val todayStart = startOfDay(System.currentTimeMillis())
        val normalizedSearch = search.trim().lowercase()
        val monthRows = rows.filter { it.transaction.occurredAt in start..end }
        val visible = monthRows.filter { row ->
            (
                normalizedSearch.isBlank() ||
                    row.transaction.merchantRaw.orEmpty().lowercase().contains(normalizedSearch) ||
                    row.transaction.note.orEmpty().lowercase().contains(normalizedSearch) ||
                    row.category?.name.orEmpty().contains(normalizedSearch) ||
                    row.transaction.amountMinor.toString().contains(normalizedSearch)
                )
        }
        val valid = monthRows.map { it.transaction }.filter {
            it.includeInStats && it.status != "PENDING_REVIEW" && it.deletedAt == null
        }
        LedgerUiState(
            rows = visible,
            categories = categories,
            summary = LedgerSummary(
                monthExpenseMinor = (valid.filter { it.direction == Direction.EXPENSE.name }.sumOf { it.amountMinor } -
                    valid.filter { it.status == "REFUND" }.sumOf { it.amountMinor }).coerceAtLeast(0),
                monthIncomeMinor = valid.filter { it.direction == Direction.INCOME.name && it.status != "REFUND" }.sumOf { it.amountMinor },
                todayExpenseMinor = (valid.filter { it.direction == Direction.EXPENSE.name && it.occurredAt >= todayStart }.sumOf { it.amountMinor } -
                    valid.filter { it.status == "REFUND" && it.occurredAt >= todayStart }.sumOf { it.amountMinor }).coerceAtLeast(0)
            ),
            monthStart = start,
            monthEnd = end,
            query = search
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerUiState())

    val budgets = monthOffset.flatMapLatest { offset ->
        repository.budgets(monthKey(offset))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun previousMonth() { monthOffset.value -= 1 }
    fun nextMonth() { if (monthOffset.value < 0) monthOffset.value += 1 }
    fun setQuery(value: String) { query.value = value }

    fun saveTransaction(
        existingId: String?, amountMinor: Long, direction: Direction, merchant: String?,
        categoryId: String, occurredAt: Long, note: String?, includeInStats: Boolean, createMerchantRule: Boolean
    ) = viewModelScope.launch {
        repository.saveTransaction(existingId, amountMinor, direction, merchant, categoryId, occurredAt, note, includeInStats, createMerchantRule)
    }

    fun deleteTransaction(id: String) = viewModelScope.launch { repository.softDelete(id) }
    fun restoreTransaction(id: String) = viewModelScope.launch { repository.restore(id) }
    fun completeOnboarding() = viewModelScope.launch { settingsStore.completeOnboarding() }
    fun setDarkTheme(value: Boolean) = viewModelScope.launch { settingsStore.setDarkTheme(value) }
    fun setHideRecents(value: Boolean) = viewModelScope.launch { settingsStore.setHideRecents(value) }
    fun setCaptureConsent(value: Boolean) = viewModelScope.launch { settingsStore.setCaptureConsent(value) }
    fun enableDebugCapture() = viewModelScope.launch { settingsStore.enableDebugCapture() }
    fun confirmCandidate(candidate: TransactionCandidateEntity, direction: Direction) = viewModelScope.launch {
        repository.confirmCandidate(candidate, direction)
    }
    fun ignoreCandidate(id: String) = viewModelScope.launch { repository.ignoreCandidate(id) }
    fun addCategory(name: String, icon: String) = runOperation("分类已添加") { repository.addCategory(name, icon) }
    fun setCategoryHidden(id: String, hidden: Boolean) = viewModelScope.launch { repository.setCategoryHidden(id, hidden) }
    fun recordRefund(originalId: String, amountMinor: Long) = runOperation("退款已记录") {
        repository.recordRefund(originalId, amountMinor)
    }
    fun setTotalBudget(amountMinor: Long) = viewModelScope.launch {
        repository.upsertBudget(BudgetEntity(periodMonth = monthKey(monthOffset.value), limitMinor = amountMinor))
    }
    fun setBudget(categoryId: String?, amountMinor: Long) = viewModelScope.launch {
        repository.upsertBudget(BudgetEntity(periodMonth = monthKey(monthOffset.value), categoryId = categoryId, limitMinor = amountMinor))
    }

    fun writeBackup(resolver: ContentResolver, uri: Uri) = runOperation("备份已创建") {
        backupManager.writeBackup(resolver, uri)
    }
    fun restoreBackup(resolver: ContentResolver, uri: Uri) = runOperation("账本已恢复") {
        backupManager.restoreBackup(resolver, uri)
    }
    fun writeCsv(resolver: ContentResolver, uri: Uri) = runOperation("CSV 已导出") {
        backupManager.writeCsv(resolver, uri)
    }
    fun writeXlsx(resolver: ContentResolver, uri: Uri) = runOperation("Excel 已导出") {
        backupManager.writeXlsx(resolver, uri)
    }
    fun clearMessage() { operationMessage.value = null }

    private fun runOperation(success: String, block: suspend () -> Unit) = viewModelScope.launch {
        operationMessage.value = runCatching { block(); success }.getOrElse { "操作失败：${it.message ?: "未知错误"}" }
    }

    class Factory(
        private val repository: MojiRepository,
        private val settings: MojiSettings
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MojiViewModel(repository, settings) as T
    }
}

private fun monthKey(offset: Int): String {
    val c = Calendar.getInstance().apply { add(Calendar.MONTH, offset) }
    return "%04d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
}

fun startOfDay(value: Long): Long = Calendar.getInstance().apply {
    timeInMillis = value
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun startOfMonth(value: Long): Long = Calendar.getInstance().apply {
    timeInMillis = value
    set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun endOfMonth(value: Long): Long = Calendar.getInstance().apply {
    timeInMillis = startOfMonth(value)
    add(Calendar.MONTH, 1); add(Calendar.MILLISECOND, -1)
}.timeInMillis
