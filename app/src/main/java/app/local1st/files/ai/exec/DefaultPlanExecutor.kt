package app.local1st.files.ai.exec

import android.content.Context
import app.local1st.files.ai.ExecProgress
import app.local1st.files.ai.Plan
import app.local1st.files.ai.PlanAction
import app.local1st.files.ai.PlanExecutor
import app.local1st.files.ai.PlanRecord
import app.local1st.files.ai.ValidationIssue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 桩实现（M1 负责替换）。 */
class DefaultPlanExecutor(private val context: Context) : PlanExecutor {
    override suspend fun validate(plan: Plan): Pair<Plan, List<ValidationIssue>> = plan to emptyList()
    override fun execute(plan: Plan): Flow<ExecProgress> =
        flowOf(ExecProgress(plan.planId, 0, 0, finished = true, error = "未实现"))
    override fun undo(planId: String): Flow<ExecProgress> =
        flowOf(ExecProgress(planId, 0, 0, finished = true, error = "未实现"))
    override suspend fun interrupted(): List<PlanRecord> = emptyList()
    override fun resume(planId: String): Flow<ExecProgress> = undo(planId)
    override suspend fun history(limit: Int): List<PlanRecord> = emptyList()
    override suspend fun actionsOf(planId: String): List<PlanAction> = emptyList()
}
