package app.local1st.files.ai.organize

import app.local1st.files.ai.Category
import app.local1st.files.ai.Classification
import app.local1st.files.ai.Classifier

/** 桩实现（M4 负责替换）。 */
class DefaultClassifier : Classifier {
    override fun classifyFast(path: String, name: String, ext: String, mime: String?, size: Long): Classification =
        Classification(Category.OTHER)
    override fun subCategoryZh(id: String): String = id
    override fun sourceAppZh(id: String): String = id
}
