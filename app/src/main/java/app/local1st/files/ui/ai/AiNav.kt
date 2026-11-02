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
    /** 打开列表里第 index 个文件；图片/视频可在同列表的同类文件间左右滑动。 */
    val openFiles: (List<String>, Int) -> Unit = { paths, index -> paths.getOrNull(index)?.let(openFile) },
    /** 回到 AI 首页（退回到已有的首页，不会再叠一层）。 */
    val home: () -> Unit = { open(AppScreen.AiHome) },
    /** 打开文件浏览器（双栏），关闭上面所有 AI 页面。底部导航“文件”用它。 */
    val browse: () -> Unit = back,
)
