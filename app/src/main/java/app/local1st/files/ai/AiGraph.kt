package app.local1st.files.ai

import app.local1st.files.ai.clean.DefaultCleanupScanner
import app.local1st.files.ai.exec.DefaultPlanExecutor
import app.local1st.files.ai.index.DefaultFileIndex
import app.local1st.files.ai.llm.DefaultLlmClient
import app.local1st.files.ai.organize.DefaultClassifier
import app.local1st.files.ai.organize.DefaultOrganizer
import app.local1st.files.di.Graph

/** AI 模块的组合根（主控维护）。各实现类名固定，由对应成员实现。 */
object AiGraph {
    val llm: LlmClient by lazy { DefaultLlmClient(Graph.appContext) }
    val classifier: Classifier by lazy { DefaultClassifier() }
    val index: FileIndex by lazy { DefaultFileIndex(Graph.appContext) }
    val executor: PlanExecutor by lazy { DefaultPlanExecutor(Graph.appContext) }
    val cleanup: CleanupScanner by lazy { DefaultCleanupScanner(Graph.appContext) }
    val organizer: Organizer by lazy { DefaultOrganizer(Graph.appContext) }
}
