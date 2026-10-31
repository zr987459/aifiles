package app.local1st.files.ui.ai

import app.local1st.files.ui.main.AppScreen

/** AI 页面之间的导航（主控维护）。 */
class AiNav(
    /** 打开另一个页面，如 AppScreen.AiClean / AppScreen.AiPlanReview(plan)。 */
    val open: (AppScreen) -> Unit,
    /** 返回上一页。 */
    val back: () -> Unit,
    /** 用查看器打开文件（绝对路径）。 */
    val openFile: (String) -> Unit,
    /** 回到浏览器并定位到该文件。 */
    val reveal: (String) -> Unit,
)
