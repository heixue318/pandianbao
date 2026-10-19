package com.fengluanchui.pandianbao

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fengluanchui.pandianbao.data.DocumentType
import com.fengluanchui.pandianbao.data.InventoryDatabase
import com.fengluanchui.pandianbao.data.InventoryRepository
import com.fengluanchui.pandianbao.data.InventoryRow
import com.fengluanchui.pandianbao.data.LocationEntity
import com.fengluanchui.pandianbao.data.ProductEntity
import com.fengluanchui.pandianbao.scanner.BarcodeScannerActivity
import com.fengluanchui.pandianbao.update.GitHubRelease
import com.fengluanchui.pandianbao.update.GitHubUpdateManager
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private enum class AppTab(val label: String) { HOME("工作台"), INVENTORY("库存"), PRODUCTS("商品"), LOCATIONS("库位"), UPDATES("更新") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF126DDB))) {
                PanDianBaoApp(InventoryRepository(InventoryDatabase.create(this)))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PanDianBaoApp(repository: InventoryRepository) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val updateManager = remember(context) { GitHubUpdateManager(context) }
    val locations by repository.locations.collectAsState(initial = emptyList())
    var selectedLocationId by rememberSaveable { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    var error by remember { mutableStateOf<String?>(null) }
    var scannedBarcode by remember { mutableStateOf<String?>(null) }
    var scannedCandidates by remember { mutableStateOf<List<ProductEntity>?>(null) }
    var scannedRow by remember { mutableStateOf<InventoryRow?>(null) }
    val scannerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val barcode = result.data?.getStringExtra(BarcodeScannerActivity.EXTRA_BARCODE)
        if (result.resultCode == Activity.RESULT_OK && !barcode.isNullOrBlank()) {
            scope.launch {
                runCatching { repository.productsWithBarcode(barcode) }
                    .onSuccess { candidates -> scannedBarcode = barcode; scannedCandidates = candidates }
                    .onFailure { error = it.message ?: "读取商品失败" }
            }
        }
    }

    LaunchedEffect(locations) {
        if (locations.isEmpty()) {
            runCatching { repository.addLocation("默认库位") }.onFailure { error = it.message }
        } else if (selectedLocationId.isBlank() || locations.none { it.id == selectedLocationId }) {
            selectedLocationId = locations.first().id
        }
    }

    val selectedLocation = locations.firstOrNull { it.id == selectedLocationId }
    val inventoryFlow = if (selectedLocation == null) flowOf(emptyList<InventoryRow>()) else repository.inventory(selectedLocation.id)
    val totalFlow = if (selectedLocation == null) flowOf(0L) else repository.locationTotal(selectedLocation.id)
    val inventory by inventoryFlow.collectAsState(initial = emptyList())
    val products by repository.products.collectAsState(initial = emptyList())
    val total by totalFlow.collectAsState(initial = 0L)

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("盘点宝") }, actions = {
                selectedLocation?.let { AssistChip(onClick = { selectedTab = AppTab.LOCATIONS }, label = { Text(it.name) }) }
            })
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tabIcon(tab), contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        when (selectedTab) {
            AppTab.HOME -> HomeScreen(
                modifier = Modifier.padding(padding),
                location = selectedLocation,
                productCount = products.size,
                total = total,
                onInventory = { selectedTab = AppTab.INVENTORY },
                onProducts = { selectedTab = AppTab.PRODUCTS },
                onScan = { scannerLauncher.launch(Intent(context, BarcodeScannerActivity::class.java)) }
            )
            AppTab.INVENTORY -> InventoryScreen(
                modifier = Modifier.padding(padding),
                location = selectedLocation,
                rows = inventory,
                onRecord = { row, type, amount ->
                    if (selectedLocation == null) return@InventoryScreen
                    scope.launch {
                        runCatching { repository.recordMovement(row, selectedLocation.id, type, amount) }
                            .onFailure { error = it.message ?: "保存失败" }
                    }
                }
            )
            AppTab.PRODUCTS -> ProductsScreen(
                modifier = Modifier.padding(padding),
                products = products,
                onAdd = { barcode, name, note ->
                    scope.launch {
                        runCatching { repository.addProduct(barcode, name, note) }
                            .onFailure { error = it.message ?: "商品保存失败，可能是同一条码变体已存在" }
                    }
                }
            )
            AppTab.LOCATIONS -> LocationsScreen(
                modifier = Modifier.padding(padding),
                locations = locations,
                selectedId = selectedLocationId,
                onSelect = { selectedLocationId = it },
                onAdd = { name ->
                    scope.launch { runCatching { repository.addLocation(name) }.onFailure { error = it.message ?: "库位保存失败" } }
                }
            )
            AppTab.UPDATES -> UpdatesScreen(modifier = Modifier.padding(padding), updateManager = updateManager)
        }
    }
    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, confirmButton = { Button(onClick = { error = null }) { Text("知道了") } }, title = { Text("提示") }, text = { Text(message) })
    }
    if (scannedCandidates != null && scannedBarcode != null) {
        val barcode = scannedBarcode!!
        val candidates = scannedCandidates!!
        if (candidates.isEmpty()) {
            ProductDialog(initialBarcode = barcode, onDismiss = { scannedCandidates = null; scannedBarcode = null }, onConfirm = { code, name, note ->
                scope.launch { runCatching { repository.addProduct(code, name, note) }.onSuccess {
                    scannedCandidates = null; scannedBarcode = null
                }.onFailure { error = it.message ?: "商品保存失败" } }
            })
        } else {
            BarcodeProductChooser(candidates, onDismiss = { scannedCandidates = null; scannedBarcode = null }, onChoose = { product ->
                scannedRow = inventory.firstOrNull { it.productId == product.id } ?: InventoryRow(product.id, product.rawBarcode, product.variantNote, product.name, 0)
                scannedCandidates = null; scannedBarcode = null
            })
        }
    }
    scannedRow?.let { row -> MovementDialog(row, DocumentType.STOCKTAKE, onDismiss = { scannedRow = null }, onConfirm = { type, quantity ->
        val location = selectedLocation
        if (location == null) error = "请先选择库位" else scope.launch {
            runCatching { repository.recordMovement(row, location.id, type, quantity) }
                .onFailure { error = it.message ?: "保存失败" }
        }
        scannedRow = null
    }) }
}

@Composable
private fun UpdatesScreen(modifier: Modifier, updateManager: GitHubUpdateManager) {
    val scope = rememberCoroutineScope()
    var repository by remember { mutableStateOf(updateManager.repository) }
    var release by remember { mutableStateOf<GitHubRelease?>(null) }
    var status by remember { mutableStateOf("填写 GitHub 仓库后可检查公开 Release。") }
    var checking by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("检查更新", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("更新包只接受 GitHub Release 提供 SHA-256 digest 且签名与当前应用一致的 APK。")
        OutlinedTextField(value = repository, onValueChange = { repository = it }, label = { Text("GitHub 仓库（owner/repo）") }, placeholder = { Text("例如 fengluanchui/pandianbao") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            updateManager.repository = repository
            checking = true
            status = "正在检查…"
            scope.launch {
                runCatching { updateManager.checkLatest() }.onSuccess { found ->
                    release = found
                    status = when {
                        found == null -> "该仓库还没有已发布的 Release。"
                        updateManager.isNewer(found) -> "发现新版本 ${found.versionName}。"
                        else -> "当前已是最新版本。"
                    }
                }.onFailure { status = it.message ?: "检查更新失败" }
                checking = false
            }
        }, enabled = repository.isNotBlank() && !checking, modifier = Modifier.fillMaxWidth()) { Text(if (checking) "检查中…" else "检查更新") }
        Text(status, color = if (status.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        release?.let { latest ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("版本 ${latest.versionName}", fontWeight = FontWeight.Bold)
                if (latest.notes.isNotBlank()) Text(latest.notes)
                if (updateManager.isNewer(latest)) Button(onClick = {
                    status = "正在下载并校验…"
                    scope.launch { runCatching { updateManager.downloadAndInstall(latest) }.onSuccess { status = "已交给系统安装器，请确认安装。" }.onFailure { status = it.message ?: "下载安装失败" } }
                }, modifier = Modifier.fillMaxWidth()) { Text("下载并安装更新") }
            } }
        }
        Text("测试提示：同一设备上的升级包必须使用同一签名密钥。正式发布前请配置风乱吹的签名密钥。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HomeScreen(modifier: Modifier, location: LocationEntity?, productCount: Int, total: Long, onInventory: () -> Unit, onProducts: () -> Unit, onScan: () -> Unit) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("本地库存工作台", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(location?.name ?: "正在准备库位", style = MaterialTheme.typography.titleMedium)
                Text("商品数：$productCount")
                Text("库存数量：$total", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text("库存允许为负数；单据录入数量必须为正数。")
            }
        }
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.QrCode2, null); Spacer(Modifier.width(8.dp)); Text("扫码盘点") }
        OutlinedButton(onClick = onInventory, modifier = Modifier.fillMaxWidth()) { Text("浏览库存 / 手动出入库") }
        OutlinedButton(onClick = onProducts, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("登记商品") }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("本阶段已启用", fontWeight = FontWeight.Bold)
                Text("商品、库位、独立库存余额与可追溯的出入库/盘点流水。")
                Spacer(Modifier.height(4.dp))
                Text("相机扫码已接入；Excel 导入导出和跨设备备份恢复将在下一阶段接入。")
            }
        }
    }
}

@Composable
private fun InventoryScreen(modifier: Modifier, location: LocationEntity?, rows: List<InventoryRow>, onRecord: (InventoryRow, DocumentType, Long) -> Unit) {
    var selectedRow by remember { mutableStateOf<InventoryRow?>(null) }
    var actionType by remember { mutableStateOf(DocumentType.STOCKTAKE) }
    Scaffold(modifier = modifier, floatingActionButton = { FloatingActionButton(onClick = { selectedRow = rows.firstOrNull(); actionType = DocumentType.STOCKTAKE }) { Icon(Icons.Default.QrCode2, "选择商品后录入") } }) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp)) {
            Text("${location?.name ?: "未选择库位"} · 库存", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp))
            if (rows.isEmpty()) {
                EmptyState("还没有商品。请先到“商品”页登记，后续扫码会直接进入商品识别。")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rows, key = { it.productId }) { row ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.name, fontWeight = FontWeight.Bold)
                                    Text(row.displayBarcode, style = MaterialTheme.typography.bodySmall)
                                    Text("当前数量：${row.quantity}", color = if (row.quantity < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                }
                                OutlinedButton(onClick = { selectedRow = row; actionType = DocumentType.STOCKTAKE }) { Text("操作") }
                            }
                        }
                    }
                }
            }
        }
    }
    selectedRow?.let { row -> MovementDialog(row, actionType, onDismiss = { selectedRow = null }, onConfirm = { type, quantity -> onRecord(row, type, quantity); selectedRow = null }) }
}

@Composable
private fun MovementDialog(row: InventoryRow, initialType: DocumentType, onDismiss: () -> Unit, onConfirm: (DocumentType, Long) -> Unit) {
    var type by remember(initialType) { mutableStateOf(initialType) }
    var quantity by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前库存：${row.quantity}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DocumentType.entries.forEach { item -> AssistChip(onClick = { type = item }, label = { Text(typeLabel(item)) }) }
                }
                OutlinedTextField(value = quantity, onValueChange = { quantity = it.filter(Char::isDigit) }, singleLine = true, label = { Text(if (type == DocumentType.STOCKTAKE) "实盘数量（正数）" else "录入数量（正数）") })
                if (type == DocumentType.STOCKTAKE) Text("保存后将以实盘数量与当前库存的差额生成调整流水。")
            }
        },
        confirmButton = { Button(onClick = { quantity.toLongOrNull()?.takeIf { it > 0 }?.let { onConfirm(type, it) } }, enabled = quantity.toLongOrNull()?.let { it > 0 } == true) { Text("保存") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ProductsScreen(modifier: Modifier, products: List<ProductEntity>, onAdd: (String, String, String) -> Unit) {
    var showAdd by remember { mutableStateOf(false) }
    Scaffold(modifier = modifier, floatingActionButton = { FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "登记商品") } }) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp)) {
            Text("商品库", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp))
            Text("同条码名称不一致时，录入变体说明后再保存，避免自动覆盖。")
            if (products.isEmpty()) EmptyState("暂无商品。先手动登记商品，后续再导入 Excel 商品库。") else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                items(products, key = { it.id }) { product -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(product.name, fontWeight = FontWeight.Bold); Text(product.displayBarcode, style = MaterialTheme.typography.bodySmall) } } }
            }
        }
    }
    if (showAdd) ProductDialog(onDismiss = { showAdd = false }, onConfirm = { barcode, name, note -> onAdd(barcode, name, note); showAdd = false })
}

@Composable
private fun ProductDialog(initialBarcode: String = "", onDismiss: () -> Unit, onConfirm: (String, String, String) -> Unit) {
    var barcode by remember(initialBarcode) { mutableStateOf(initialBarcode) }; var name by remember { mutableStateOf("") }; var note by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("登记商品") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = barcode, onValueChange = { barcode = it.trim() }, label = { Text("原始条码") }, singleLine = true)
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("商品名称") }, singleLine = true)
        OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("同条码变体说明（可选）") }, singleLine = true)
    } }, confirmButton = { Button(onClick = { onConfirm(barcode, name, note) }, enabled = barcode.isNotBlank() && name.isNotBlank()) { Text("保存") } }, dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun BarcodeProductChooser(products: List<ProductEntity>, onDismiss: () -> Unit, onChoose: (ProductEntity) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("识别到已有条码") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("请选择本次盘点的商品。若名称不一致，请先人工审核后再建立带说明的副本条码。")
            products.forEach { product -> OutlinedButton(onClick = { onChoose(product) }, modifier = Modifier.fillMaxWidth()) { Text("${product.name}\n${product.displayBarcode}") } }
        }
    }, confirmButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun LocationsScreen(modifier: Modifier, locations: List<LocationEntity>, selectedId: String, onSelect: (String) -> Unit, onAdd: (String) -> Unit) {
    var showAdd by remember { mutableStateOf(false) }
    Scaffold(modifier = modifier, floatingActionButton = { FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "新建库位") } }) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp)) {
            Text("库位管理", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp))
            Text("每个库位的商品库存独立保存。")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                items(locations, key = { it.id }) { location -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(location.name, fontWeight = FontWeight.Bold); if (location.id == selectedId) Text("当前库位", color = MaterialTheme.colorScheme.primary) }; OutlinedButton(onClick = { onSelect(location.id) }) { Text(if (location.id == selectedId) "已选择" else "选择") } } } }
            }
        }
    }
    if (showAdd) NameDialog("新建库位", "库位名称", onDismiss = { showAdd = false }) { name -> onAdd(name); showAdd = false }
}

@Composable
private fun NameDialog(title: String, label: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true) }, confirmButton = { Button(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text("保存") } }, dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun EmptyState(message: String) { Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(message) } }
private fun typeLabel(type: DocumentType) = when (type) { DocumentType.STOCKTAKE -> "盘点"; DocumentType.INBOUND -> "入库"; DocumentType.OUTBOUND -> "出库" }
private fun tabIcon(tab: AppTab) = when (tab) { AppTab.HOME -> Icons.Default.Home; AppTab.INVENTORY -> Icons.Default.Inventory2; AppTab.PRODUCTS -> Icons.Default.QrCode2; AppTab.LOCATIONS -> Icons.Default.LocationOn; AppTab.UPDATES -> Icons.Default.Settings }
