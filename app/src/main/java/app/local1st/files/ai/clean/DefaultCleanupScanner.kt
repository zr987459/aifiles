package app.local1st.files.ai.clean

import android.content.Context
import app.local1st.files.ai.CleanGroup
import app.local1st.files.ai.CleanScanState
import app.local1st.files.ai.CleanupScanner
import app.local1st.files.ai.Plan
import app.local1st.files.ai.PlanKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 桩实现（M3 负责替换）。 */
class DefaultCleanupScanner(private val context: Context) : CleanupScanner {
    override fun scan(): Flow<CleanScanState> = flowOf(CleanScanState(finished = true))
    override suspend fun aiReview(groups: List<CleanGroup>): List<CleanGroup> = groups
    override fun buildPlan(groups: List<CleanGroup>, selectedPaths: Set<String>): Plan =
        Plan("stub", PlanKind.CLEAN, "清理", actions = emptyList())
}
