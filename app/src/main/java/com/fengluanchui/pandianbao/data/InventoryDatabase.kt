package com.fengluanchui.pandianbao.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import java.util.UUID

enum class DocumentType { STOCKTAKE, INBOUND, OUTBOUND }

@Entity(
    tableName = "products",
    indices = [Index(value = ["rawBarcode", "variantNote"], unique = true)]
)
data class ProductEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val rawBarcode: String,
    val variantNote: String = "",
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val displayBarcode: String get() = if (variantNote.isBlank()) rawBarcode else "$rawBarcode（$variantNote）"
}

@Entity(tableName = "locations", indices = [Index(value = ["name"], unique = true)])
data class LocationEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "stock_balances", primaryKeys = ["productId", "locationId"])
data class StockBalanceEntity(
    val productId: String,
    val locationId: String,
    val quantity: Long,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "stock_documents", indices = [Index("locationId")])
data class StockDocumentEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val type: DocumentType,
    val title: String,
    val locationId: String,
    val isCompleted: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "stock_movements", indices = [Index("productId"), Index("locationId"), Index("documentId")])
data class StockMovementEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val productId: String,
    val locationId: String,
    val documentId: String,
    val delta: Long,
    val note: String,
    val occurredAt: Long = System.currentTimeMillis()
)

/** A quick stocktake is a frozen snapshot, never a live view of the product table. */
@Entity(tableName = "stocktake_tasks", indices = [Index("isCompleted"), Index("createdAt")])
data class StocktakeTaskEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

@Entity(tableName = "stocktake_lines", primaryKeys = ["taskId", "productId", "locationId"])
data class StocktakeLineEntity(
    val taskId: String,
    val productId: String,
    val locationId: String,
    val locationName: String,
    val barcode: String,
    val productName: String,
    val quantity: Long,
    val scannedAt: Long = System.currentTimeMillis()
)

data class StocktakeTaskSummary(
    val id: String,
    val title: String,
    val isCompleted: Boolean,
    val createdAt: Long,
    val completedAt: Long?,
    val varietyCount: Long,
    val totalQuantity: Long
)

data class StocktakeLineRow(
    val taskId: String,
    val productId: String,
    val locationId: String,
    val locationName: String,
    val barcode: String,
    val productName: String,
    val quantity: Long,
    val scannedAt: Long
)

data class InventoryRow(
    val productId: String,
    val rawBarcode: String,
    val variantNote: String,
    val name: String,
    val quantity: Long
) {
    val displayBarcode: String get() = if (variantNote.isBlank()) rawBarcode else "$rawBarcode（$variantNote）"
}

class DbConverters {
    @TypeConverter fun fromDocumentType(value: DocumentType): String = value.name
    @TypeConverter fun toDocumentType(value: String): DocumentType = DocumentType.valueOf(value)
}

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE rawBarcode = :barcode ORDER BY variantNote")
    suspend fun byRawBarcode(barcode: String): List<ProductEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(product: ProductEntity)

    @Query("UPDATE products SET name = :name, updatedAt = :updatedAt WHERE id = :productId")
    suspend fun rename(productId: String, name: String, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT p.id AS productId, p.rawBarcode AS rawBarcode, p.variantNote AS variantNote, p.name AS name, COALESCE(b.quantity, 0) AS quantity FROM products p LEFT JOIN stock_balances b ON p.id = b.productId AND b.locationId = :locationId ORDER BY p.name COLLATE NOCASE")
    fun observeInventory(locationId: String): Flow<List<InventoryRow>>
}

@Dao
interface LocationDao {
    @Query("SELECT * FROM locations ORDER BY createdAt")
    fun observeAll(): Flow<List<LocationEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(location: LocationEntity)
}

@Dao
interface InventoryDao {
    @Query("SELECT * FROM stock_balances WHERE productId = :productId AND locationId = :locationId LIMIT 1")
    suspend fun balance(productId: String, locationId: String): StockBalanceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBalance(balance: StockBalanceEntity)

    @Insert
    suspend fun insertDocument(document: StockDocumentEntity)

    @Insert
    suspend fun insertMovement(movement: StockMovementEntity)

    @Query("SELECT COALESCE(SUM(quantity), 0) FROM stock_balances WHERE locationId = :locationId")
    fun observeLocationTotal(locationId: String): Flow<Long>
}

@Dao
interface StocktakeDao {
    @Query("SELECT t.id, t.title, t.isCompleted, t.createdAt, t.completedAt, COUNT(l.productId) AS varietyCount, COALESCE(SUM(l.quantity), 0) AS totalQuantity FROM stocktake_tasks t LEFT JOIN stocktake_lines l ON t.id = l.taskId GROUP BY t.id ORDER BY t.isCompleted ASC, t.createdAt DESC")
    fun observeTasks(): Flow<List<StocktakeTaskSummary>>

    @Query("SELECT * FROM stocktake_tasks WHERE id = :taskId LIMIT 1")
    suspend fun task(taskId: String): StocktakeTaskEntity?

    @Query("SELECT * FROM stocktake_lines WHERE taskId = :taskId ORDER BY scannedAt DESC")
    fun observeLines(taskId: String): Flow<List<StocktakeLineRow>>

    @Query("SELECT * FROM stocktake_lines WHERE taskId = :taskId AND productId = :productId AND locationId = :locationId LIMIT 1")
    suspend fun line(taskId: String, productId: String, locationId: String): StocktakeLineEntity?

    @Insert
    suspend fun insertTask(task: StocktakeTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLine(line: StocktakeLineEntity)

    @Query("UPDATE stocktake_lines SET quantity = :quantity, scannedAt = :updatedAt WHERE taskId = :taskId AND productId = :productId AND locationId = :locationId")
    suspend fun updateQuantity(taskId: String, productId: String, locationId: String, quantity: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE stocktake_tasks SET isCompleted = 1, completedAt = :completedAt WHERE id = :taskId AND isCompleted = 0")
    suspend fun complete(taskId: String, completedAt: Long = System.currentTimeMillis()): Int

    @Query("SELECT * FROM stocktake_lines WHERE taskId = :taskId")
    suspend fun lines(taskId: String): List<StocktakeLineEntity>
}

@Database(
    entities = [ProductEntity::class, LocationEntity::class, StockBalanceEntity::class, StockDocumentEntity::class, StockMovementEntity::class, StocktakeTaskEntity::class, StocktakeLineEntity::class],
    version = 2,
    exportSchema = true
)
@TypeConverters(DbConverters::class)
abstract class InventoryDatabase : RoomDatabase() {
    abstract fun products(): ProductDao
    abstract fun locations(): LocationDao
    abstract fun inventory(): InventoryDao
    abstract fun stocktakes(): StocktakeDao

    companion object {
        fun create(context: Context): InventoryDatabase = Room.databaseBuilder(
            context.applicationContext,
            InventoryDatabase::class.java,
            "pandianbao.db"
        ).addMigrations(object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS stocktake_tasks (id TEXT NOT NULL, title TEXT NOT NULL, isCompleted INTEGER NOT NULL, createdAt INTEGER NOT NULL, completedAt INTEGER, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stocktake_tasks_isCompleted ON stocktake_tasks(isCompleted)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stocktake_tasks_createdAt ON stocktake_tasks(createdAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS stocktake_lines (taskId TEXT NOT NULL, productId TEXT NOT NULL, locationId TEXT NOT NULL, locationName TEXT NOT NULL, barcode TEXT NOT NULL, productName TEXT NOT NULL, quantity INTEGER NOT NULL, scannedAt INTEGER NOT NULL, PRIMARY KEY(taskId, productId, locationId))")
            }
        }).build()
    }
}

class InventoryRepository(private val database: InventoryDatabase) {
    val products: Flow<List<ProductEntity>> = database.products().observeAll()
    val locations: Flow<List<LocationEntity>> = database.locations().observeAll()
    val stocktakeTasks: Flow<List<StocktakeTaskSummary>> = database.stocktakes().observeTasks()

    suspend fun productsWithBarcode(barcode: String): List<ProductEntity> = database.products().byRawBarcode(barcode.trim())

    fun inventory(locationId: String): Flow<List<InventoryRow>> = database.products().observeInventory(locationId)
    fun locationTotal(locationId: String): Flow<Long> = database.inventory().observeLocationTotal(locationId)
    fun stocktakeLines(taskId: String): Flow<List<StocktakeLineRow>> = database.stocktakes().observeLines(taskId)

    suspend fun addLocation(name: String) {
        require(name.isNotBlank()) { "库位名称不能为空" }
        database.locations().insert(LocationEntity(name = name.trim()))
    }

    suspend fun addProduct(barcode: String, name: String, variantNote: String = "") {
        require(barcode.isNotBlank()) { "条码不能为空" }
        require(name.isNotBlank()) { "商品名称不能为空" }
        database.products().insert(
            ProductEntity(rawBarcode = barcode.trim(), name = name.trim(), variantNote = variantNote.trim())
        )
    }

    suspend fun renameProduct(productId: String, name: String) {
        require(name.isNotBlank()) { "商品名称不能为空" }
        database.products().rename(productId, name.trim())
    }

    suspend fun createStocktake(title: String): String {
        val normalized = title.trim().ifBlank { "盘点任务" }
        val task = StocktakeTaskEntity(title = normalized)
        database.stocktakes().insertTask(task)
        return task.id
    }

    suspend fun countProduct(taskId: String, product: ProductEntity, location: LocationEntity) = database.withTransaction {
        val task = requireNotNull(database.stocktakes().task(taskId)) { "盘点任务不存在" }
        check(!task.isCompleted) { "已完成的盘点任务不能再修改" }
        val old = database.stocktakes().line(taskId, product.id, location.id)
        database.stocktakes().upsertLine(
            StocktakeLineEntity(
                taskId = taskId,
                productId = product.id,
                locationId = location.id,
                locationName = location.name,
                barcode = product.displayBarcode,
                productName = product.name,
                quantity = (old?.quantity ?: 0L) + 1L
            )
        )
    }

    suspend fun addProductAndCount(taskId: String, barcode: String, name: String, variantNote: String, location: LocationEntity) = database.withTransaction {
        require(barcode.isNotBlank()) { "条码不能为空" }
        require(name.isNotBlank()) { "商品名称不能为空" }
        val task = requireNotNull(database.stocktakes().task(taskId)) { "盘点任务不存在" }
        check(!task.isCompleted) { "已完成的盘点任务不能再修改" }
        val product = ProductEntity(rawBarcode = barcode.trim(), name = name.trim(), variantNote = variantNote.trim())
        database.products().insert(product)
        database.stocktakes().upsertLine(
            StocktakeLineEntity(taskId, product.id, location.id, location.name, product.displayBarcode, product.name, 1L)
        )
    }

    suspend fun setStocktakeLineQuantity(taskId: String, line: StocktakeLineRow, quantity: Long) = database.withTransaction {
        require(quantity >= 0) { "数量不能小于 0" }
        val task = requireNotNull(database.stocktakes().task(taskId)) { "盘点任务不存在" }
        check(!task.isCompleted) { "已完成的盘点任务不能再修改" }
        database.stocktakes().updateQuantity(taskId, line.productId, line.locationId, quantity)
    }

    suspend fun completeStocktake(taskId: String) = database.withTransaction {
        val task = requireNotNull(database.stocktakes().task(taskId)) { "盘点任务不存在" }
        check(!task.isCompleted) { "该盘点任务已经完成" }
        val lines = database.stocktakes().lines(taskId)
        require(lines.isNotEmpty()) { "至少录入一条盘点记录后才能完成" }
        val documents = mutableMapOf<String, StockDocumentEntity>()
        for (line in lines) {
            val document = documents.getOrPut(line.locationId) {
                StockDocumentEntity(type = DocumentType.STOCKTAKE, title = "盘点：${task.title}", locationId = line.locationId)
                    .also { database.inventory().insertDocument(it) }
            }
            val before = database.inventory().balance(line.productId, line.locationId)?.quantity ?: 0L
            val delta = line.quantity - before
            database.inventory().insertMovement(
                StockMovementEntity(
                    productId = line.productId,
                    locationId = line.locationId,
                    documentId = document.id,
                    delta = delta,
                    note = "实盘数量：${line.quantity}"
                )
            )
            database.inventory().upsertBalance(StockBalanceEntity(line.productId, line.locationId, line.quantity))
        }
        check(database.stocktakes().complete(taskId) == 1) { "盘点任务状态已变化，请刷新后重试" }
    }

    suspend fun recordMovement(
        product: InventoryRow,
        locationId: String,
        type: DocumentType,
        enteredQuantity: Long
    ) = database.withTransaction {
        require(enteredQuantity > 0) { "数量必须为正数" }
        val before = database.inventory().balance(product.productId, locationId)?.quantity ?: 0L
        val delta = when (type) {
            DocumentType.INBOUND -> enteredQuantity
            DocumentType.OUTBOUND -> -enteredQuantity
            DocumentType.STOCKTAKE -> enteredQuantity - before
        }
        val document = StockDocumentEntity(
            type = type,
            title = when (type) {
                DocumentType.INBOUND -> "手动入库"
                DocumentType.OUTBOUND -> "手动出库"
                DocumentType.STOCKTAKE -> "手动盘点"
            },
            locationId = locationId
        )
        database.inventory().insertDocument(document)
        database.inventory().insertMovement(
            StockMovementEntity(
                productId = product.productId,
                locationId = locationId,
                documentId = document.id,
                delta = delta,
                note = if (type == DocumentType.STOCKTAKE) "实盘数量：$enteredQuantity" else document.title
            )
        )
        database.inventory().upsertBalance(
            StockBalanceEntity(productId = product.productId, locationId = locationId, quantity = before + delta)
        )
    }
}
