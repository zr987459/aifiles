package app.local1st.files.ai.llm

import android.content.Context
import app.local1st.files.ai.LlmClient
import app.local1st.files.ai.LlmException
import app.local1st.files.ai.LlmSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** 桩实现（M5 负责替换）。 */
class DefaultLlmClient(private val context: Context) : LlmClient {
    private val state = MutableStateFlow(LlmSettings())
    override val settings: StateFlow<LlmSettings> = state
    override suspend fun update(settings: LlmSettings) { state.value = settings }
    override suspend fun setApiKey(key: String?) {}
    override fun isReady(): Boolean = false
    override suspend fun chatJson(system: String, user: String, maxTokens: Int): JSONObject = throw LlmException("未实现")
    override suspend fun test(): Result<String> = Result.failure(LlmException("未实现"))
    override fun requestsToday(): Int = 0
    override fun fence(label: String, text: String): String = "<<<$label\n$text\n$label>>>"
    override fun allowed(path: String): Boolean = false
}
