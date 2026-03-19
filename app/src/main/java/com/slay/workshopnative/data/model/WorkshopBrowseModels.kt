package com.slay.workshopnative.data.model

data class WorkshopDateRangeFilter(
    val startEpochSeconds: Long = 0L,
    val endEpochSeconds: Long = 0L,
) {
    val isActive: Boolean
        get() = startEpochSeconds > 0L || endEpochSeconds > 0L

    fun normalized(): WorkshopDateRangeFilter {
        val normalizedStart = startEpochSeconds.coerceAtLeast(0L)
        val normalizedEnd = endEpochSeconds.coerceAtLeast(0L)
        return if (normalizedStart > 0L && normalizedEnd > 0L && normalizedStart > normalizedEnd) {
            copy(
                startEpochSeconds = normalizedEnd,
                endEpochSeconds = normalizedStart,
            )
        } else {
            copy(
                startEpochSeconds = normalizedStart,
                endEpochSeconds = normalizedEnd,
            )
        }
    }
}

data class WorkshopBrowseQuery(
    val sectionKey: String = SECTION_ITEMS,
    val sortKey: String = SORT_TREND,
    val periodDays: Int = 7,
    val searchText: String = "",
    val requiredTags: Set<String> = emptySet(),
    val excludedTags: Set<String> = emptySet(),
    val showIncompatible: Boolean = false,
    val createdDateRange: WorkshopDateRangeFilter = WorkshopDateRangeFilter(),
    val updatedDateRange: WorkshopDateRangeFilter = WorkshopDateRangeFilter(),
    val page: Int = 1,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    companion object {
        const val DEFAULT_PAGE_SIZE = 9
        val PAGE_SIZE_OPTIONS = listOf(9, 18, 30)
        const val SECTION_ITEMS = "readytouseitems"
        const val SECTION_MY_SUBSCRIPTIONS = "mysubscriptions"
        const val SORT_TREND = "trend"

        fun normalizePageSize(pageSize: Int): Int {
            return if (pageSize in PAGE_SIZE_OPTIONS) pageSize else DEFAULT_PAGE_SIZE
        }
    }
}

data class WorkshopBrowsePage(
    val items: List<WorkshopItem>,
    val totalCount: Int,
    val page: Int,
    val hasMore: Boolean,
    val sectionOptions: List<WorkshopBrowseSectionOption>,
    val sortOptions: List<WorkshopBrowseSortOption>,
    val periodOptions: List<WorkshopBrowsePeriodOption>,
    val tagGroups: List<WorkshopBrowseTagGroup>,
    val supportsIncompatibleFilter: Boolean,
)

data class WorkshopBrowseSectionOption(
    val key: String,
    val label: String,
)

data class WorkshopBrowseSortOption(
    val key: String,
    val label: String,
    val supportsPeriod: Boolean,
)

data class WorkshopBrowsePeriodOption(
    val days: Int,
    val label: String,
)

enum class WorkshopBrowseTagGroupSelectionMode {
    IncludeExclude,
    SingleSelect,
}

data class WorkshopBrowseTagGroup(
    val label: String,
    val tags: List<WorkshopBrowseTagOption>,
    val selectionMode: WorkshopBrowseTagGroupSelectionMode = WorkshopBrowseTagGroupSelectionMode.IncludeExclude,
)

data class WorkshopBrowseTagOption(
    val value: String,
    val label: String,
)
