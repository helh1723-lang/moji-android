package com.moji.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoGraph
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SettingsAccessibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.core.app.NotificationManagerCompat
import com.moji.app.data.CategoryEntity
import com.moji.app.data.Direction
import com.moji.app.data.TransactionEntity
import com.moji.app.data.TransactionCandidateEntity
import com.moji.app.data.TransactionWithCategory
import com.moji.app.data.TransactionStatus
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class MainTab(val label: String) { LEDGER("账本"), STATS("统计"), PROFILE("我的") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MojiApp(
    viewModel: MojiViewModel,
    editTransactionId: String? = null,
    onEditConsumed: () -> Unit = {}
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    MojiTheme(darkTheme = settings.darkTheme) {
        Surface(Modifier.fillMaxSize()) {
            if (!settings.onboardingDone) {
                WelcomeScreen(viewModel::completeOnboarding)
            } else {
                MainShell(viewModel, editTransactionId, onEditConsumed)
            }
        }
    }
}

@Composable
private fun WelcomeScreen(onContinue: () -> Unit) {
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
    Column(
        Modifier.fillMaxSize().widthIn(max = 760.dp).padding(24.dp), verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Spacer(Modifier.height(56.dp))
            Text("默迹", fontSize = 46.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text("让每一笔消费，悄悄留下轨迹。", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(56.dp))
            FeatureLine("¥", "本地记账", "没有账号，没有广告，离线完整可用")
            FeatureLine("✓", "自动优先", "高可信支付自动入账，异常时由你确认")
            FeatureLine("◌", "数据归你", "账本仅保存在手机，可备份和导出")
        }
        Column {
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("开始使用") }
            Spacer(Modifier.height(12.dp))
            Text("自动记账权限稍后单独说明和开启，手动记账始终可用。", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
    }
}

@Composable
private fun FeatureLine(symbol: String, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Text(symbol, color = MaterialTheme.colorScheme.primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(16.dp))
        Column { Text(title, fontWeight = FontWeight.SemiBold); Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(
    viewModel: MojiViewModel,
    editTransactionId: String?,
    onEditConsumed: () -> Unit
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val budgets by viewModel.budgets.collectAsStateWithLifecycle()
    val allRows by viewModel.allRows.collectAsStateWithLifecycle()
    val message by viewModel.operationMessage.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(MainTab.LEDGER) }
    var editing by remember { mutableStateOf<TransactionEntity?>(null) }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var refunding by remember { mutableStateOf<TransactionEntity?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }

    LaunchedEffect(editTransactionId, allRows) {
        val requested = editTransactionId ?: return@LaunchedEffect
        val transaction = allRows.firstOrNull { it.transaction.id == requested }?.transaction
            ?: return@LaunchedEffect
        tab = MainTab.LEDGER
        editing = transaction
        showEditor = true
        onEditConsumed()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(
                    modifier = Modifier.navigationBarsPadding(),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    MainTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = {
                                Icon(
                                    when (item) {
                                        MainTab.LEDGER -> Icons.Outlined.AccountBalanceWallet
                                        MainTab.STATS -> Icons.Outlined.AutoGraph
                                        MainTab.PROFILE -> Icons.Outlined.Person
                                    }, item.label
                                )
                            },
                            label = { Text(item.label, style = MaterialTheme.typography.labelMedium) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            val screenModifier = Modifier.fillMaxHeight().widthIn(max = 760.dp)
            when (tab) {
                MainTab.LEDGER -> LedgerScreen(
                    ui = ui,
                    budgetMinor = budgets.firstOrNull { it.categoryId == null }?.limitMinor,
                    showSearch = showSearch,
                    onSearchToggle = { showSearch = !showSearch; if (!showSearch) viewModel.setQuery("") },
                    onQuery = viewModel::setQuery,
                    onPreviousMonth = viewModel::previousMonth,
                    onNextMonth = viewModel::nextMonth,
                    onAdd = { editing = null; showEditor = true },
                    onOpen = { editing = it; showEditor = true },
                    modifier = screenModifier
                )
                MainTab.STATS -> StatsScreen(ui, allRows, screenModifier)
                MainTab.PROFILE -> ProfileScreen(viewModel, screenModifier)
            }
        }
    }

    if (showEditor) {
        TransactionEditorSheet(
            transaction = editing,
            categories = ui.categories.filterNot { it.hidden },
            onDismiss = { showEditor = false },
            onSave = { id, amount, direction, merchant, category, note, include, createRule ->
                viewModel.saveTransaction(id, amount, direction, merchant, category, editing?.occurredAt ?: System.currentTimeMillis(), note, include, createRule)
                showEditor = false
            },
            onDelete = editing?.let { tx ->
                {
                    viewModel.deleteTransaction(tx.id)
                    showEditor = false
                    scope.launch {
                        val result = snackbar.showSnackbar("已删除 ${tx.merchantRaw ?: "一笔账单"}", actionLabel = "撤销")
                        if (result.name == "ActionPerformed") viewModel.restoreTransaction(tx.id)
                    }
                }
            },
            onRefund = editing?.takeIf { it.direction == Direction.EXPENSE.name && it.status != TransactionStatus.FULLY_REFUNDED.name }?.let { tx ->
                { showEditor = false; refunding = tx }
            }
        )
    }
    refunding?.let { original ->
        RefundDialog(
            original = original,
            onDismiss = { refunding = null },
            onSave = { amount -> viewModel.recordRefund(original.id, amount); refunding = null }
        )
    }
}

@Composable
private fun LedgerScreen(
    ui: LedgerUiState,
    budgetMinor: Long?,
    showSearch: Boolean,
    onSearchToggle: () -> Unit,
    onQuery: (String) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (TransactionEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val monthFormat = remember { SimpleDateFormat("yyyy 年 M 月", Locale.CHINA) }
    val dayFormat = remember { SimpleDateFormat("M 月 d 日 · EEEE", Locale.CHINA) }
    val grouped = ui.rows.groupBy { startOfDay(it.transaction.occurredAt) }
    LazyColumn(
        modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPreviousMonth) { Icon(Icons.Outlined.ChevronLeft, "上个月") }
                Text(
                    monthFormat.format(Date(ui.monthStart)),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                IconButton(onClick = onNextMonth) { Icon(Icons.Outlined.ChevronRight, "下个月") }
                IconButton(onClick = onSearchToggle) { Icon(Icons.Outlined.Search, "搜索账单") }
                IconButton(onClick = onAdd) { Icon(Icons.Outlined.Add, "手动记账") }
            }
            if (showSearch) {
                OutlinedTextField(
                    value = ui.query, onValueChange = onQuery, singleLine = true,
                    placeholder = { Text("商户、分类、备注或金额") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp)) {
                Text(
                    "本月支出",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    money(ui.summary.monthExpenseMinor),
                    style = MaterialTheme.typography.displayMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SummaryValue("今日", money(ui.summary.todayExpenseMinor), Modifier.weight(1f))
                    Box(Modifier.width(1.dp).height(44.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    val remaining = budgetMinor?.minus(ui.summary.monthExpenseMinor)
                    SummaryValue(
                        if (remaining != null && remaining < 0) "预算超出" else "预算剩余",
                        remaining?.let { money(kotlin.math.abs(it)) } ?: "未设置",
                        Modifier.weight(1f).padding(start = 24.dp)
                    )
                }
            }
        }
        if (grouped.isEmpty()) {
            item { EmptyLedger(onAdd) }
        } else {
            grouped.forEach { (day, rows) ->
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 8.dp)) {
                        Text(
                            dayFormat.format(Date(day)),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            money(rows.filter { it.transaction.direction == Direction.EXPENSE.name }.sumOf { it.transaction.amountMinor }),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                items(rows, key = { it.transaction.id }) { row -> TransactionRow(row) { onOpen(row.transaction) } }
            }
        }
    }
}

@Composable
private fun SummaryValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun EmptyLedger(onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 56.dp)) {
        Text("这个月还很安静", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("第一笔记录会从这里开始。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onAdd) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("记一笔") }
    }
}

@Composable
private fun TransactionRow(row: TransactionWithCategory, onClick: () -> Unit) {
    val income = row.transaction.direction == Direction.INCOME.name
    val prefix = when(row.transaction.direction) { Direction.INCOME.name -> "+"; Direction.TRANSFER.name -> "↔ "; else -> "−" }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) { Text(row.category?.icon ?: "◌", fontSize = 19.sp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.transaction.merchantRaw ?: if (income) "一笔收入" else "未知商户",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(row.category?.name ?: "其他", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                prefix + money(row.transaction.amountMinor),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = if (income) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 82.dp, end = 24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionEditorSheet(
    transaction: TransactionEntity?, categories: List<CategoryEntity>, onDismiss: () -> Unit,
    onSave: (String?, Long, Direction, String?, String, String?, Boolean, Boolean) -> Unit,
    onDelete: (() -> Unit)?,
    onRefund: (() -> Unit)?
) {
    var amount by remember(transaction?.id) { mutableStateOf(transaction?.let { "%.2f".format(Locale.ROOT, it.amountMinor / 100.0) }.orEmpty()) }
    var merchant by remember(transaction?.id) { mutableStateOf(transaction?.merchantRaw.orEmpty()) }
    var note by remember(transaction?.id) { mutableStateOf(transaction?.note.orEmpty()) }
    var direction by remember(transaction?.id) { mutableStateOf(runCatching { Direction.valueOf(transaction?.direction ?: Direction.EXPENSE.name) }.getOrDefault(Direction.EXPENSE)) }
    var categoryId by remember(transaction?.id, categories) { mutableStateOf(transaction?.categoryId ?: categories.firstOrNull()?.id.orEmpty()) }
    var include by remember(transaction?.id) { mutableStateOf(transaction?.includeInStats ?: true) }
    var createRule by remember(transaction?.id) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
                .padding(horizontal = 20.dp).padding(bottom = 32.dp)
        ) {
            Text(if (transaction == null) "记一笔" else "编辑账单", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = direction == Direction.EXPENSE, onClick = { direction = Direction.EXPENSE }, label = { Text("支出") })
                FilterChip(selected = direction == Direction.INCOME, onClick = { direction = Direction.INCOME }, label = { Text("收入") })
                FilterChip(selected = direction == Direction.TRANSFER, onClick = { direction = Direction.TRANSFER; include = false }, label = { Text("转账") })
            }
            OutlinedTextField(
                value = amount, onValueChange = { value -> amount = value.filter { it.isDigit() || it == '.' }.take(12); error = null },
                label = { Text("金额") }, prefix = { Text("¥ ") }, singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(12.dp))
            Text("分类", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { category -> FilterChip(selected = categoryId == category.id, onClick = { categoryId = category.id }, label = { Text("${category.icon} ${category.name}") }) }
            }
            OutlinedTextField(merchant, { merchant = it.take(50) }, label = { Text("商户（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(note, { note = it.take(200) }, label = { Text("备注（可选）") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().clickable { include = !include }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(include, { include = it }); Text("计入统计")
            }
            if (merchant.isNotBlank()) {
                Row(Modifier.fillMaxWidth().clickable { createRule = !createRule }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(createRule, { createRule = it }); Text("以后将这个商户自动归入所选分类")
                }
            }
            Button(
                onClick = {
                    val minor = parseAmountMinor(amount)
                    if (minor == null || minor <= 0) error = "请输入大于 0、最多两位小数的金额"
                    else if (categoryId.isBlank()) error = "请选择分类"
                    else onSave(transaction?.id, minor, direction, merchant, categoryId, note, include, createRule)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("保存") }
            if (onRefund != null) {
                OutlinedButton(onClick = onRefund, modifier = Modifier.fillMaxWidth()) { Text("记录退款") }
            }
            if (onDelete != null) {
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Delete, null); Text("删除这笔账单") }
            }
        }
    }
}

@Composable
private fun RefundDialog(original: TransactionEntity, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var value by remember { mutableStateOf("%.2f".format(Locale.ROOT, original.amountMinor / 100.0)) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记录退款") },
        text = {
            Column {
                Text(original.merchantRaw ?: "未知商户", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value, { value = it.filter { c -> c.isDigit() || c == '.' }; error = null }, label = { Text("退款金额") }, prefix = { Text("¥ ") }, isError = error != null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { Button(onClick = { val amount=parseAmountMinor(value); if(amount==null||amount<=0||amount>original.amountMinor) error="退款金额必须大于 0 且不超过原金额" else onSave(amount) }) { Text("保存退款") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun StatsScreen(ui: LedgerUiState, allRows: List<TransactionWithCategory>, modifier: Modifier = Modifier) {
    var yearly by rememberSaveable { mutableStateOf(false) }
    val yearStart = remember { java.util.Calendar.getInstance().apply { set(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY,0);set(java.util.Calendar.MINUTE,0);set(java.util.Calendar.SECOND,0);set(java.util.Calendar.MILLISECOND,0) }.timeInMillis }
    val periodRows = if (yearly) allRows.filter { it.transaction.occurredAt >= yearStart } else ui.rows
    val valid = periodRows.filter { it.transaction.includeInStats }
    val expenses = valid.filter { it.transaction.direction == Direction.EXPENSE.name }
    val refunds = valid.filter { it.transaction.status == TransactionStatus.REFUND.name }
    val income = valid.filter { it.transaction.direction == Direction.INCOME.name && it.transaction.status != TransactionStatus.REFUND.name }.sumOf { it.transaction.amountMinor }
    val expenseTotal = (expenses.sumOf { it.transaction.amountMinor } - refunds.sumOf { it.transaction.amountMinor }).coerceAtLeast(0)
    val categoryTotals = valid.groupBy { it.category?.name ?: "其他" }.mapValues { (_, rows) ->
        (rows.filter { it.transaction.direction == Direction.EXPENSE.name }.sumOf { it.transaction.amountMinor } - rows.filter { it.transaction.status == TransactionStatus.REFUND.name }.sumOf { it.transaction.amountMinor }).coerceAtLeast(0)
    }.filterValues { it > 0 }.entries.sortedByDescending { it.value }
    val max = categoryTotals.maxOfOrNull { it.value } ?: 1L
    LazyColumn(
        modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("统计", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                PeriodOption("月", selected = !yearly) { yearly = false }
                PeriodOption("年", selected = yearly) { yearly = true }
            }
            Spacer(Modifier.height(32.dp))
        }
        item {
            Column(Modifier.fillMaxWidth()) {
                Text(if(yearly) "本年总支出" else "本月总支出", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(money(expenseTotal), style = MaterialTheme.typography.displayMedium, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SummaryValue("收入", money(income), Modifier.weight(1f))
                    Box(Modifier.width(1.dp).height(44.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    SummaryValue("结余", money(income - expenseTotal), Modifier.weight(1f).padding(start = 24.dp))
                }
                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        item {
            Spacer(Modifier.height(28.dp))
            Text("支出趋势", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(18.dp))
            ExpenseTrend(valid)
            Spacer(Modifier.height(32.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(28.dp))
            Text("分类排行", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(18.dp))
        }
        if (categoryTotals.isEmpty()) item { Text("暂无支出数据", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp)) }
        items(categoryTotals.toList()) { item ->
            CategoryBar(item.key, item.value, item.value.toFloat() / max)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun PeriodOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(48.dp).clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(18.dp).height(2.dp).background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent))
    }
}

@Composable
private fun ExpenseTrend(expenses: List<TransactionWithCategory>) {
    val rawPoints = expenses.groupBy { startOfDay(it.transaction.occurredAt) }
        .mapValues { (_, rows) -> (rows.filter { it.transaction.direction == Direction.EXPENSE.name }.sumOf { it.transaction.amountMinor } - rows.filter { it.transaction.status == TransactionStatus.REFUND.name }.sumOf { it.transaction.amountMinor }).coerceAtLeast(0) }
        .toSortedMap().entries.toList()
    val points = rawPoints.filter { it.value > 0 }
    if (points.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            Text(if (rawPoints.isEmpty()) "暂无趋势数据" else "本期净支出为 0", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val max = points.maxOf { it.value }.coerceAtLeast(1L)
    Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = "支出趋势，共 ${points.size} 个有支出的日期" }) {
        repeat(4) { index ->
            val y = size.height * index / 3f
            drawLine(gridColor, Offset(0f,y), Offset(size.width,y), strokeWidth = 1.dp.toPx())
        }
        val offsets = points.mapIndexed { index, entry ->
            val x = if(points.size == 1) size.width / 2 else size.width * index / (points.size - 1f)
            val y = size.height - size.height * (entry.value.toFloat() / max)
            Offset(x,y)
        }
        offsets.zipWithNext().forEach { (a,b) -> drawLine(lineColor,a,b,strokeWidth=3.dp.toPx(),cap=StrokeCap.Round) }
        offsets.forEach { drawCircle(lineColor, radius=4.dp.toPx(), center=it) }
    }
}

@Composable
private fun CategoryBar(name: String, value: Long, ratio: Float) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(money(value), style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(9.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(ratio.coerceIn(0.02f, 1f)).height(6.dp).background(MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
private fun ProfileScreen(viewModel: MojiViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val budgets by viewModel.budgets.collectAsStateWithLifecycle()
    val pending by viewModel.pendingCandidates.collectAsStateWithLifecycle()
    var showConsent by remember { mutableStateOf(false) }
    var showBudget by remember { mutableStateOf(false) }
    var showBackupWarning by remember { mutableStateOf(false) }
    var showCategories by remember { mutableStateOf(false) }
    var accessibilityEnabled by remember { mutableStateOf(false) }
    var notificationEnabled by remember { mutableStateOf(false) }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { viewModel.writeBackup(context.contentResolver, it) } }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.restoreBackup(context.contentResolver, it) } }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { viewModel.writeCsv(context.contentResolver, it) } }
    val xlsxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri -> uri?.let { viewModel.writeXlsx(context.contentResolver, it) } }

    LifecycleResumeEffect(Unit) {
        accessibilityEnabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { component -> component.startsWith(context.packageName) && component.substringAfter('/').endsWith("MojiAccessibilityService") }
        notificationEnabled = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        onPauseOrDispose { }
    }

    LazyColumn(modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("我的", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp))
            Text(
                "本地账本与自动记账设置",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 18.dp)
            )
        }
        item { SectionTitle("自动记账") }
        if (pending.isNotEmpty()) {
            item { Text("${pending.size} 笔待确认", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            items(pending, key = { it.id }) { candidate -> PendingCandidateRow(candidate, viewModel::confirmCandidate, viewModel::ignoreCandidate) }
        }
        item {
            SettingRow(Icons.Outlined.SettingsAccessibility, "无障碍自动记账", when { accessibilityEnabled -> "服务已开启"; settings.captureConsent -> "已同意用途，服务尚未开启"; else -> "需要先阅读并同意用途" }) {
                if (settings.captureConsent) context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) else showConsent = true
            }
        }
        item {
            SettingRow(Icons.Outlined.Notifications, "通知辅助识别", if(notificationEnabled) "已开启，仅作交叉验证" else "可选，当前未开启") {
                context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
        }
        item {
            SettingRow(Icons.Outlined.Edit, "脱敏调试采样", if (settings.debugCaptureUntil > System.currentTimeMillis()) "已开启，24 小时内自动关闭" else "仅保存结构特征，不保存金额和商户") { viewModel.enableDebugCapture() }
        }
        item { SectionTitle("账本") }
        item { SettingRow(Icons.Outlined.Category, "分类管理", "新增分类或隐藏不常用分类") { showCategories = true } }
        item { SettingRow(Icons.Outlined.AccountBalanceWallet, "预算", budgets.firstOrNull { it.categoryId == null }?.let { "总预算 ${money(it.limitMinor)} · ${budgets.size - 1} 个分类预算" } ?: "未设置") { showBudget = true } }
        item { SectionTitle("数据") }
        item { SettingRow(Icons.Outlined.Backup, "创建完整备份", "未加密，请妥善保管") { showBackupWarning = true } }
        item { SettingRow(Icons.Outlined.Restore, "从备份恢复", "校验通过后替换当前账本") { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) } }
        item { SettingRow(Icons.Outlined.FileDownload, "导出 CSV", "可用 Excel 打开，共 ${ui.rows.size} 笔") { csvLauncher.launch("默迹账单.csv") } }
        item { SettingRow(Icons.Outlined.FileDownload, "导出 Excel", "日期和金额保持正确单元格类型") { xlsxLauncher.launch("默迹账单.xlsx") } }
        item { SectionTitle("外观") }
        item {
            SwitchSettingRow(
                icon = Icons.Outlined.DarkMode,
                title = "深色模式",
                subtitle = "降低夜间查看账本的亮度",
                checked = settings.darkTheme,
                onCheckedChange = viewModel::setDarkTheme
            )
        }
        item {
            SwitchSettingRow(
                icon = Icons.Outlined.PrivacyTip,
                title = "隐藏最近任务预览",
                subtitle = "开启后系统截图与投屏也会被阻止",
                checked = settings.hideRecents,
                onCheckedChange = viewModel::setHideRecents
            )
        }
        item { Text("数据仅保存在本机。普通日志不记录金额、商户、备注或页面正文。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp)) }
    }

    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text("开启自动记账前请确认") },
            text = { Text("默迹只在你主动打开的微信、支付宝支付相关页面中读取可访问性文字，用于识别金额、商户和支付结果。不会读取密码、验证码或聊天内容，不会点击页面、代替支付，也不会上传交易数据。你可以随时在系统设置中关闭。") },
            confirmButton = {
                Button(onClick = {
                    viewModel.setCaptureConsent(true); showConsent = false
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("我已了解，前往开启") }
            },
            dismissButton = { TextButton(onClick = { showConsent = false }) { Text("暂不开启") } }
        )
    }
    if (showBudget) BudgetDialog(
        budgets = budgets,
        categories = ui.categories.filterNot { it.hidden },
        onDismiss = { showBudget = false },
        onSave = { categoryId, amount -> viewModel.setBudget(categoryId, amount); showBudget = false }
    )
    if (showBackupWarning) {
        AlertDialog(
            onDismissRequest = { showBackupWarning = false },
            title = { Text("备份包含敏感财务数据") },
            text = { Text("当前完整备份未加密。请只保存到你信任的位置，不要发送给他人。") },
            confirmButton = { Button(onClick = { showBackupWarning = false; backupLauncher.launch("默迹完整备份.moji.zip") }) { Text("选择保存位置") } },
            dismissButton = { TextButton(onClick = { showBackupWarning = false }) { Text("取消") } }
        )
    }
    if (showCategories) {
        CategoryManagerSheet(
            categories = ui.categories,
            onDismiss = { showCategories = false },
            onAdd = viewModel::addCategory,
            onHidden = viewModel::setCategoryHidden
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryManagerSheet(
    categories: List<CategoryEntity>, onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit, onHidden: (String, Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("◌") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).padding(bottom = 32.dp)) {
            Text("分类管理", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(icon, { icon = it.take(4) }, label = { Text("图标") }, singleLine = true, modifier = Modifier.width(90.dp))
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(name, { name = it.take(20) }, label = { Text("新分类名称") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = { if(name.isNotBlank()){onAdd(name,icon);name=""} }) { Text("添加") }
            }
            Spacer(Modifier.height(20.dp))
            categories.forEach { category ->
                ListItem(
                    headlineContent = { Text("${category.icon} ${category.name}") },
                    supportingContent = { Text(if(category.origin == "SYSTEM") "系统分类" else "自定义分类") },
                    trailingContent = { Switch(!category.hidden, { onHidden(category.id, !it) }) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PendingCandidateRow(
    candidate: TransactionCandidateEntity,
    onConfirm: (TransactionCandidateEntity, Direction) -> Unit,
    onIgnore: (String) -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(candidate.merchant ?: "待确认交易", fontWeight = FontWeight.SemiBold)
                    Text("${candidate.platform} · ${candidate.conflictCode ?: "字段不确定"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(candidate.amountMinor?.let(::money) ?: "金额缺失", fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onIgnore(candidate.id) }) { Text("忽略") }
                TextButton(onClick = { onConfirm(candidate, Direction.INCOME) }, enabled = candidate.amountMinor != null) { Text("记为收入") }
                Button(onClick = { onConfirm(candidate, Direction.EXPENSE) }, enabled = candidate.amountMinor != null) { Text("记为支出") }
            }
        }
    }
}

@Composable
private fun SectionTitle(value: String) {
    Text(
        value,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 54.dp))
    }
}

@Composable
private fun SwitchSettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange).padding(end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = null)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 54.dp))
    }
}

@Composable
private fun BudgetDialog(
    budgets: List<com.moji.app.data.BudgetEntity>, categories: List<CategoryEntity>,
    onDismiss: () -> Unit, onSave: (String?, Long) -> Unit
) {
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var value by remember(selectedCategory, budgets) { mutableStateOf(budgets.firstOrNull { it.categoryId == selectedCategory }?.let { "%.2f".format(Locale.ROOT, it.limitMinor / 100.0) }.orEmpty()) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置本月总预算") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = selectedCategory == null, onClick = { selectedCategory = null }, label = { Text("总预算") })
                    categories.forEach { category -> FilterChip(selected = selectedCategory == category.id, onClick = { selectedCategory = category.id }, label = { Text("${category.icon} ${category.name}") }) }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value, { value = it.filter { c -> c.isDigit() || c == '.' }; error = false }, label = { Text("预算金额") }, prefix = { Text("¥ ") }, isError = error, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = { Button(onClick = { val amount = parseAmountMinor(value); if (amount == null || amount <= 0) error = true else onSave(selectedCategory, amount) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun parseAmountMinor(value: String): Long? = runCatching {
    BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
}.getOrNull()

private fun money(minor: Long): String = NumberFormat.getCurrencyInstance(Locale.CHINA).format(minor / 100.0)
