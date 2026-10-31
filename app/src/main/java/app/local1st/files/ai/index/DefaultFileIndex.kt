package app.local1st.files.ai.index

import android.content.Context
import app.local1st.files.ai.Category
import app.local1st.files.ai.Classification
import app.local1st.files.ai.FileIndex
import app.local1st.files.ai.FileQuery
import app.local1st.files.ai.IndexStats
import app.local1st.files.ai.IndexedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 桩实现（M2 负责替换）。 */
class DefaultFileIndex(private val context: Context) : FileIndex {
    override val stats: StateFlow<IndexStats> = MutableStateFlow(IndexStats())
    override fun startScan(full: Boolean) {}
    override suspend fun query(q: FileQuery): List<IndexedFile> = emptyList()
    override suspend fun get(id: Long): IndexedFile? = null
    override suspend fun getByPath(path: String): IndexedFile? = null
    override suspend fun updateClassification(id: Long, c: Classification) {}
    override suspend fun fullHash(id: Long): String? = null
    override suspend fun onMoved(oldPath: String, newPath: String) {}
    override suspend fun onRemoved(path: String) {}
    override suspend fun onAdded(path: String) {}
    override suspend fun sourceApps(): Map<String, Int> = emptyMap()
    override suspend fun subCategories(category: Category): Map<String, Int> = emptyMap()
    override suspend fun emptyDirs(): List<String> = emptyList()
}
