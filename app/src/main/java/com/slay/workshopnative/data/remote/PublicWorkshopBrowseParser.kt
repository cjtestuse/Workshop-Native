package com.slay.workshopnative.data.remote

import com.slay.workshopnative.data.model.WorkshopBrowsePeriodOption
import com.slay.workshopnative.data.model.WorkshopBrowseQuery
import com.slay.workshopnative.data.model.WorkshopBrowseSectionOption
import com.slay.workshopnative.data.model.WorkshopBrowseSortOption
import com.slay.workshopnative.data.model.WorkshopBrowseTagGroup
import com.slay.workshopnative.data.model.WorkshopBrowseTagGroupSelectionMode
import com.slay.workshopnative.data.model.WorkshopBrowseTagOption
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

internal data class PublicWorkshopBrowseItemSkeleton(
    val publishedFileId: Long,
    val title: String = "",
    val previewUrl: String? = null,
    val authorName: String = "",
)

internal data class PublicWorkshopBrowseSkeleton(
    val items: List<PublicWorkshopBrowseItemSkeleton>,
    val totalCount: Int?,
    val maxPage: Int,
    val sectionOptions: List<WorkshopBrowseSectionOption>,
    val sortOptions: List<WorkshopBrowseSortOption>,
    val periodOptions: List<WorkshopBrowsePeriodOption>,
    val tagGroups: List<WorkshopBrowseTagGroup>,
    val supportsIncompatibleFilter: Boolean,
    val isExplicitlyEmpty: Boolean,
)

internal object PublicWorkshopBrowseParser {
    private data class SsrBrowsePayload(
        val items: List<PublicWorkshopBrowseItemSkeleton> = emptyList(),
        val totalCount: Int? = null,
        val maxPage: Int? = null,
        val tagGroups: List<WorkshopBrowseTagGroup> = emptyList(),
        val supportsIncompatibleFilter: Boolean? = null,
        val isExplicitlyEmpty: Boolean = false,
    )

    private data class SsrBrowseState(
        val items: List<PublicWorkshopBrowseItemSkeleton>,
        val totalCount: Int?,
        val maxPage: Int?,
        val isExplicitlyEmpty: Boolean,
    )

    private data class LinkCandidate(
        val href: String,
        val label: String,
    )

    private val workshopHoverRegex = Regex(
        """SharedFileBindMouseHover\(\s*"sharedfile_(\d+)"\s*,\s*false\s*,\s*(\{.*?\})\s*\);""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val totalCountRegexes = listOf(
        Regex("""Showing\s+\d+\s*(?:-|to)\s*\d+\s+of\s+([\d,]+)\s+(?:entries|results)""", RegexOption.IGNORE_CASE),
        Regex("""([\d,]+)\s+(?:entries|results)\s+matching\s+filters""", RegexOption.IGNORE_CASE),
        Regex("""正在显示第\s*\d+\s*-\s*\d+\s*项，共\s*([\d,]+)\s*项(?:条目|结果)"""),
        Regex("""共\s*([\d,]+)\s*项(?:条目|结果)"""),
    )
    private val emptyStateRegexes = listOf(
        Regex("""\b0\s+(?:entries|results)\s+matching\s+filters\b""", RegexOption.IGNORE_CASE),
        Regex("""no\s+(?:items|entries|results)\s+matching""", RegexOption.IGNORE_CASE),
        Regex("""没有[^。]*匹配"""),
        Regex("""没有可显示的创意工坊条目"""),
    )
    private val ssrLoaderDataRegex = Regex("""window\.SSR\.loaderData\s*=\s*(\[[\s\S]*?]);""")
    private val ssrRenderContextRegex = Regex("""window\.SSR\.renderContext\s*=\s*JSON\.parse\("([\s\S]*?)"\);?""")
    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun parse(
        appId: Int,
        query: WorkshopBrowseQuery,
        html: String,
        baseUrl: String,
    ): PublicWorkshopBrowseSkeleton {
        val document = Jsoup.parse(html, baseUrl)
        val ssrPayload = parseSsrBrowsePayload(
            appId = appId,
            query = query,
            html = html,
        )
        val itemSkeletons = ssrPayload.items.ifEmpty {
            extractItemSkeletons(document, html)
        }
        val totalCount = ssrPayload.totalCount ?: parseTotalCount(document, html)
        val maxPage = (ssrPayload.maxPage ?: parseMaxPage(document)).coerceAtLeast(query.page)
        val sectionOptions = parseSectionOptions(document)
        val sortOptions = parseSortOptions(document)
        val periodOptions = parsePeriodOptions(document)
        val domTagGroups = parseTagGroups(document)
        val tagGroups = ssrPayload.tagGroups.ifEmpty { domTagGroups }
        val supportsIncompatibleFilter = ssrPayload.supportsIncompatibleFilter
            ?: supportsIncompatibleFilter(document)

        return PublicWorkshopBrowseSkeleton(
            items = itemSkeletons,
            totalCount = totalCount,
            maxPage = maxPage,
            sectionOptions = if (sectionOptions.isEmpty()) {
                emptyList()
            } else {
                ensureCurrentSection(sectionOptions, query.sectionKey)
            },
            sortOptions = if (sortOptions.isEmpty()) {
                emptyList()
            } else {
                ensureCurrentSortOption(sortOptions, query.sortKey)
            },
            periodOptions = if (periodOptions.isEmpty()) {
                emptyList()
            } else {
                ensureCurrentPeriodOption(periodOptions, query.periodDays)
            },
            tagGroups = tagGroups,
            supportsIncompatibleFilter = supportsIncompatibleFilter,
            isExplicitlyEmpty = ssrPayload.isExplicitlyEmpty || isExplicitlyEmpty(document, totalCount),
        )
    }

    private fun extractItemSkeletons(
        document: Document,
        html: String,
    ): List<PublicWorkshopBrowseItemSkeleton> {
        val linkSkeletons = extractLinkBasedSkeletons(document)
        val dataSkeletons = extractDataAttributeSkeletons(document)
        val skeletonById = LinkedHashMap<Long, PublicWorkshopBrowseItemSkeleton>()

        fun upsert(items: List<PublicWorkshopBrowseItemSkeleton>) {
            items.forEach { item ->
                val existing = skeletonById[item.publishedFileId]
                skeletonById[item.publishedFileId] = mergeSkeletons(existing, item)
            }
        }

        upsert(linkSkeletons)
        upsert(dataSkeletons)

        val hoverIds = workshopHoverRegex.findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1)?.toLongOrNull() }
            .filter { it > 0L }
            .distinct()
            .toList()

        val preferredOrder = when {
            hoverIds.isNotEmpty() -> hoverIds
            dataSkeletons.isNotEmpty() -> dataSkeletons.map(PublicWorkshopBrowseItemSkeleton::publishedFileId)
            else -> linkSkeletons.map(PublicWorkshopBrowseItemSkeleton::publishedFileId)
        }

        return preferredOrder.map { publishedFileId ->
            skeletonById[publishedFileId] ?: PublicWorkshopBrowseItemSkeleton(
                publishedFileId = publishedFileId,
            )
        }
    }

    private fun extractLinkBasedSkeletons(document: Document): List<PublicWorkshopBrowseItemSkeleton> {
        val results = LinkedHashMap<Long, PublicWorkshopBrowseItemSkeleton>()
        document.select("a[href*=\"sharedfiles/filedetails/\"]").forEach { link ->
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            val publishedFileId = extractPublishedFileId(href) ?: return@forEach
            results[publishedFileId] = mergeSkeletons(
                results[publishedFileId],
                buildItemSkeleton(link, publishedFileId),
            )
        }
        return results.values.toList()
    }

    private fun extractDataAttributeSkeletons(document: Document): List<PublicWorkshopBrowseItemSkeleton> {
        val results = LinkedHashMap<Long, PublicWorkshopBrowseItemSkeleton>()
        document.select("[data-publishedfileid]").forEach { element ->
            val publishedFileId = element.attr("data-publishedfileid").toLongOrNull()
                ?.takeIf { it > 0L }
                ?: return@forEach
            results[publishedFileId] = mergeSkeletons(
                results[publishedFileId],
                buildItemSkeleton(element, publishedFileId),
            )
        }
        return results.values.toList()
    }

    private fun buildItemSkeleton(
        element: Element,
        publishedFileId: Long,
    ): PublicWorkshopBrowseItemSkeleton {
        val contexts = buildContextChain(element)
        val title = firstMeaningfulText(
            contexts.asSequence().mapNotNull { context ->
                context.selectFirst(
                    ".workshopItemTitle, [class*=workshopItemTitle], [class*=ItemTitle], [data-panel*=title]",
                )?.text()
            },
            contexts.asSequence().map { it.attr("title") },
            sequenceOf(element.text()),
            contexts.asSequence().mapNotNull { context ->
                context.selectFirst("img[alt]")?.attr("alt")
            },
        )
        val previewUrl = firstMeaningfulUrl(
            contexts.asSequence().mapNotNull { context ->
                context.selectFirst("img.workshopItemPreviewImage[src], img[src]")?.let { image ->
                    image.absUrl("src").ifBlank { image.attr("src") }
                }
            },
        )
        val authorName = firstMeaningfulAuthor(
            contexts.asSequence().mapNotNull { context ->
                context.selectFirst(
                    ".workshopItemAuthorName, [class*=AuthorName], [class*=author] a, [class*=author]",
                )?.text()
            },
        )

        return PublicWorkshopBrowseItemSkeleton(
            publishedFileId = publishedFileId,
            title = title,
            previewUrl = previewUrl,
            authorName = authorName,
        )
    }

    private fun parseSectionOptions(document: Document): List<WorkshopBrowseSectionOption> {
        val sectionRoot = findSectionRoot(document) ?: return emptyList()
        return sectionRoot.select("a[href]")
            .mapNotNull { link ->
                val candidate = link.toLinkCandidate() ?: return@mapNotNull null
                val httpUrl = candidate.href.toHttpUrlOrNull() ?: return@mapNotNull null
                if (!isWorkshopBrowseUrl(httpUrl.encodedPath)) return@mapNotNull null
                val sectionKey = httpUrl.queryParameter("section")?.trim().orEmpty()
                if (sectionKey.isBlank()) return@mapNotNull null
                val label = candidate.label.ifBlank { sectionLabel(sectionKey) }
                if (label.isBlank() || label.all(Char::isDigit)) return@mapNotNull null
                WorkshopBrowseSectionOption(
                    key = sectionKey,
                    label = label,
                )
            }
            .distinctBy(WorkshopBrowseSectionOption::key)
    }

    private fun parseSortOptions(document: Document): List<WorkshopBrowseSortOption> {
        val links = findSortingDropdownLinks(document) { link -> extractSortKey(link.href) } ?: return emptyList()
        return links.mapNotNull { link ->
            val sortKey = extractSortKey(link.href) ?: return@mapNotNull null
            WorkshopBrowseSortOption(
                key = sortKey,
                label = link.label.ifBlank { sortLabel(sortKey) },
                supportsPeriod = sortKey == WorkshopBrowseQuery.SORT_TREND,
            )
        }.distinctBy(WorkshopBrowseSortOption::key)
    }

    private fun parsePeriodOptions(document: Document): List<WorkshopBrowsePeriodOption> {
        val links = findSortingDropdownLinks(document) { link -> extractPeriodDays(link.href) } ?: return emptyList()
        return links.mapNotNull { link ->
            val days = extractPeriodDays(link.href) ?: return@mapNotNull null
            WorkshopBrowsePeriodOption(
                days = days,
                label = link.label.ifBlank { periodLabel(days) },
            )
        }.distinctBy(WorkshopBrowsePeriodOption::days)
    }

    private fun parseTagGroups(document: Document): List<WorkshopBrowseTagGroup> {
        val filterRoot = findTagFilterRoot(document) ?: return emptyList()

        val groups = mutableListOf<WorkshopBrowseTagGroup>()
        var currentLabel: String? = null
        var currentMode = WorkshopBrowseTagGroupSelectionMode.IncludeExclude
        var currentTags = mutableListOf<WorkshopBrowseTagOption>()

        fun flushCurrentGroup() {
            val label = currentLabel?.takeIf(String::isNotBlank) ?: return
            if (currentTags.isEmpty()) return
            groups += WorkshopBrowseTagGroup(
                label = label,
                tags = currentTags.toList(),
                selectionMode = currentMode,
            )
            currentLabel = null
            currentMode = WorkshopBrowseTagGroupSelectionMode.IncludeExclude
            currentTags = mutableListOf()
        }

        filterRoot.select(
            ".tag_category_desc, [class*=tag_category_desc], [class*=tagCategory], " +
                "[class*=SidebarSectionTitle], [class*=SectionTitle], h3, h4, h5, " +
                "select.selectTagsFilter, select[class*=TagsFilter], " +
                ".filterOption, [class*=filterOption], " +
                "input.inputTagsFilter[name=\"requiredtags[]\"][value], input[name=\"requiredtags[]\"][value], " +
                "a[href*=\"requiredtags\"]",
        ).forEach { element ->
            when {
                isTagHeadingElement(element) -> {
                    flushCurrentGroup()
                    currentLabel = normalizeWhitespace(element.text())
                }

                element.tagName().equals("select", ignoreCase = true) &&
                    element.classNames().any { className ->
                        className.contains("selectTagsFilter", ignoreCase = true) ||
                            className.contains("TagsFilter", ignoreCase = true)
                    } -> {
                    val label = currentLabel?.takeIf(String::isNotBlank) ?: defaultTagGroupLabel()
                    val options = element.select("option[value]")
                        .mapNotNull { option ->
                            val value = option.attr("value").trim()
                            if (value.isBlank() || value == "-1") {
                                null
                            } else {
                                WorkshopBrowseTagOption(
                                    value = value,
                                    label = option.text().trim(),
                                )
                            }
                        }
                    if (options.isNotEmpty()) {
                        groups += WorkshopBrowseTagGroup(
                            label = label,
                            tags = options,
                            selectionMode = WorkshopBrowseTagGroupSelectionMode.SingleSelect,
                        )
                    }
                    currentLabel = null
                }

                isFilterOptionElement(element) -> {
                    val tagOption = parseTagOptionFromElement(element) ?: return@forEach
                    if (currentLabel.isNullOrBlank()) {
                        currentLabel = defaultTagGroupLabel()
                    }
                    currentMode = WorkshopBrowseTagGroupSelectionMode.IncludeExclude
                    if (currentTags.none { tag -> tag.value == tagOption.value }) {
                        currentTags += tagOption
                    }
                }

                element.tagName().equals("a", ignoreCase = true) -> {
                    if (element.parents().any(::isFilterOptionElement)) return@forEach
                    val tagOption = parseTagOptionFromElement(element) ?: return@forEach
                    if (currentLabel.isNullOrBlank()) {
                        currentLabel = defaultTagGroupLabel()
                    }
                    currentMode = WorkshopBrowseTagGroupSelectionMode.IncludeExclude
                    if (currentTags.none { tag -> tag.value == tagOption.value }) {
                        currentTags += tagOption
                    }
                }
            }
        }

        flushCurrentGroup()
        return groups
    }

    private fun parseSsrBrowsePayload(
        appId: Int,
        query: WorkshopBrowseQuery,
        html: String,
    ): SsrBrowsePayload {
        val loaderDataObjects = parseSsrLoaderDataObjects(html)
        val browseState = parseSsrBrowseState(
            appId = appId,
            query = query,
            html = html,
        )
        return SsrBrowsePayload(
            items = browseState?.items.orEmpty(),
            totalCount = browseState?.totalCount,
            maxPage = browseState?.maxPage,
            tagGroups = parseSsrTagGroups(loaderDataObjects, query.sectionKey),
            supportsIncompatibleFilter = parseSsrSupportsIncompatibleFilter(loaderDataObjects),
            isExplicitlyEmpty = browseState?.isExplicitlyEmpty == true,
        )
    }

    private fun parseSsrLoaderDataObjects(html: String): List<JsonObject> {
        val rawLoaderData = ssrLoaderDataRegex.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()
        val loaderData = runCatching {
            json.parseToJsonElement(rawLoaderData).jsonArray
        }.getOrNull() ?: return emptyList()
        return loaderData.mapNotNull { element ->
            when (element) {
                is JsonObject -> element
                is JsonPrimitive -> {
                    if (!element.isString) {
                        null
                    } else {
                        runCatching {
                            json.parseToJsonElement(element.content).jsonObject
                        }.getOrNull()
                    }
                }

                else -> null
            }
        }
    }

    private fun parseSsrBrowseState(
        appId: Int,
        query: WorkshopBrowseQuery,
        html: String,
    ): SsrBrowseState? {
        val renderContext = parseSsrRenderContext(html) ?: return null
        val queryData = renderContext.stringOrNull("queryData")
            ?.let(::parseJsonObject)
            ?: return null
        val queries = queryData.jsonArrayOrNull("queries")
            ?.mapNotNull { element -> element.asJsonObjectOrNull() }
            .orEmpty()
        if (queries.isEmpty()) return null

        val creatorNames = queries.asSequence()
            .filter { entry -> entry.queryKeyName() == "PlayerLinkDetails" }
            .mapNotNull { entry ->
                val queryKey = entry.jsonArrayOrNull("queryKey") ?: return@mapNotNull null
                val steamId = queryKey.getOrNull(1)?.asJsonPrimitiveOrNull()?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val personaName = entry.jsonObjectOrNull("state")
                    ?.jsonObjectOrNull("data")
                    ?.jsonObjectOrNull("public_data")
                    ?.stringOrNull("persona_name")
                    ?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                steamId to personaName
            }
            .toMap()

        val browseEntry = queries.firstOrNull { entry ->
            entry.queryKeyName() == "workshop_browse" && entry.matchesWorkshopBrowseQuery(appId, query)
        } ?: queries.firstOrNull { entry ->
            entry.queryKeyName() == "workshop_browse"
        } ?: return null

        val data = browseEntry.jsonObjectOrNull("state")
            ?.jsonObjectOrNull("data")
            ?: return null
        val items = data.jsonArrayOrNull("results")
            ?.mapNotNull { result ->
                val resultObject = result.asJsonObjectOrNull() ?: return@mapNotNull null
                val publishedFileId = resultObject.stringOrNull("publishedfileid")
                    ?.toLongOrNull()
                    ?: return@mapNotNull null
                val creatorId = resultObject.stringOrNull("creator").orEmpty()
                PublicWorkshopBrowseItemSkeleton(
                    publishedFileId = publishedFileId,
                    title = resultObject.stringOrNull("title").orEmpty(),
                    previewUrl = resultObject.stringOrNull("preview_url"),
                    authorName = creatorNames[creatorId].orEmpty(),
                )
            }
            .orEmpty()

        val totalCount = data.intOrNull("total_count")
        val maxPage = data.intOrNull("total_pages")

        return SsrBrowseState(
            items = items,
            totalCount = totalCount,
            maxPage = maxPage,
            isExplicitlyEmpty = totalCount == 0 || (maxPage == 0 && items.isEmpty()),
        )
    }

    private fun parseSsrRenderContext(html: String): JsonObject? {
        val rawRenderContext = ssrRenderContextRegex.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null
        val decodedRenderContext = decodeJsonStringLiteral(rawRenderContext) ?: return null
        return parseJsonObject(decodedRenderContext)
    }

    private fun parseSsrTagGroups(
        loaderDataObjects: List<JsonObject>,
        sectionKey: String,
    ): List<WorkshopBrowseTagGroup> {
        val declaredTags = loaderDataObjects.asSequence()
            .mapNotNull { it.jsonObjectOrNull("declaredTags") }
            .firstOrNull()
            ?: return emptyList()
        val groups = workshopSectionTagKeys(sectionKey).asSequence()
            .mapNotNull { key ->
                declaredTags.jsonArrayOrNull(key)?.takeIf(JsonArray::isNotEmpty)
            }
            .firstOrNull()
            ?: declaredTags.entries.asSequence()
                .filter { (key, _) -> key.endsWith("_tags") && key != "visible_admin_tags" }
                .mapNotNull { (_, value) ->
                    value.asJsonArrayOrNull()?.takeIf(JsonArray::isNotEmpty)
                }
                .firstOrNull()
            ?: return emptyList()

        return groups.mapNotNull { group ->
            val groupObject = group.asJsonObjectOrNull() ?: return@mapNotNull null
            val tags = groupObject.jsonArrayOrNull("tags")
                ?.mapNotNull { tag ->
                    val tagObject = tag.asJsonObjectOrNull() ?: return@mapNotNull null
                    if (tagObject.booleanOrNull("admin_only") == true) {
                        return@mapNotNull null
                    }
                    val value = normalizeWhitespace(tagObject.stringOrNull("name"))
                    if (value.isBlank()) {
                        return@mapNotNull null
                    }
                    WorkshopBrowseTagOption(
                        value = value,
                        label = normalizeWhitespace(tagObject.stringOrNull("display_name")).ifBlank { value },
                    )
                }
                ?.distinctBy(WorkshopBrowseTagOption::value)
                .orEmpty()
            if (tags.isEmpty()) {
                return@mapNotNull null
            }
            val label = normalizeWhitespace(groupObject.stringOrNull("name"))
                .ifBlank { defaultTagGroupLabel() }
            val selectionMode = when {
                groupObject.stringOrNull("htmlelement")
                    ?.contains("select", ignoreCase = true) == true -> {
                    WorkshopBrowseTagGroupSelectionMode.SingleSelect
                }

                groupObject.stringOrNull("htmlelement")
                    ?.contains("dropdown", ignoreCase = true) == true -> {
                    WorkshopBrowseTagGroupSelectionMode.SingleSelect
                }

                else -> WorkshopBrowseTagGroupSelectionMode.IncludeExclude
            }
            WorkshopBrowseTagGroup(
                label = label,
                tags = tags,
                selectionMode = selectionMode,
            )
        }
    }

    private fun parseSsrSupportsIncompatibleFilter(loaderDataObjects: List<JsonObject>): Boolean? {
        return loaderDataObjects.any { objectNode ->
            objectNode.jsonObjectOrNull("workshopNumbers")?.containsKey("total_incompatible") == true
        }.takeIf { it }
    }

    private fun workshopSectionTagKeys(sectionKey: String): List<String> {
        val normalizedSectionKey = sectionKey.trim().lowercase(Locale.US)
        return buildList {
            when {
                normalizedSectionKey.contains("collection") -> add("collection_tags")
                normalizedSectionKey.contains("merch") -> add("merch_tags")
                normalizedSectionKey.contains("mtx") -> add("mtx_tags")
                else -> add("readytouse_tags")
            }

            if (normalizedSectionKey.isNotBlank()) {
                add("${normalizedSectionKey}_tags")
                if (normalizedSectionKey.endsWith("items")) {
                    add("${normalizedSectionKey.removeSuffix("items")}_tags")
                }
            }

            add("readytouse_tags")
            add("collection_tags")
            add("mtx_tags")
            add("merch_tags")
        }.distinct()
    }

    private fun parseJsonObject(value: String): JsonObject? {
        return runCatching {
            json.parseToJsonElement(value).jsonObject
        }.getOrNull()
    }

    private fun decodeJsonStringLiteral(value: String): String? {
        val normalizedValue = value
            .replace("\r", "\\r")
            .replace("\n", "\\n")
        return runCatching {
            json.parseToJsonElement("\"$normalizedValue\"").jsonPrimitive.content
        }.getOrNull()
    }

    private fun parseTotalCount(
        document: Document,
        html: String,
    ): Int? {
        val visibleText = document.text()
        return totalCountRegexes.asSequence()
            .mapNotNull { regex ->
                regex.find(visibleText)?.groupValues?.getOrNull(1)
                    ?: regex.find(html)?.groupValues?.getOrNull(1)
            }
            .mapNotNull { rawValue -> rawValue.replace(",", "").toIntOrNull() }
            .firstOrNull()
    }

    private fun parseMaxPage(document: Document): Int {
        return document.select("a[href]")
            .mapNotNull { link ->
                val httpUrl = link.absUrl("href").ifBlank { link.attr("href") }.toHttpUrlOrNull()
                    ?: return@mapNotNull null
                if (!httpUrl.encodedPath.contains("/workshop/browse")) return@mapNotNull null
                httpUrl.queryParameter("p")?.toIntOrNull()
            }
            .maxOrNull()
            ?: 1
    }

    private fun supportsIncompatibleFilter(document: Document): Boolean {
        return document.select(
            "#incompatibleCheckbox, input[name=\"requiredflags[]\"][value=\"incompatible\"], " +
                "a[href*=\"requiredflags%5B%5D=incompatible\"], a[href*=\"requiredflags[]=incompatible\"], " +
                "[class*=incompatibleCheckbox]",
        ).isNotEmpty() || document.text().contains("Show incompatible items", ignoreCase = true)
    }

    private fun isExplicitlyEmpty(
        document: Document,
        totalCount: Int?,
    ): Boolean {
        if (totalCount == 0) return true
        val text = document.text()
        return emptyStateRegexes.any { regex -> regex.containsMatchIn(text) }
    }

    private fun ensureCurrentSection(
        sections: List<WorkshopBrowseSectionOption>,
        currentSectionKey: String,
    ): List<WorkshopBrowseSectionOption> {
        val mutableSections = sections.toMutableList()
        if (mutableSections.none { it.key == currentSectionKey }) {
            mutableSections.add(
                0,
                WorkshopBrowseSectionOption(
                    key = currentSectionKey,
                    label = sectionLabel(currentSectionKey),
                ),
            )
        }
        if (mutableSections.none { it.key == WorkshopBrowseQuery.SECTION_ITEMS }) {
            mutableSections.add(
                0,
                WorkshopBrowseSectionOption(
                    key = WorkshopBrowseQuery.SECTION_ITEMS,
                    label = sectionLabel(WorkshopBrowseQuery.SECTION_ITEMS),
                ),
            )
        }
        return mutableSections.distinctBy(WorkshopBrowseSectionOption::key)
    }

    private fun ensureCurrentSortOption(
        options: List<WorkshopBrowseSortOption>,
        currentSortKey: String,
    ): List<WorkshopBrowseSortOption> {
        if (options.any { it.key == currentSortKey }) {
            return options.distinctBy(WorkshopBrowseSortOption::key)
        }
        return listOf(
            WorkshopBrowseSortOption(
                key = currentSortKey,
                label = sortLabel(currentSortKey),
                supportsPeriod = currentSortKey == WorkshopBrowseQuery.SORT_TREND,
            ),
        ) + options
    }

    private fun ensureCurrentPeriodOption(
        options: List<WorkshopBrowsePeriodOption>,
        currentPeriodDays: Int,
    ): List<WorkshopBrowsePeriodOption> {
        if (options.any { it.days == currentPeriodDays }) {
            return options.distinctBy(WorkshopBrowsePeriodOption::days)
        }
        return listOf(
            WorkshopBrowsePeriodOption(
                days = currentPeriodDays,
                label = periodLabel(currentPeriodDays),
            ),
        ) + options
    }

    private fun mergeSkeletons(
        existing: PublicWorkshopBrowseItemSkeleton?,
        incoming: PublicWorkshopBrowseItemSkeleton,
    ): PublicWorkshopBrowseItemSkeleton {
        if (existing == null) return incoming
        return PublicWorkshopBrowseItemSkeleton(
            publishedFileId = incoming.publishedFileId,
            title = existing.title.ifBlank { incoming.title },
            previewUrl = existing.previewUrl ?: incoming.previewUrl,
            authorName = existing.authorName.ifBlank { incoming.authorName },
        )
    }

    private fun extractPublishedFileId(rawHref: String): Long? {
        val httpUrl = rawHref.toHttpUrlOrNull()
        val queryId = httpUrl?.queryParameter("id")?.toLongOrNull()?.takeIf { it > 0L }
        if (queryId != null) return queryId
        return Regex("""[?&]id=(\d+)""")
            .find(rawHref)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    }

    private fun findSectionRoot(document: Document): Element? {
        val candidateRoots = document.select(
            "div.rightDetailsBlock, div[class*=rightDetailsBlock], div[class*=rightDetails], " +
                "div[class*=BrowseSidebar], div[class*=browseSidebar], aside",
        )
        return candidateRoots.maxByOrNull { root ->
            sectionKeys(root).size
        }?.takeIf { root ->
            sectionKeys(root).size >= 2
        }
    }

    private fun sectionKeys(root: Element): Set<String> {
        return root.select("a[href]")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                val httpUrl = href.toHttpUrlOrNull() ?: return@mapNotNull null
                if (!isWorkshopBrowseUrl(httpUrl.encodedPath)) return@mapNotNull null
                httpUrl.queryParameter("section")?.trim()?.takeIf(String::isNotBlank)
            }
            .toSet()
    }

    private fun findTagFilterRoot(document: Document): Element? {
        val candidateRoots = document.select(
            "div.rightDetailsBlock, div[class*=rightDetailsBlock], div[class*=rightDetails], " +
                "div[class*=BrowseSidebar], div[class*=browseSidebar], aside",
        )
        return candidateRoots.maxByOrNull { root ->
            root.select(
                "input[name=\"requiredtags[]\"][value], select.selectTagsFilter, select[class*=TagsFilter], " +
                    "a[href*=\"requiredtags\"], input[name=\"requiredflags[]\"][value=\"incompatible\"], " +
                    "#incompatibleCheckbox",
            ).size
        }?.takeIf { root ->
            root.select(
                "input[name=\"requiredtags[]\"][value], select.selectTagsFilter, select[class*=TagsFilter], " +
                    "a[href*=\"requiredtags\"], input[name=\"requiredflags[]\"][value=\"incompatible\"], " +
                    "#incompatibleCheckbox",
            ).isNotEmpty()
        }
    }

    private fun <T> findSortingDropdownLinks(
        document: Document,
        valueExtractor: (LinkCandidate) -> T?,
    ): List<LinkCandidate>? {
        return parseSortingDropdownGroups(document)
            .map { group ->
                group to group.mapNotNull(valueExtractor).distinct()
            }
            .maxByOrNull { (_, values) -> values.size }
            ?.takeIf { (_, values) -> values.size >= 2 }
            ?.first
    }

    private fun parseSortingDropdownGroups(document: Document): List<List<LinkCandidate>> {
        val controlRoot = document.select(
            "div.workshopBrowseSortingControls, div[class*=workshopBrowseSortingControls], div[class*=BrowseSortingControls]",
        ).maxByOrNull { root ->
            root.select("[data-dropdown-html]").size
        }
        val dropdownElements = controlRoot
            ?.select("[data-dropdown-html]")
            ?.takeIf { it.isNotEmpty() }
            ?: document.select("[data-dropdown-html]")

        return dropdownElements.mapNotNull { element ->
            parseLinksFromHtml(
                html = Parser.unescapeEntities(element.attr("data-dropdown-html"), false),
                baseUrl = document.baseUri(),
            ).takeIf { it.isNotEmpty() }
        }
    }

    private fun parseLinksFromHtml(
        html: String,
        baseUrl: String,
    ): List<LinkCandidate> {
        if (html.isBlank()) return emptyList()
        return Jsoup.parseBodyFragment(html, baseUrl)
            .select("a[href]")
            .mapNotNull { link -> link.toLinkCandidate() }
            .distinctBy { "${it.href}|${it.label}" }
    }

    private fun Element.toLinkCandidate(): LinkCandidate? {
        val href = absUrl("href").ifBlank { attr("href") }.trim()
        if (href.isBlank()) return null
        return LinkCandidate(
            href = href,
            label = normalizeWhitespace(text()),
        )
    }

    private fun isTagHeadingElement(element: Element): Boolean {
        if (element.tagName().equals("a", ignoreCase = true)) return false
        if (element.tagName().equals("input", ignoreCase = true)) return false
        if (element.tagName().equals("select", ignoreCase = true)) return false
        if (isFilterOptionElement(element)) return false
        if (element.select("a[href*=\"requiredtags\"], input[name=\"requiredtags[]\"], select").isNotEmpty()) {
            return false
        }
        val text = normalizeWhitespace(element.text())
        if (text.isBlank() || text.length > 40) return false
        if (text.all(Char::isDigit)) return false
        if (text in ignoredTagHeadingLabels) return false
        if (element.hasClass("tag_category_desc")) return true
        if (element.classNames().any { className ->
                className.contains("tag_category", ignoreCase = true) ||
                    className.contains("SectionTitle", ignoreCase = true)
            }
        ) {
            return true
        }
        return element.tagName().lowercase(Locale.US) in setOf("h3", "h4", "h5")
    }

    private fun isFilterOptionElement(element: Element): Boolean {
        if (element.hasClass("filterOption")) return true
        return element.classNames().any { className ->
            className.contains("filterOption", ignoreCase = true)
        }
    }

    private fun parseTagOptionFromElement(element: Element): WorkshopBrowseTagOption? {
        val link = when {
            element.tagName().equals("a", ignoreCase = true) -> element
            else -> element.selectFirst("a[href*=\"requiredtags\"]")
        }
        if (link != null) {
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            val httpUrl = href.toHttpUrlOrNull()
            val value = httpUrl
                ?.queryParameterValues("requiredtags[]")
                ?.firstOrNull()
                ?.takeIf(String::isNotBlank)
                ?: Regex("""requiredtags(?:%5B%5D|\[\])=([^&]+)""", RegexOption.IGNORE_CASE)
                    .find(href)
                    ?.groupValues
                    ?.getOrNull(1)
            val label = normalizeWhitespace(link.text())
            if (!value.isNullOrBlank() && label.isNotBlank()) {
                return WorkshopBrowseTagOption(value = value, label = label)
            }
        }

        val input = when {
            element.tagName().equals("input", ignoreCase = true) &&
                element.attr("name") == "requiredtags[]" -> element
            else -> element.selectFirst(
                "input.inputTagsFilter[name=\"requiredtags[]\"][value], input[name=\"requiredtags[]\"][value]",
            )
        } ?: return null
        val value = input.attr("value").trim()
        if (value.isBlank() || value == "-1") return null
        val label = normalizeWhitespace(
            input.closest("label")?.text()
                ?: element.selectFirst("label")?.text()
                ?: element.text(),
        )
        return label.takeIf(String::isNotBlank)?.let {
            WorkshopBrowseTagOption(value = value, label = it)
        }
    }

    private fun defaultTagGroupLabel(): String = "标签"

    private fun JsonObject.queryKeyName(): String? {
        return jsonArrayOrNull("queryKey")
            ?.firstOrNull()
            ?.asJsonPrimitiveOrNull()
            ?.contentOrNull
    }

    private fun JsonObject.matchesWorkshopBrowseQuery(
        appId: Int,
        query: WorkshopBrowseQuery,
    ): Boolean {
        val queryKey = jsonArrayOrNull("queryKey") ?: return false
        val params = queryKey.getOrNull(1)?.asJsonObjectOrNull() ?: return false
        return params.intOrNull("appid") == appId &&
            params.stringOrNull("section") == query.sectionKey &&
            params.intOrNull("page") == query.page &&
            params.stringOrNull("browse_sort") == query.sortKey &&
            when (query.sortKey) {
                WorkshopBrowseQuery.SORT_TREND -> params.intOrNull("trend_days") == query.periodDays
                else -> true
            }
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement.asJsonArrayOrNull(): JsonArray? = this as? JsonArray

    private fun JsonElement.asJsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    private fun JsonObject.jsonObjectOrNull(key: String): JsonObject? = this[key]?.asJsonObjectOrNull()

    private fun JsonObject.jsonArrayOrNull(key: String): JsonArray? = this[key]?.asJsonArrayOrNull()

    private fun JsonObject.stringOrNull(key: String): String? = this[key]?.asJsonPrimitiveOrNull()?.contentOrNull

    private fun JsonObject.intOrNull(key: String): Int? = stringOrNull(key)?.toIntOrNull()

    private fun JsonObject.booleanOrNull(key: String): Boolean? {
        return when (stringOrNull(key)?.lowercase(Locale.US)) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun extractSortKey(rawHref: String): String? {
        val httpUrl = rawHref.toHttpUrlOrNull() ?: return null
        if (!isWorkshopBrowseUrl(httpUrl.encodedPath)) return null
        return httpUrl.queryParameter("browsesort")
            ?.trim()
            ?.ifBlank { null }
            ?: httpUrl.queryParameter("actualsort")
                ?.trim()
                ?.ifBlank { null }
    }

    private fun extractPeriodDays(rawHref: String): Int? {
        val httpUrl = rawHref.toHttpUrlOrNull() ?: return null
        if (!isWorkshopBrowseUrl(httpUrl.encodedPath)) return null
        return httpUrl.queryParameter("days")?.trim()?.toIntOrNull()
    }

    private fun isWorkshopBrowseUrl(path: String): Boolean {
        return path.contains("/workshop/browse")
    }

    private fun buildContextChain(element: Element): List<Element> {
        val contexts = ArrayList<Element>(5)
        var current: Element? = element
        repeat(5) {
            if (current == null) return@repeat
            contexts += current
            current = current.parent()
        }
        return contexts
    }

    private fun firstMeaningfulText(vararg sources: Sequence<String?>): String {
        sources.forEach { source ->
            source.forEach { candidate ->
                val normalized = normalizeWhitespace(candidate)
                if (normalized.isNotBlank() && normalized.length > 1) {
                    return normalized
                }
            }
        }
        return ""
    }

    private fun firstMeaningfulUrl(source: Sequence<String?>): String? {
        source.forEach { candidate ->
            val normalized = normalizeWhitespace(candidate)
            if (normalized.isNotBlank()) return normalized
        }
        return null
    }

    private fun firstMeaningfulAuthor(source: Sequence<String?>): String {
        source.forEach { candidate ->
            val normalized = normalizeWhitespace(candidate)
                .removePrefix("by ")
                .removePrefix("By ")
                .removePrefix("BY ")
                .trim()
            if (normalized.isNotBlank() && normalized.length > 1) {
                return normalized
            }
        }
        return ""
    }

    private fun normalizeWhitespace(value: String?): String {
        return value
            .orEmpty()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun sectionLabel(sectionKey: String): String {
        return when (sectionKey) {
            WorkshopBrowseQuery.SECTION_MY_SUBSCRIPTIONS -> "我的订阅"
            WorkshopBrowseQuery.SECTION_ITEMS -> "项目"
            "collections" -> "合集"
            else -> sectionKey
        }
    }

    private fun sortLabel(sortKey: String): String {
        return when (sortKey) {
            WorkshopBrowseQuery.SORT_TREND -> "最热门"
            WorkshopBrowseQuery.SORT_TOP_RATED -> "最受好评（发布至今）"
            WorkshopBrowseQuery.SORT_MOST_RECENT -> "最近发行"
            WorkshopBrowseQuery.SORT_LAST_UPDATED -> "最新更新"
            WorkshopBrowseQuery.SORT_TOTAL_UNIQUE_SUBSCRIBERS -> "不重复订阅者总计"
            else -> sortKey
        }
    }

    private fun periodLabel(days: Int): String {
        return when (days) {
            1 -> "今天"
            7 -> "1 周"
            30 -> "30 天"
            90 -> "3 个月"
            180 -> "6 个月"
            365 -> "1 年"
            -1 -> "All Time"
            else -> String.format(Locale.US, "%d 天", days)
        }
    }

    private val ignoredTagHeadingLabels = setOf(
        "Special Filters:",
        "None",
        "Filter by Date",
        "Show items tagged with all of the selected terms:",
        "Show items tagged with any of the selected terms:",
        "Show incompatible items",
    )
}
