package com.qylent.manhwatakip

import org.junit.Assert.*
import org.junit.Test

class AiShareSnapshotTest {
    private val row = Series(title = "Snapshot fixture", lastChapter = "1", createdAt = 1, updatedAt = 1)
    private fun source(records: List<Series> = listOf(row), categories: List<CategoryDefinition> = emptyList(), history: List<ProgressChange> = emptyList()) =
        ChatGptActivity.Source(records, categories, history)
    private fun generated(): ChatGptActivity.State = ChatGptActivity.State().apply {
        acceptOutput(source(), mapOf("context.json" to "original".toByteArray(), "profile.md" to byteArrayOf()))
        pendingOutput = "original"; pendingZip = byteArrayOf(1)
    }

    @Test fun unchangedSourceReturnKeepsExactSnapshotAndPendingFile() {
        val state = generated(); val files = state.files
        assertFalse(state.observeSource(source()))
        assertSame(files, state.files); assertEquals("original", state.output)
        assertEquals("original", state.pendingOutput); assertNotNull(state.pendingZip)
    }

    @Test fun approvedImportImmediatelyInvalidatesEveryOldOutputPath() {
        val state = generated()
        state.importApplied(1)
        assertCleared(state); assertTrue(state.message.contains("1 seri"))
        state.acceptOutput(source(listOf(row.copy(lastChapter = "2"))), mapOf("context.json" to "updated".toByteArray()))
        assertEquals("updated", state.output)
    }

    @Test fun changedRecordAdditionDeletionCategoryOrHistoryInvalidatesSnapshot() {
        val changed = listOf(source(listOf(row.copy(lastChapter = "2"))), source(listOf(row, row.copy(id = "new", title = "New"))), source(emptyList()),
            source(categories = listOf(CategoryDefinition("category", "blue", 0))),
            source(history = listOf(ProgressChange("change", row.id, "1", "2", 2, null))))
        changed.forEach { next -> val state = generated(); assertTrue(state.observeSource(next)); assertCleared(state) }
    }

    private fun assertCleared(state: ChatGptActivity.State) {
        assertNull(state.output); assertNull(state.files); assertNull(state.pendingOutput); assertNull(state.pendingZip)
    }
}
