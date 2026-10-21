package com.fengluanchui.pandianbao

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import com.fengluanchui.pandianbao.data.*
import com.fengluanchui.pandianbao.scanner.BarcodeScannerActivity
import com.fengluanchui.pandianbao.update.GitHubRelease
import com.fengluanchui.pandianbao.update.GitHubUpdateManager
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private enum class AppTab(val label: String) { HOME("工作台"), STOCKTAKES("盘点"), INVENTORY("库存"), PRODUCTS("商品"), LOCATIONS("库位"), UPDATES("更新") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) = super.onCreate(savedInstanceState).also {
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF1287EE))) { PanDianBaoApp(InventoryRepository(InventoryDatabase.create(this))) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PanDianBaoApp(repository: InventoryRepository) {
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    val locations by repository.locations.collectAsState(initial = emptyList())
    val products by repository.products.collectAsState(initial = emptyList())
    val tasks by repository.stocktakeTasks.collectAsState(initial = emptyList())
    var selectedLocationId by rememberSaveable { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    var openTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var scanTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var barcode by remember { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf<List<ProductEntity>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val selectedLocation = locations.firstOrNull { it.id == selectedLocationId }
    val inventory by (selectedLocation?.let { repository.inventory(it.id) } ?: flowOf(emptyList())).collectAsState(initial = emptyList())
    val total by (selectedLocation?.let { repository.locationTotal(it.id) } ?: flowOf(0L)).collectAsState(initial = 0L)
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val code = result.data?.getStringExtra(BarcodeScannerActivity.EXTRA_BARCODE)
        if (result.resultCode == Activity.RESULT_OK && !code.isNullOrBlank()) scope.launch {
            runCatching { repository.productsWithBarcode(code) }.onSuccess { found -> barcode = code; candidates = found }
                .onFailure { error = it.message ?: "读取商品失败" }
        }
    }
    LaunchedEffect(locations) { if (locations.isEmpty()) runCatching { repository.addLocation("默认库位") }.onFailure { error = it.message } else if (locations.none { it.id == selectedLocationId }) selectedLocationId = locations.first().id }

    Scaffold(topBar = { TopAppBar(title = { Text(if (openTaskId == null) "盘点宝" else "快速盘点") }, navigationIcon = { if (openTaskId != null) IconButton(onClick = { openTaskId = null }) { Icon(Icons.Default.ArrowBack, "返回盘点记录") } }, actions = { selectedLocation?.let { AssistChip(onClick = { selectedTab = AppTab.LOCATIONS }, label = { Text(it.name) }) } }) }, bottomBar = { if (openTaskId == null) NavigationBar { AppTab.entries.forEach { tab -> NavigationBarItem(selected = selectedTab == tab, onClick = { selectedTab = tab }, icon = { Icon(tabIcon(tab), tab.label) }, label = { Text(tab.label) }) } } }) { padding ->
        if (openTaskId != null) {
            val task = tasks.firstOrNull { it.id == openTaskId }
            if (task == null) LaunchedEffect(Unit) { openTaskId = null } else StocktakeDetailScreen(Modifier.padding(padding), task, repository.stocktakeLines(task.id).collectAsState(initial = emptyList()).value, selectedLocation, onScan = {
                if (task.isCompleted) error = "该盘点已完成，只能查看和导出" else if (selectedLocation == null) error = "请先选择库位" else { scanTaskId = task.id; scanner.launch(Intent(context, BarcodeScannerActivity::class.java)) }
            }, onSetQuantity = { line, quantity -> scope.launch { runCatching { repository.setStocktakeLineQuantity(task.id, line, quantity) }.onFailure { error = it.message ?: "修改失败" } } }, onComplete = { scope.launch { runCatching { repository.completeStocktake(task.id) }.onSuccess { error = "盘点已完成并写入库存流水。历史明细已冻结。" }.onFailure { error = it.message ?: "完成失败" } } })
        } else when (selectedTab) {
            AppTab.HOME -> HomeScreen(Modifier.padding(padding), selectedLocation, products.size, total, onStocktake = { selectedTab = AppTab.STOCKTAKES }, onInventory = { selectedTab = AppTab.INVENTORY }, onProducts = { selectedTab = AppTab.PRODUCTS })
            AppTab.STOCKTAKES -> StocktakeListScreen(Modifier.padding(padding), tasks, onCreate = { title -> scope.launch { runCatching { repository.createStocktake(title) }.onSuccess { openTaskId = it }.onFailure { error = it.message ?: "创建失败" } } }, onOpen = { openTaskId = it })
            AppTab.INVENTORY -> InventoryScreen(Modifier.padding(padding), selectedLocation, inventory, onRecord = { row, type, quantity -> selectedLocation?.let { loc -> scope.launch { runCatching { repository.recordMovement(row, loc.id, type, quantity) }.onFailure { error = it.message ?: "保存失败" } } } })
            AppTab.PRODUCTS -> ProductsScreen(Modifier.padding(padding), products, onAdd = { code, name, note -> scope.launch { runCatching { repository.addProduct(code, name, note) }.onFailure { error = it.message ?: "商品保存失败，可能是重复条码与变体" } } })
            AppTab.LOCATIONS -> LocationsScreen(Modifier.padding(padding), locations, selectedLocationId, onSelect = { selectedLocationId = it }, onAdd = { name -> scope.launch { runCatching { repository.addLocation(name) }.onFailure { error = it.message ?: "库位保存失败" } } })
            AppTab.UPDATES -> UpdatesScreen(Modifier.padding(padding), remember(context) { GitHubUpdateManager(context) })
        }
    }
    if (candidates != null && barcode != null) {
        val code = barcode!!; val found = candidates!!; val taskId = scanTaskId; val location = selectedLocation
        if (taskId == null || location == null) { error = "请先创建并打开快速盘点任务"; candidates = null; barcode = null }
        else {
            val activeTaskId: String = taskId
            val activeLocation: LocationEntity = location
            if (found.isEmpty()) ProductDialog("未知条码建档", code, onDismiss = { candidates = null; barcode = null }, onConfirm = { enteredCode, name, note -> scope.launch { runCatching { repository.addProductAndCount(activeTaskId, enteredCode, name, note, activeLocation) }.onSuccess { error = "已建档，并已为本次盘点自动记 1"; candidates = null; barcode = null }.onFailure { error = it.message ?: "建档失败" } } })
            else BarcodeProductChooser(found, onDismiss = { candidates = null; barcode = null }, onChoose = { product -> scope.launch { runCatching { repository.countProduct(activeTaskId, product, activeLocation) }.onSuccess { error = "${product.name} 已计数 +1（${activeLocation.name}）" }.onFailure { error = it.message ?: "计数失败" }; candidates = null; barcode = null } })
        }
    }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("提示") }, text = { Text(message) }, confirmButton = { Button(onClick = { error = null }) { Text("知道了") } }) }
}

@Composable private fun HomeScreen(modifier: Modifier, location: LocationEntity?, productCount: Int, total: Long, onStocktake: () -> Unit, onInventory: () -> Unit, onProducts: () -> Unit) = Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Text("本地库存工作台", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) { Text(location?.name ?: "正在准备库位", fontWeight = FontWeight.Bold); Text("商品：$productCount  ·  当前库存：$total") } }
    Button(onClick = onStocktake, modifier = Modifier.fillMaxWidth().height(56.dp)) { Icon(Icons.Default.QrCode2, null); Spacer(Modifier.width(8.dp)); Text("快速盘点") }
    OutlinedButton(onClick = onInventory, modifier = Modifier.fillMaxWidth()) { Text("在库资产 / 手动入库、出库") }
    OutlinedButton(onClick = onProducts, modifier = Modifier.fillMaxWidth()) { Text("商品登记") }
    Text("离线数据仅保存于本机。快速盘点支持按库位独立计数，完成后生成不可变历史和库存调整流水。", style = MaterialTheme.typography.bodySmall)
}

@Composable private fun StocktakeListScreen(modifier: Modifier, tasks: List<StocktakeTaskSummary>, onCreate: (String) -> Unit, onOpen: (String) -> Unit) {
    var create by remember { mutableStateOf(false) }
    Scaffold(modifier = modifier, floatingActionButton = { ExtendedFloatingActionButton(onClick = { create = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("新建盘点") }) }) { inner -> Column(Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp)) {
        Text("盘点记录", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp)); Text("进行中的任务可继续扫码；已完成任务为只读快照。")
        if (tasks.isEmpty()) EmptyState("还没有盘点任务。点击右下角新建快速盘点。") else LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(tasks, key = { it.id }) { task -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(task.title, fontWeight = FontWeight.Bold); Text("品类：${task.varietyCount}  数量：${task.totalQuantity}"); Text(if (task.isCompleted) "已完成（只读）" else "进行中", color = if (task.isCompleted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary) }; Button(onClick = { onOpen(task.id) }) { Text(if (task.isCompleted) "详情" else "继续") } } } } }
    } }
    if (create) NameDialog("创建快速盘点", "本次计划/任务名称", onDismiss = { create = false }) { onCreate(it); create = false }
}

@Composable private fun StocktakeDetailScreen(modifier: Modifier, task: StocktakeTaskSummary, lines: List<StocktakeLineRow>, location: LocationEntity?, onScan: () -> Unit, onSetQuantity: (StocktakeLineRow, Long) -> Unit, onComplete: () -> Unit) {
    var editing by remember { mutableStateOf<StocktakeLineRow?>(null) }; var completeConfirm by remember { mutableStateOf(false) }
    Scaffold(modifier = modifier, bottomBar = { Surface(shadowElevation = 8.dp) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("记录：${lines.size}  数量：${lines.sumOf { it.quantity }}  库位：${location?.name ?: "未选择"}"); if (task.isCompleted) Text("已完成：历史数据已冻结", color = MaterialTheme.colorScheme.primary) else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton(onClick = { completeConfirm = true }, modifier = Modifier.weight(1f)) { Text("完成盘点") }; Button(onClick = onScan, modifier = Modifier.weight(1f)) { Icon(Icons.Default.QrCode2, null); Spacer(Modifier.width(6.dp)); Text("开始扫码") } } } } }) { inner -> Column(Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp)) {
        Text(task.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp)); Text("序号  码值  名称  数量  库位", fontWeight = FontWeight.Bold)
        if (lines.isEmpty()) EmptyState(if (task.isCompleted) "此任务没有盘点明细。" else "点击“开始扫码”录入商品；未知条码建档后会自动记为 1。") else LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(lines, key = { "${it.productId}-${it.locationId}" }) { line -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(line.productName, fontWeight = FontWeight.Bold); Text(line.barcode, color = MaterialTheme.colorScheme.primary); Text("库位：${line.locationName}", style = MaterialTheme.typography.bodySmall) }; Text(line.quantity.toString(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary); if (!task.isCompleted) OutlinedButton(onClick = { editing = line }, modifier = Modifier.padding(start = 8.dp)) { Text("修改") } } } } }
    } }
    editing?.let { QuantityDialog(it, onDismiss = { editing = null }) { quantity -> onSetQuantity(it, quantity); editing = null } }
    if (completeConfirm) AlertDialog(onDismissRequest = { completeConfirm = false }, title = { Text("完成盘点？") }, text = { Text("系统会以每个条码和库位的实盘数量调整当前库存，并冻结此任务的历史明细。") }, confirmButton = { Button(onClick = { completeConfirm = false; onComplete() }) { Text("确认完成") } }, dismissButton = { OutlinedButton(onClick = { completeConfirm = false }) { Text("取消") } })
}

@Composable private fun QuantityDialog(line: StocktakeLineRow, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) { var value by remember { mutableStateOf(line.quantity.toString()) }; AlertDialog(onDismissRequest = onDismiss, title = { Text("修改数量") }, text = { OutlinedTextField(value, { value = it.filter(Char::isDigit) }, label = { Text(line.productName) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true) }, confirmButton = { Button(onClick = { value.toLongOrNull()?.let(onConfirm) }, enabled = value.toLongOrNull() != null) { Text("保存数量") } }, dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } }) }

@Composable private fun InventoryScreen(modifier: Modifier, location: LocationEntity?, rows: List<InventoryRow>, onRecord: (InventoryRow, DocumentType, Long) -> Unit) { var selected by remember { mutableStateOf<InventoryRow?>(null) }; Scaffold(modifier = modifier) { inner -> Column(Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp)) { Text("${location?.name ?: "未选择库位"} · 库存", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp)); if (rows.isEmpty()) EmptyState("暂无商品；请先登记或导入商品。") else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(rows, key = { it.productId }) { row -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(row.name, fontWeight = FontWeight.Bold); Text(row.displayBarcode); Text("当前数量：${row.quantity}") }; OutlinedButton(onClick = { selected = row }) { Text("操作") } } } } } } }; selected?.let { MovementDialog(it, onDismiss = { selected = null }) { type, quantity -> onRecord(it, type, quantity); selected = null } } }

@Composable private fun MovementDialog(row: InventoryRow, onDismiss: () -> Unit, onConfirm: (DocumentType, Long) -> Unit) { var type by remember { mutableStateOf(DocumentType.INBOUND) }; var quantity by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = onDismiss, title = { Text(row.name) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("当前库存：${row.quantity}"); Row { DocumentType.entries.filter { it != DocumentType.STOCKTAKE }.forEach { item -> AssistChip(onClick = { type = item }, label = { Text(typeLabel(item)) }) } }; OutlinedTextField(quantity, { quantity = it.filter(Char::isDigit) }, label = { Text("录入数量（正数）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) } }, confirmButton = { Button(onClick = { quantity.toLongOrNull()?.takeIf { it > 0 }?.let { onConfirm(type, it) } }, enabled = quantity.toLongOrNull()?.let { it > 0 } == true) { Text("保存") } }, dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } }) }

@Composable private fun ProductsScreen(modifier: Modifier, products: List<ProductEntity>, onAdd: (String, String, String) -> Unit) { var showAdd by remember { mutableStateOf(false) }; Scaffold(modifier = modifier, floatingActionButton = { FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "登记商品") } }) { inner -> Column(Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp)) { Text("商品库", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp)); if (products.isEmpty()) EmptyState("暂无商品。") else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(products, key = { it.id }) { product -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(product.name, fontWeight = FontWeight.Bold); Text(product.displayBarcode) } } } } } }; if (showAdd) ProductDialog("登记商品", onDismiss = { showAdd = false }) { c, n, note -> onAdd(c, n, note); showAdd = false } }

@Composable private fun ProductDialog(title: String, initialBarcode: String = "", onDismiss: () -> Unit, onConfirm: (String, String, String) -> Unit) { var code by remember(initialBarcode) { mutableStateOf(initialBarcode) }; var name by remember { mutableStateOf("") }; var note by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(code, { code = it.trim() }, label = { Text("原始条码") }, singleLine = true); OutlinedTextField(name, { name = it }, label = { Text("商品名称") }, singleLine = true); OutlinedTextField(note, { note = it }, label = { Text("同条码变体说明（可选）") }, singleLine = true) } }, confirmButton = { Button(onClick = { onConfirm(code, name, note) }, enabled = code.isNotBlank() && name.isNotBlank()) { Text("保存") } }, dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } }) }

@Composable private fun BarcodeProductChooser(products: List<ProductEntity>, onDismiss: () -> Unit, onChoose: (ProductEntity) -> Unit) = AlertDialog(onDismissRequest = onDismiss, title = { Text("识别到已有条码") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("选择本次盘点商品，系统会将当前库位数量加 1。") ; products.forEach { product -> OutlinedButton(onClick = { onChoose(product) }, modifier = Modifier.fillMaxWidth()) { Text("${product.name}\n${product.displayBarcode}") } } } }, confirmButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } })

@Composable private fun LocationsScreen(modifier: Modifier, locations: List<LocationEntity>, selectedId: String, onSelect: (String) -> Unit, onAdd: (String) -> Unit) { var add by remember { mutableStateOf(false) }; Scaffold(modifier = modifier, floatingActionButton = { FloatingActionButton(onClick = { add = true }) { Icon(Icons.Default.Add, "新建库位") } }) { inner -> LazyColumn(Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { item { Text("库位管理", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp)) }; items(locations, key = { it.id }) { location -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Text(location.name, Modifier.weight(1f), fontWeight = FontWeight.Bold); OutlinedButton(onClick = { onSelect(location.id) }) { Text(if (location.id == selectedId) "已选择" else "选择") } } } } } }; if (add) NameDialog("新建库位", "库位名称", { add = false }) { onAdd(it); add = false } }

@Composable private fun UpdatesScreen(modifier: Modifier, manager: GitHubUpdateManager) { val scope = rememberCoroutineScope(); var repo by remember { mutableStateOf(manager.repository) }; var release by remember { mutableStateOf<GitHubRelease?>(null) }; var status by remember { mutableStateOf("填写 GitHub 仓库后可检查更新。") }; Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("检查更新", style = MaterialTheme.typography.titleLarge); OutlinedTextField(repo, { repo = it }, label = { Text("GitHub 仓库（owner/repo）") }, modifier = Modifier.fillMaxWidth()); Button(onClick = { manager.repository = repo; scope.launch { runCatching { manager.checkLatest() }.onSuccess { release = it; status = if (it == null) "未发现 Release。" else "发现 ${it.versionName}" }.onFailure { status = it.message ?: "检查失败" } } }, enabled = repo.isNotBlank()) { Text("检查更新") }; Text(status); release?.takeIf { manager.isNewer(it) }?.let { Button(onClick = { scope.launch { runCatching { manager.downloadAndInstall(it) }.onFailure { status = it.message ?: "下载安装失败" } } }) { Text("下载并安装 ${it.versionName}") } } } }

@Composable private fun NameDialog(title: String, label: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) { var value by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true) }, confirmButton = { Button(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text("保存") } }, dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } }) }
@Composable private fun EmptyState(message: String) = Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(message) }
private fun typeLabel(type: DocumentType) = when (type) { DocumentType.STOCKTAKE -> "盘点"; DocumentType.INBOUND -> "入库"; DocumentType.OUTBOUND -> "出库" }
private fun tabIcon(tab: AppTab) = when (tab) { AppTab.HOME -> Icons.Default.Home; AppTab.STOCKTAKES -> Icons.Default.QrCode2; AppTab.INVENTORY -> Icons.Default.Inventory2; AppTab.PRODUCTS -> Icons.Default.Add; AppTab.LOCATIONS -> Icons.Default.LocationOn; AppTab.UPDATES -> Icons.Default.Settings }
