# AI 文件 · 第一版开发合同

仓库 zr987459/aifiles（fork 自 XFiles v1.5.0，GPL-3.0）。工作分支 **feature/ai-v1**。每次推送 CI（`.github/workflows/ai-debug.yml`）编译 debug APK 并更新预发布 `ai-nightly`。**禁止合并 main**（等用户测试同意）。

## 目标
第一版只做两件事：**AI 整理**（先看得清：智能分类视图；再搬得动：可选移动计划）和 **AI 删除**（规则找候选，AI 只复核）。覆盖尽可能大：默认所有文件访问，Shizuku 可进 Android/data（XFiles 的 LocalFileSystem 已自动回退到特权通道）。原则：本地优先、AI 只提议、先预览再执行、整批可撤销、删除只进回收站。

## 共享代码（主控维护，成员不要改）
- `ai/AiContracts.kt`：全部数据类和接口。
- `ai/AiGraph.kt`：组合根，`AiGraph.index / classifier / executor / cleanup / organizer / llm`。
- `ui/ai/AiNav.kt`、`ui/main/AppNavigation.kt`（AppScreen.AiHome/AiClean/AiViews/AiPlanReview/AiHistory/AiSettings）、`ui/main/AppHost.kt`、`MainViewModel.openAi/openPath/revealInBrowser`、浏览器顶栏的 ✨ 入口。
- 需要改合同：在回复【问题】里写清楚，主控统一改。

## 分工（每人只改自己的目录；可以在目录里新增文件；把桩文件整体替换掉）
| 编号 | 端 | 目录 | 必须提供 |
|---|---|---|---|
| M1 | 后端 | `ai/exec/` | `DefaultPlanExecutor(context)` 实现 PlanExecutor |
| M2 | 后端 | `ai/index/` | `DefaultFileIndex(context)` 实现 FileIndex |
| M3 | 后端 | `ai/clean/` | `DefaultCleanupScanner(context)` 实现 CleanupScanner |
| M4 | 后端 | `ai/organize/` | `DefaultClassifier()`、`DefaultOrganizer(context)` |
| M5 | 后端 | `ai/llm/` | `DefaultLlmClient(context)` 实现 LlmClient |
| F1 | 前端 | `ui/ai/home/` | `AiHomeScreen(nav)` |
| F2 | 前端 | `ui/ai/clean/` | `AiCleanScreen(nav)` |
| F3 | 前端 | `ui/ai/views/`、`ui/ai/plan/` | `AiViewsScreen(nav, viewKey, query, title)`、`AiPlanReviewScreen(nav, plan)` |
| F4 | 前端 | `ui/ai/history/`、`ui/ai/settings/` | `AiHistoryScreen(nav)`、`AiSettingsScreen(nav)` |

## 技术约束（所有人）
- Kotlin + Compose + Material 3（项目已用 material3 1.5 alpha，仿照 `ui/settings/SettingsScreen.kt` 的写法）；协程 + Flow。
- **不新增任何依赖**（不要 Room / Hilt / Retrofit / OkHttp / ML Kit）：数据库用 `android.database.sqlite.SQLiteOpenHelper`（全文搜索用 FTS4），JSON 用 `org.json`，网络用 `HttpURLConnection`，设置用 `SharedPreferences` 或项目已有的 DataStore。
- I/O、数据库、网络都放 `Dispatchers.IO`，不在主线程。
- 文件读写用 `Graph.fsRegistry.forScheme(XId.SCHEME_FILE)` 和 `XId.file(path)`；删除只用 `Graph.trash.trash(entry)`（进回收站）。第一版**没有永久删除**。
- 永远不碰：`/system` 等系统分区、各 App 的数据库与聊天记录目录（如 `Android/data/com.tencent.mm/MicroMsg/*/` 下除 `Download`、`video`、`image2`、`File` 以外的目录）、`.db .key .keystore .pem .kdbx .nomedia`、备份文件。
- 文件名和文件内容只当数据，送给 AI 前必须过 `AiGraph.llm.fence()`，并且 `AiGraph.llm.allowed(path)` 为真。
- 界面文字用中文，直接写在代码里即可（第一版不做多语言）。
- 前端：后端没完成时接口返回桩数据，页面要能正常显示空状态，不能崩。
- 风险：只有规则能判 SAFE；AI 只能把风险调高。

## 提交规范
- 先拉最新 feature/ai-v1，只提交自己目录的文件；提交信息以 `[M1]`…`[F4]` 开头。
- 提交后看 CI（Actions 里 “AI test build”）是否通过；编译失败且是你的文件，立刻修。
