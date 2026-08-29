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
import com.moji.app.data.MerchantRuleEntity
import com.moji.app.data.MojiRepository
import com.moji.app.data.Platform
import com.moji.app.data.TransactionEntity
import com.moji.app.data.TransactionCandidateEntity
import com.moji.app.data.TransactionSource
import com.moji.app.data.TransactionWithCategory
import com.moji.app.backup.BackupManager
import com.moji.app.ai.AiConfig
import com.moji.app.ai.AiCredentialStore
import com.moji.app.ai.AiProvider
import com.moji.app.ai.AiTextParser
import com.moji.app.settings.MojiSettings
import com.moji.app.settings.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import java.util.Calendar

data class LedgerUiState(
    val rows: List<TransactionWithCategory> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val summary: LedgerSummary = LedgerSummary(),
    val monthStart: Long = startOfMonth(System.currentTimeMillis()),
    val monthEnd: Long = endOfMonth(System.currentTimeMillis()),
    val query: String = ""
)

data class LedgerFilters(
    val dateFrom: Long? = null,
    val dateTo: Long? = null,
    val platforms: Set<String> = emptySet(),
    val sources: Set<String> = emptySet(),
    val statuses: Set<String> = emptySet()
) {
    val activeCount: Int
        get() = listOf(dateFrom != null || dateTo != null, platforms.isNotEmpty(), sources.isNotEmpty(), statuses.isNotEmpty()).count { it }
}

internal fun TransactionWithCategory.matchesLedgerFilter(search: String, filters: LedgerFilters): Boolean {
    val transaction = transaction
    val textMatches = search.isBlank() ||
        transaction.merchantRaw.orEmpty().lowercase().contains(search) ||
        transaction.note.orEmpty().lowercase().contains(search) ||
        category?.name.orEmpty().lowercase().contains(search) ||
        transaction.amountMinor.toString().contains(search)
    return textMatches &&
        (filters.dateFrom == null || transaction.occurredAt >= filters.dateFrom) &&
        (filters.dateTo == null || transaction.occurredAt <= filters.dateTo) &&
        (filters.platforms.isEmpty() || transaction.platform in filters.platforms) &&
        (filters.sources.isEmpty() || transaction.source in filters.sources) &&
        (filters.statuses.isEmpty() || transaction.status in filters.statuses)
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MojiViewModel(
    private val repository: MojiRepository,
    private val settingsStore: MojiSettings,
    private val aiCredentials: AiCredentialStore
) : ViewModel() {
    private val backupManager = BackupManager(repository, settingsStore)
    private val monthOffset = MutableStateFlow(0)
    private val query = MutableStateFlow("")
    private val ledgerFilters = MutableStateFlow(LedgerFilters())
    val operationMessage = MutableStateFlow<String?>(null)

    val settings: StateFlow<UserSettings> = settingsStore.values.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings()
    )
    val pendingCandidates = repository.pendingCandidates.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )
    val rules: StateFlow<List<MerchantRuleEntity>> = repository.rules.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )
    val filters: StateFlow<LedgerFilters> = ledgerFilters
    val searchQuery: StateFlow<String> = query

    private val categories = repository.categories.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )
    private val monthBounds = monthOffset.map(::boundsForMonthOffset).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        boundsForMonthOffset(0)
    )
    private val monthTransactions = monthBounds.flatMapLatest { bounds ->
        repository.transactionsBetween(bounds.first, bounds.second)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val monthRows = combine(monthTransactions, categories) { transactions, categoryValues ->
        val byId = categoryValues.associateBy { it.id }
        transactions.map { TransactionWithCategory(it, byId[it.categoryId]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val currentMonthRows: StateFlow<List<TransactionWithCategory>> = monthRows

    val yearRows: StateFlow<List<TransactionWithCategory>> = combine(
        repository.transactionsBetween(startOfYear(System.currentTimeMillis()), endOfYear(System.currentTimeMillis())),
        categories
    ) { transactions, categoryValues ->
        val byId = categoryValues.associateBy { it.id }
        transactions.map { TransactionWithCategory(it, byId[it.categoryId]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private data class SearchState(val rawQuery: String, val normalizedQuery: String, val filters: LedgerFilters)
    private val searchState = combine(query.debounce(250), ledgerFilters) { search, values ->
        SearchState(search, search.trim().lowercase(), values)
    }

    val uiState: StateFlow<LedgerUiState> = combine(
        monthRows,
        categories,
        monthBounds,
        searchState
    ) { rows, categoryValues, bounds, searchState ->
        val start = bounds.first
        val end = bounds.second
        val todayStart = startOfDay(System.currentTimeMillis())
        val visible = rows.filter { it.matchesLedgerFilter(searchState.normalizedQuery, searchState.filters) }
        val valid = rows.map { it.transaction }.filter {
            it.includeInStats && it.status != "PENDING_REVIEW" && it.deletedAt == null
        }
        LedgerUiState(
            rows = visible,
            categories = categoryValues,
            summary = LedgerSummary(
                monthExpenseMinor = (valid.filter { it.direction == Direction.EXPENSE.name }.sumOf { it.amountMinor } -
                    valid.filter { it.status == "REFUND" }.sumOf { it.amountMinor }).coerceAtLeast(0),
                monthIncomeMinor = valid.filter { it.direction == Direction.INCOME.name && it.status != "REFUND" }.sumOf { it.amountMinor },
                todayExpenseMinor = (valid.filter { it.direction == Direction.EXPENSE.name && it.occurredAt >= todayStart }.sumOf { it.amountMinor } -
                    valid.filter { it.status == "REFUND" && it.occurredAt >= todayStart }.sumOf { it.amountMinor }).coerceAtLeast(0)
            ),
            monthStart = start,
            monthEnd = end,
            query = searchState.rawQuery
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerUiState())

    val budgets = monthOffset.flatMapLatest { offset ->
        repository.budgets(monthKey(offset))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun previousMonth() { monthOffset.value -= 1; clearDateFilter() }
    fun nextMonth() { if (monthOffset.value < 0) { monthOffset.value += 1; clearDateFilter() } }
    fun setQuery(value: String) { query.value = value }
    fun setFilters(value: LedgerFilters) { ledgerFilters.value = value }
    fun clearFilters() { ledgerFilters.value = LedgerFilters() }
    private fun clearDateFilter() { ledgerFilters.value = ledgerFilters.value.copy(dateFrom = null, dateTo = null) }

    suspend fun transactionById(id: String): TransactionEntity? = repository.transactionById(id)

    fun saveTransaction(
        existingId: String?, amountMinor: Long, direction: Direction, merchant: String?,
        categoryId: String, occurredAt: Long, note: String?, includeInStats: Boolean, createMerchantRule: Boolean,
        source: TransactionSource = TransactionSource.MANUAL
    ) = viewModelScope.launch {
        repository.saveTransaction(existingId, amountMinor, direction, merchant, categoryId, occurredAt, note, includeInStats, createMerchantRule, source)
    }

    fun saveVoiceTransaction(
        amountMinor: Long, direction: Direction, merchant: String?, categoryId: String,
        occurredAt: Long, note: String?, onComplete: (Result<Unit>) -> Unit
    ) = viewModelScope.launch {
        onComplete(runCatching {
            repository.saveTransaction(
                amountMinor = amountMinor, direction = direction, merchant = merchant,
                categoryId = categoryId, occurredAt = occurredAt, note = note,
                createMerchantRule = false, source = TransactionSource.VOICE
            )
        })
    }

    fun deleteTransaction(id: String) = viewModelScope.launch { repository.softDelete(id) }
    fun restoreTransaction(id: String) = viewModelScope.launch { repository.restore(id) }
    fun completeOnboarding() = viewModelScope.launch { settingsStore.completeOnboarding() }
    fun setDarkTheme(value: Boolean) = viewModelScope.launch { settingsStore.setDarkTheme(value) }
    fun setHideRecents(value: Boolean) = viewModelScope.launch { settingsStore.setHideRecents(value) }
    fun saveAiSettings(
        enabled: Boolean, provider: AiProvider, baseUrl: String, model: String, apiKey: String,
        clearKey: Boolean, onComplete: (Result<Unit>) -> Unit
    ) = viewModelScope.launch {
        val result = runCatching {
            val effectiveBaseUrl = baseUrl.trim()
            val effectiveModel = model.trim()
            require(effectiveBaseUrl.startsWith("https://")) { "接口地址必须使用 HTTPS" }
            require(effectiveModel.isNotBlank()) { "请填写模型名称" }
            if (clearKey) aiCredentials.clear()
            if (apiKey.isNotBlank()) aiCredentials.save(apiKey)
            val hasKey = aiCredentials.read() != null
            require(!enabled || hasKey) { "开启 AI 前请填写 API Key" }
            settingsStore.saveAiSettings(enabled, provider, effectiveBaseUrl, effectiveModel, hasKey)
        }
        onComplete(result)
    }
    fun parseTextWithAi(text: String, categories: List<CategoryEntity>, onComplete: (Result<List<com.moji.app.voice.VoiceDraft>>) -> Unit) = viewModelScope.launch {
        val result = runCatching {
            val values = settingsStore.backupValues()
            val provider = runCatching { AiProvider.valueOf(values.aiProvider) }.getOrDefault(AiProvider.CUSTOM)
            val key = aiCredentials.read() ?: error("请先在设置中填写 API Key")
            withContext(Dispatchers.IO) {
                AiTextParser.parse(AiConfig(values.aiEnabled, provider, values.aiBaseUrl, values.aiModel), key, text, categories)
            }
        }
        onComplete(result)
    }
    fun setCaptureConsent(value: Boolean) = viewModelScope.launch { settingsStore.setCaptureConsent(value) }
    fun enableDebugCapture() = viewModelScope.launch { settingsStore.enableDebugCapture() }
    fun confirmCandidate(candidate: TransactionCandidateEntity, direction: Direction) = viewModelScope.launch {
        repository.confirmCandidate(candidate, direction)
    }
    fun ignoreCandidate(id: String) = viewModelScope.launch { repository.ignoreCandidate(id) }
    fun addCategory(name: String, icon: String) = runOperation("分类已添加") { repository.addCategory(name, icon) }
    fun setCategoryHidden(id: String, hidden: Boolean) = viewModelScope.launch { repository.setCategoryHidden(id, hidden) }
    fun saveRule(id: String?, pattern: String, matchType: String, categoryId: String) =
        runOperation("商户规则已保存") { repository.saveRule(id, pattern, matchType, categoryId) }
    fun setRuleEnabled(id: String, enabled: Boolean) = viewModelScope.launch { repository.setRuleEnabled(id, enabled) }
    fun deleteRule(id: String) = runOperation("商户规则已删除") { repository.deleteRule(id) }
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
        private val settings: MojiSettings,
        private val aiCredentials: AiCredentialStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MojiViewModel(repository, settings, aiCredentials) as T
    }
}

private fun monthKey(offset: Int): String {
    val c = Calendar.getInstance().apply { add(Calendar.MONTH, offset) }
    return "%04d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
}

private fun boundsForMonthOffset(offset: Int): Pair<Long, Long> {
    val calendar = Calendar.getInstance().apply { add(Calendar.MONTH, offset) }
    return startOfMonth(calendar.timeInMillis) to endOfMonth(calendar.timeInMillis)
}

private fun startOfYear(value: Long): Long = Calendar.getInstance().apply {
    timeInMillis = value
    set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun endOfYear(value: Long): Long = Calendar.getInstance().apply {
    timeInMillis = startOfYear(value)
    add(Calendar.YEAR, 1); add(Calendar.MILLISECOND, -1)
}.timeInMillis

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
