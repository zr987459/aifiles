package app.local1st.files.ui.ai.assistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.local1st.files.ui.ai.AiNav

/** 桩页面（AI1 负责替换）。 */
@Composable
fun AiAssistantScreen(nav: AiNav, initial: String?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("AI 助手开发中") }
}
