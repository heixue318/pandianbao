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

@Database(
    entities = [ProductEntity::class, LocationEntity::class, StockBalanceEntity::class, StockDocumentEntity::class, StockMovementEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(DbConverters::class)
abstract class InventoryDatabase : RoomDatabase() {
    abstract fun products(): ProductDao
    abstract fun locations(): LocationDao
    abstract fun inventory(): InventoryDao

    companion object {
        fun create(context: Context): InventoryDatabase = Room.databaseBuilder(
            context.applicationContext,
            InventoryDatabase::class.java,
            "pandianbao.db"
        ).build()
    }
}

class InventoryRepository(private val database: InventoryDatabase) {
    val products: Flow<List<ProductEntity>> = database.products().observeAll()
    val locations: Flow<List<LocationEntity>> = database.locations().observeAll()

    suspend fun productsWithBarcode(barcode: String): List<ProductEntity> = database.products().byRawBarcode(barcode.trim())

    fun inventory(locationId: String): Flow<List<InventoryRow>> = database.products().observeInventory(locationId)
    fun locationTotal(locationId: String): Flow<Long> = database.inventory().observeLocationTotal(locationId)

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
