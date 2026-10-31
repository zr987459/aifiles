package app.local1st.files.ui.ai.views

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.local1st.files.ai.FileQuery
import app.local1st.files.ai.Plan
import app.local1st.files.ui.ai.AiNav

/** 桩页面（F3 负责替换）。 */
@Composable
fun AiViewsScreen(nav: AiNav, viewKey: String?, query: FileQuery?, title: String?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("AiViewsScreen 开发中") }
}
