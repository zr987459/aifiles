package app.local1st.files.ai.organize

import android.content.Context
import app.local1st.files.ai.CommandIntent
import app.local1st.files.ai.FileQuery
import app.local1st.files.ai.IntentAction
import app.local1st.files.ai.OrganizeRule
import app.local1st.files.ai.Organizer
import app.local1st.files.ai.Plan
import app.local1st.files.ai.PlanKind
import app.local1st.files.ai.SmartView

/** 桩实现（M4 负责替换）。 */
class DefaultOrganizer(private val context: Context) : Organizer {
    override suspend fun smartViews(): List<SmartView> = emptyList()
    override suspend fun refineWithAi(limit: Int): Int = 0
    override suspend fun proposeOrganize(scopePath: String?, instruction: String?): Plan =
        Plan("stub", PlanKind.ORGANIZE, "整理", instruction, actions = emptyList())
    override suspend fun parseCommand(text: String): CommandIntent =
        CommandIntent(IntentAction.FIND, FileQuery(text = text), listOf("搜索：$text"), raw = text)
    override suspend fun recordConfirmed(plan: Plan): OrganizeRule? = null
    override suspend fun rules(): List<OrganizeRule> = emptyList()
    override suspend fun saveRule(rule: OrganizeRule) {}
    override suspend fun deleteRule(id: String) {}
    override suspend fun runRules(): Plan? = null
}
