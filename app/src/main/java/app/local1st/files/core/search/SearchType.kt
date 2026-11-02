package app.local1st.files.core.search

import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.util.FileCategory
import app.local1st.files.core.util.FileTypes

/** File kinds the search screen can filter by. */
enum class SearchType {
    ALL,
    FOLDER,
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    ARCHIVE,
    APK,
    ;

    fun matches(entry: XEntry): Boolean {
        if (this == ALL) return true
        if (this == FOLDER) return entry.isDir
        if (entry.isDir) return false
        val category = runCatching {
            FileTypes.categoryOf(entry.name, entry.mime ?: FileTypes.mimeOf(entry.name))
        }.getOrDefault(FileCategory.GENERIC)
        return when (this) {
            IMAGE -> category == FileCategory.IMAGE
            VIDEO -> category == FileCategory.VIDEO
            AUDIO -> category == FileCategory.AUDIO
            DOCUMENT -> category == FileCategory.PDF ||
                category == FileCategory.TEXT ||
                extensionOf(entry.name) in OFFICE_EXTENSIONS
            ARCHIVE -> category == FileCategory.ARCHIVE ||
                (entry.kind == EntryKind.ARCHIVE && category != FileCategory.APK)
            APK -> category == FileCategory.APK
            ALL, FOLDER -> true
        }
    }

    private companion object {
        val OFFICE_EXTENSIONS = setOf(
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf",
            "wps", "et", "dps", "epub", "mobi", "pages", "numbers", "key",
        )

        fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()
    }
}
