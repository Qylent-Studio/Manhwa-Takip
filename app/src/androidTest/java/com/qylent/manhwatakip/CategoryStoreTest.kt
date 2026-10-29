package com.qylent.manhwatakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test

class CategoryStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun schemaFourMigrationPreservesRecordsDecisionsDeletedIdsAndAlphabeticalCategories() {
        val name = "category-upgrade-${System.nanoTime()}.db"
        val record = Series(title = "Korunan kayıt", notes = "Not", categories = listOf("Zeta", "Alfa"))
        val deleted = Series(title = "Silinen")
        try {
            LibraryStore(context, name).use { it.save(record); it.save(deleted); it.delete(deleted); it.recordBridgeRejection("keep") }
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use {
                it.execSQL("ALTER TABLE categories RENAME TO fixture_categories")
                it.execSQL("CREATE TABLE categories (name TEXT NOT NULL, folded TEXT PRIMARY KEY NOT NULL)")
                it.execSQL("INSERT INTO categories SELECT name, folded FROM fixture_categories")
                it.execSQL("DROP TABLE fixture_categories"); it.version = 4
            }
            LibraryStore(context, name).use {
                assertEquals(record, it.get(record.id))
                assertEquals(mapOf("keep" to "rejected"), it.bridgeDecisions())
                assertThrows(IllegalArgumentException::class.java) { it.save(deleted) }
                assertEquals(listOf(CategoryDefinition("Alfa", position = 0), CategoryDefinition("Zeta", position = 1)), it.categoryDefinitions())
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun colorOrderRenameAndRemovalSurviveReopenAndPreserveRecordContent() {
        val name = "category-order-${System.nanoTime()}.db"
        val record = Series(title = "Seri", notes = "Korunacak", categories = listOf("Bir"))
        try {
            LibraryStore(context, name).use {
                it.addCategory("Bir", "blue"); it.addCategory("İki", "rose"); it.save(record)
                it.changeCategory("Bir", "Bir", "purple")
                assertEquals(record, it.get(record.id))
                it.moveCategory("İki", -1)
                it.changeCategory("Bir", "Yeni")
            }
            LibraryStore(context, name).use {
                assertEquals(listOf(CategoryDefinition("İki", "rose", 0), CategoryDefinition("Yeni", "purple", 1)), it.categoryDefinitions())
                val renamed = it.get(record.id)!!
                assertEquals(listOf("Yeni"), renamed.categories); assertEquals(record.notes, renamed.notes)
                assertThrows(IllegalArgumentException::class.java) { it.changeCategory("Yeni", "İKİ") }
                assertEquals(renamed, it.get(record.id))
                it.changeCategory("Yeni", null)
                assertEquals(emptyList<String>(), it.get(record.id)!!.categories)
                assertEquals(record.notes, it.get(record.id)!!.notes)
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun importPreservesExistingStylesAndAppendsUnusedCategoriesInBackupOrder() {
        val name = "category-import-${System.nanoTime()}.db"
        val record = Series(title = "Kayıt", categories = listOf("Mevcut"), notes = "Korunacak")
        try {
            LibraryStore(context, name).use {
                it.addCategory("Mevcut", "purple"); it.save(record)
                val definitions = listOf(CategoryDefinition("Son", "gray", 5), CategoryDefinition("Mevcut", "blue", 0), CategoryDefinition("Boş", "rose", 2))
                val backup = BackupCodec.decodeLibrary(BackupCodec.encodeLibrary(listOf(record.copy(notes = "Eski")), definitions))
                assertEquals(ImportResult(0, 1), it.importMissing(backup.records, backup.categories))
                assertEquals(record, it.get(record.id))
                assertEquals(listOf(CategoryDefinition("Mevcut", "purple", 0), CategoryDefinition("Boş", "rose", 1), CategoryDefinition("Son", "gray", 2)), it.categoryDefinitions())
                val before = it.categoryDefinitions()
                assertThrows(IllegalArgumentException::class.java) { it.importMissing(emptyList(), listOf(CategoryDefinition("Geçerli", "green", 0), CategoryDefinition("Bozuk", "unknown", 1))) }
                assertEquals(before, it.categoryDefinitions())
            }
        } finally { context.deleteDatabase(name) }
    }
}
