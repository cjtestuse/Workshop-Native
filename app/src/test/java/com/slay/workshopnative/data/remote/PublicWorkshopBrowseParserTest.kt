package com.slay.workshopnative.data.remote

import com.slay.workshopnative.data.model.WorkshopBrowseQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PublicWorkshopBrowseParserTest {

    @Test
    fun parse_extracts_items_and_filters_from_dom() {
        val html = """
            <html>
            <body>
            <div class="workshopBrowseSortingControls">
              <div
                class="dropdown"
                data-dropdown-html="&lt;a href='https://steamcommunity.com/workshop/browse/?appid=431960&amp;actualsort=trend&amp;browsesort=trend'&gt;趋势&lt;/a&gt;&lt;a href='https://steamcommunity.com/workshop/browse/?appid=431960&amp;actualsort=lastupdated&amp;browsesort=lastupdated'&gt;最近更新&lt;/a&gt;">
              </div>
              <div
                class="dropdown"
                data-dropdown-html="&lt;a href='https://steamcommunity.com/workshop/browse/?appid=431960&amp;days=7'&gt;一周内&lt;/a&gt;&lt;a href='https://steamcommunity.com/workshop/browse/?appid=431960&amp;days=30'&gt;一个月内&lt;/a&gt;">
              </div>
            </div>
            <div class="rightDetailsBlock">
              <a href="https://steamcommunity.com/workshop/browse/?appid=431960&section=readytouseitems">项目</a>
              <a href="https://steamcommunity.com/workshop/browse/?appid=431960&section=collections">合集</a>
              <div class="tag_category_desc">风格</div>
              <div class="filterOption">
                <label>
                  <input class="inputTagsFilter" name="requiredtags[]" value="anime" />
                  动漫
                </label>
              </div>
              <input id="incompatibleCheckbox" name="requiredflags[]" value="incompatible" />
            </div>
            <div class="summary">18 entries matching filters</div>
            <div class="workshopItem" data-publishedfileid="100">
              <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=100">
                <img class="workshopItemPreviewImage" src="https://cdn.example.com/100.jpg" alt="Title 100" />
              </a>
              <div class="workshopItemTitle">Title 100</div>
              <div class="workshopItemAuthorName ellipsis">by <a>Author One</a></div>
            </div>
            <div class="workshopItem" data-publishedfileid="200">
              <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=200">Title 200</a>
              <div class="workshopItemAuthorName ellipsis">by <a>Author Two</a></div>
            </div>
            <div class="pagination">
              <a href="https://steamcommunity.com/workshop/browse/?appid=431960&p=1">1</a>
              <a href="https://steamcommunity.com/workshop/browse/?appid=431960&p=2">2</a>
            </div>
            </body>
            </html>
        """.trimIndent()

        val result = PublicWorkshopBrowseParser.parse(
            appId = 431960,
            query = WorkshopBrowseQuery(
                page = 1,
                sectionKey = WorkshopBrowseQuery.SECTION_ITEMS,
            ),
            html = html,
            baseUrl = "https://steamcommunity.com/workshop/browse/?appid=431960",
        )

        assertEquals(listOf(100L, 200L), result.items.map { it.publishedFileId })
        assertEquals("Title 100", result.items[0].title)
        assertEquals("Author One", result.items[0].authorName)
        assertEquals(18, result.totalCount)
        assertEquals(2, result.maxPage)
        assertEquals(
            listOf(WorkshopBrowseQuery.SECTION_ITEMS, "collections"),
            result.sectionOptions.map { it.key },
        )
        assertEquals(listOf("trend", "lastupdated"), result.sortOptions.map { it.key })
        assertEquals(listOf(7, 30), result.periodOptions.map { it.days })
        assertEquals(listOf("风格"), result.tagGroups.map { it.label })
        assertEquals(listOf("anime"), result.tagGroups.flatMap { group -> group.tags.map { it.value } })
        assertTrue(result.supportsIncompatibleFilter)
        assertFalse(result.isExplicitlyEmpty)
    }

    @Test
    fun parse_falls_back_to_detail_links_and_detects_explicit_empty() {
        val html = """
            <html>
            <body>
            <div class="summary">0 entries matching filters</div>
            <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=300">Title 300</a>
            <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=300">Duplicate Title 300</a>
            <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=400">
              <img src="https://cdn.example.com/400.jpg" alt="Title 400" />
            </a>
            </body>
            </html>
        """.trimIndent()

        val result = PublicWorkshopBrowseParser.parse(
            appId = 431960,
            query = WorkshopBrowseQuery(page = 1),
            html = html,
            baseUrl = "https://steamcommunity.com/workshop/browse/?appid=431960",
        )

        assertEquals(listOf(300L, 400L), result.items.map { it.publishedFileId })
        assertEquals("Title 300", result.items[0].title)
        assertEquals("Title 400", result.items[1].title)
        assertEquals(0, result.totalCount)
        assertTrue(result.isExplicitlyEmpty)
    }

    @Test
    fun parse_uses_dropdown_labels_for_sort_and_period_instead_of_sidebar_links() {
        val html = """
            <html>
            <body>
            <div class="rightDetailsBlock">
              <a href="https://steamcommunity.com/workshop/browse/?appid=431960&section=readytouseitems&actualsort=trend&browsesort=trend&days=7">项目</a>
              <a href="https://steamcommunity.com/workshop/browse/?appid=431960&section=collections&actualsort=trend&browsesort=trend&days=7">合集</a>
            </div>
            <div class="pagination">
              <a href="https://steamcommunity.com/workshop/browse/?appid=431960&p=2&actualsort=trend&browsesort=trend&days=7">2</a>
            </div>
            <div class="workshopBrowseSortingControls">
              <div
                class="dropdown"
                data-dropdown-html="&lt;a href='https://steamcommunity.com/workshop/browse/?appid=431960&amp;actualsort=trend&amp;browsesort=trend'&gt;最热门&lt;/a&gt;&lt;a href='https://steamcommunity.com/workshop/browse/?appid=431960&amp;actualsort=mostrecent&amp;browsesort=mostrecent'&gt;最新&lt;/a&gt;">
              </div>
              <div
                class="dropdown"
                data-dropdown-html="&lt;a href='https://steamcommunity.com/workshop/browse/?appid=431960&amp;days=7'&gt;一周&lt;/a&gt;&lt;a href='https://steamcommunity.com/workshop/browse/?appid=431960&amp;days=30'&gt;30 天&lt;/a&gt;">
              </div>
            </div>
            <div class="workshopItem" data-publishedfileid="100">
              <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=100">Title 100</a>
            </div>
            </body>
            </html>
        """.trimIndent()

        val result = PublicWorkshopBrowseParser.parse(
            appId = 431960,
            query = WorkshopBrowseQuery(
                page = 1,
                sectionKey = WorkshopBrowseQuery.SECTION_ITEMS,
                sortKey = WorkshopBrowseQuery.SORT_TREND,
                periodDays = 7,
            ),
            html = html,
            baseUrl = "https://steamcommunity.com/workshop/browse/?appid=431960",
        )

        assertEquals(
            listOf("最热门", "最新"),
            result.sortOptions.map { it.label },
        )
        assertEquals(
            listOf("一周", "30 天"),
            result.periodOptions.map { it.label },
        )
    }

    @Test
    fun parse_supports_current_steam_public_quick_filter_structure() {
        val html = """
            <html>
            <body>
            <div class="workshopBrowseSortingControls">
              <div
                class="dropdown"
                data-dropdown-html="&lt;a href='https://steamcommunity.com/workshop/browse/?appid=646570&amp;actualsort=trend&amp;browsesort=trend'&gt;最热门&lt;/a&gt;&lt;a href='https://steamcommunity.com/workshop/browse/?appid=646570&amp;browsesort=toprated'&gt;最受好评（发布至今）&lt;/a&gt;&lt;a href='https://steamcommunity.com/workshop/browse/?appid=646570&amp;browsesort=mostrecent'&gt;最近发行&lt;/a&gt;&lt;a href='https://steamcommunity.com/workshop/browse/?appid=646570&amp;browsesort=lastupdated'&gt;最新更新&lt;/a&gt;&lt;a href='https://steamcommunity.com/workshop/browse/?appid=646570&amp;browsesort=totaluniquesubscribers'&gt;不重复订阅者总计&lt;/a&gt;">
              </div>
              <div
                class="dropdown"
                data-dropdown-html="&lt;a href='https://steamcommunity.com/workshop/browse/?appid=646570&amp;actualsort=trend&amp;browsesort=trend&amp;days=1'&gt;今天&lt;/a&gt;&lt;a href='https://steamcommunity.com/workshop/browse/?appid=646570&amp;actualsort=trend&amp;browsesort=trend&amp;days=7'&gt;1 周&lt;/a&gt;&lt;a href='https://steamcommunity.com/workshop/browse/?appid=646570&amp;actualsort=trend&amp;browsesort=trend&amp;days=30'&gt;30 天&lt;/a&gt;&lt;a href='https://steamcommunity.com/workshop/browse/?appid=646570&amp;actualsort=trend&amp;browsesort=trend&amp;days=90'&gt;3 个月&lt;/a&gt;&lt;a href='https://steamcommunity.com/workshop/browse/?appid=646570&amp;actualsort=trend&amp;browsesort=trend&amp;days=180'&gt;6 个月&lt;/a&gt;&lt;a href='https://steamcommunity.com/workshop/browse/?appid=646570&amp;actualsort=trend&amp;browsesort=trend&amp;days=365'&gt;1 年&lt;/a&gt;&lt;a href='https://steamcommunity.com/workshop/browse/?appid=646570&amp;actualsort=trend&amp;browsesort=trend&amp;days=-1'&gt;All Time&lt;/a&gt;">
              </div>
            </div>
            <div class="rightDetailsBlock">
              <a href="https://steamcommunity.com/workshop/browse/?appid=646570&section=readytouseitems&actualsort=trend&browsesort=trend&days=7">项目</a>
              <a href="https://steamcommunity.com/workshop/browse/?appid=646570&section=collections&actualsort=trend&browsesort=trend&days=7">合集</a>
            </div>
            <div class="workshopItem" data-publishedfileid="100">
              <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=100">Title 100</a>
            </div>
            </body>
            </html>
        """.trimIndent()

        val result = PublicWorkshopBrowseParser.parse(
            appId = 646570,
            query = WorkshopBrowseQuery(
                page = 1,
                sectionKey = WorkshopBrowseQuery.SECTION_ITEMS,
                sortKey = WorkshopBrowseQuery.SORT_TREND,
                periodDays = 7,
            ),
            html = html,
            baseUrl = "https://steamcommunity.com/workshop/browse/?appid=646570",
        )

        assertEquals(
            listOf(
                WorkshopBrowseQuery.SORT_TREND,
                WorkshopBrowseQuery.SORT_TOP_RATED,
                WorkshopBrowseQuery.SORT_MOST_RECENT,
                WorkshopBrowseQuery.SORT_LAST_UPDATED,
                WorkshopBrowseQuery.SORT_TOTAL_UNIQUE_SUBSCRIBERS,
            ),
            result.sortOptions.map { it.key },
        )
        assertEquals(
            listOf(
                "最热门",
                "最受好评（发布至今）",
                "最近发行",
                "最新更新",
                "不重复订阅者总计",
            ),
            result.sortOptions.map { it.label },
        )
        assertEquals(listOf(1, 7, 30, 90, 180, 365, -1), result.periodOptions.map { it.days })
        assertEquals(
            listOf("今天", "1 周", "30 天", "3 个月", "6 个月", "1 年", "All Time"),
            result.periodOptions.map { it.label },
        )
    }

    @Test
    fun parse_prefers_browsesort_when_actualsort_conflicts() {
        val html = """
            <html>
            <body>
            <div class="workshopBrowseSortingControls">
              <div
                class="dropdown"
                data-dropdown-html="&lt;a href='https://steamcommunity.com/workshop/browse/?appid=646570&amp;actualsort=trend&amp;browsesort=toprated'&gt;最受好评（发布至今）&lt;/a&gt;&lt;a href='https://steamcommunity.com/workshop/browse/?appid=646570&amp;actualsort=trend&amp;browsesort=mostrecent'&gt;最近发行&lt;/a&gt;">
              </div>
            </div>
            <div class="workshopItem" data-publishedfileid="100">
              <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=100">Title 100</a>
            </div>
            </body>
            </html>
        """.trimIndent()

        val result = PublicWorkshopBrowseParser.parse(
            appId = 646570,
            query = WorkshopBrowseQuery(
                page = 1,
                sortKey = WorkshopBrowseQuery.SORT_TOP_RATED,
            ),
            html = html,
            baseUrl = "https://steamcommunity.com/workshop/browse/?appid=646570",
        )

        assertEquals(
            listOf(
                WorkshopBrowseQuery.SORT_TOP_RATED,
                WorkshopBrowseQuery.SORT_MOST_RECENT,
            ),
            result.sortOptions.map { it.key },
        )
    }

    @Test
    fun parse_returns_empty_sort_and_period_options_when_only_current_value_is_present_outside_dropdowns() {
        val html = """
            <html>
            <body>
            <div class="rightDetailsBlock">
              <a href="https://steamcommunity.com/workshop/browse/?appid=431960&section=readytouseitems&actualsort=trend&browsesort=trend&days=7">项目</a>
              <a href="https://steamcommunity.com/workshop/browse/?appid=431960&section=collections&actualsort=trend&browsesort=trend&days=7">合集</a>
            </div>
            <div class="workshopItem" data-publishedfileid="100">
              <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=100">Title 100</a>
            </div>
            </body>
            </html>
        """.trimIndent()

        val result = PublicWorkshopBrowseParser.parse(
            appId = 431960,
            query = WorkshopBrowseQuery(
                page = 1,
                sectionKey = WorkshopBrowseQuery.SECTION_ITEMS,
                sortKey = WorkshopBrowseQuery.SORT_TREND,
                periodDays = 7,
            ),
            html = html,
            baseUrl = "https://steamcommunity.com/workshop/browse/?appid=431960",
        )

        assertTrue(result.sortOptions.isEmpty())
        assertTrue(result.periodOptions.isEmpty())
        assertEquals(
            listOf(WorkshopBrowseQuery.SECTION_ITEMS, "collections"),
            result.sectionOptions.map { it.key },
        )
    }

    @Test
    fun parse_tag_groups_support_modern_sidebar_structure() {
        val html = """
            <html>
            <body>
            <aside>
              <div class="tag_category_desc">错误分组</div>
              <div class="filterOption">
                <label>
                  <input class="inputTagsFilter" name="requiredtags[]" value="wrong" />
                  Wrong
                </label>
              </div>
            </aside>
            <div class="BrowseSidebarCtn">
              <div class="SidebarSectionTitle">CATEGORIES</div>
              <div class="SidebarSectionBody">
                <div class="filterOption">
                  <label>
                    <input class="inputTagsFilter" name="requiredtags[]" value="Tools" />
                    Tools
                  </label>
                </div>
                <div class="filterOption">
                  <a href="https://steamcommunity.com/workshop/browse/?appid=646570&requiredtags%5B%5D=Api">Api</a>
                </div>
              </div>
              <div class="tag_category_desc">Language</div>
              <div class="NestedSelect">
                <select class="selectTagsFilter">
                  <option value="-1">Any</option>
                  <option value="English">English</option>
                  <option value="Chinese">Chinese</option>
                </select>
              </div>
              <div class="CompatRow">
                <label>
                  <input name="requiredflags[]" value="incompatible" />
                  Show incompatible items
                </label>
              </div>
            </div>
            </body>
            </html>
        """.trimIndent()

        val result = PublicWorkshopBrowseParser.parse(
            appId = 431960,
            query = WorkshopBrowseQuery(page = 1),
            html = html,
            baseUrl = "https://steamcommunity.com/workshop/browse/?appid=431960",
        )

        assertEquals(listOf("CATEGORIES", "Language"), result.tagGroups.map { it.label })
        assertEquals(listOf("Tools", "Api", "English", "Chinese"), result.tagGroups.flatMap { group -> group.tags.map { it.value } })
        assertEquals(
            listOf(
                com.slay.workshopnative.data.model.WorkshopBrowseTagGroupSelectionMode.IncludeExclude,
                com.slay.workshopnative.data.model.WorkshopBrowseTagGroupSelectionMode.SingleSelect,
            ),
            result.tagGroups.map { it.selectionMode },
        )
        assertTrue(result.supportsIncompatibleFilter)
    }

    @Test
    fun parse_incompatible_filter_supports_modern_checkbox_marker() {
        val html = """
            <html>
            <body>
            <div class="BrowseSidebarCtn">
              <input name="requiredflags[]" value="incompatible" />
            </div>
            </body>
            </html>
        """.trimIndent()

        val result = PublicWorkshopBrowseParser.parse(
            appId = 431960,
            query = WorkshopBrowseQuery(page = 1),
            html = html,
            baseUrl = "https://steamcommunity.com/workshop/browse/?appid=431960",
        )

        assertTrue(result.supportsIncompatibleFilter)
    }

    @Test
    fun parse_extracts_items_and_advanced_filters_from_ssr_payload() {
        val loaderItem1 = """
            {
              "declaredTags": {
                "readytouse_tags": [
                  {
                    "name": "",
                    "htmlelement": "checkbox",
                    "tags": [
                      { "name": "Tools", "display_name": "Tools", "admin_only": false },
                      { "name": "Api", "display_name": "API", "admin_only": false }
                    ]
                  },
                  {
                    "name": "Language ",
                    "htmlelement": "checkbox",
                    "tags": [
                      { "name": "English", "display_name": "English", "admin_only": false }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()
        val loaderItem2 = """
            {
              "workshopNumbers": {
                "total": 99,
                "total_incompatible": 0
              },
              "serverQuery": {
                "appid": 646570,
                "section": "readytouseitems"
              }
            }
        """.trimIndent()
        val queryData = """
            {
              "mutations": [],
              "queries": [
                {
                  "queryKey": ["PlayerLinkDetails", "76561198000000001"],
                  "state": {
                    "data": {
                      "public_data": {
                        "persona_name": "Author SSR"
                      }
                    }
                  }
                },
                {
                  "queryKey": [
                    "workshop_browse",
                    {
                      "appid": 646570,
                      "browse_sort": "trend",
                      "section": "readytouseitems",
                      "page": 1,
                      "trend_days": 7
                    }
                  ],
                  "state": {
                    "data": {
                      "current_page": 1,
                      "total_pages": 4,
                      "total_count": 99,
                      "results": [
                        {
                          "publishedfileid": "111",
                          "title": "SSR Title",
                          "preview_url": "https://cdn.example.com/111.jpg",
                          "creator": "76561198000000001"
                        }
                      ]
                    }
                  }
                }
              ]
            }
        """.trimIndent()
        val html = ssrHtml(
            loaderData = listOf(loaderItem1, loaderItem2),
            queryData = queryData,
        )

        val result = PublicWorkshopBrowseParser.parse(
            appId = 646570,
            query = WorkshopBrowseQuery(
                page = 1,
                sectionKey = WorkshopBrowseQuery.SECTION_ITEMS,
                sortKey = WorkshopBrowseQuery.SORT_TREND,
                periodDays = 7,
            ),
            html = html,
            baseUrl = "https://steamcommunity.com/workshop/browse/?appid=646570",
        )

        assertEquals(listOf(111L), result.items.map { it.publishedFileId })
        assertEquals("SSR Title", result.items[0].title)
        assertEquals("Author SSR", result.items[0].authorName)
        assertEquals(99, result.totalCount)
        assertEquals(4, result.maxPage)
        assertEquals(listOf("标签", "Language"), result.tagGroups.map { it.label })
        assertEquals(listOf("Tools", "Api", "English"), result.tagGroups.flatMap { group -> group.tags.map { it.value } })
        assertTrue(result.supportsIncompatibleFilter)
        assertFalse(result.isExplicitlyEmpty)
    }

    @Test
    fun parse_uses_section_specific_ssr_tag_bucket_and_single_select_mode() {
        val loaderItem1 = """
            {
              "declaredTags": {
                "readytouse_tags": [
                  {
                    "name": "",
                    "htmlelement": "checkbox",
                    "tags": [
                      { "name": "Gameplay", "display_name": "Gameplay", "admin_only": false }
                    ]
                  }
                ],
                "collection_tags": [
                  {
                    "name": "Language",
                    "htmlelement": "select",
                    "tags": [
                      { "name": "English", "display_name": "English", "admin_only": false },
                      { "name": "French", "display_name": "French", "admin_only": false }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()
        val loaderItem2 = """
            {
              "workshopNumbers": {
                "total": 0,
                "total_incompatible": 0
              },
              "serverQuery": {
                "appid": 646570,
                "section": "collections"
              }
            }
        """.trimIndent()
        val queryData = """
            {
              "mutations": [],
              "queries": [
                {
                  "queryKey": [
                    "workshop_browse",
                    {
                      "appid": 646570,
                      "browse_sort": "trend",
                      "section": "collections",
                      "page": 1,
                      "trend_days": 7
                    }
                  ],
                  "state": {
                    "data": {
                      "current_page": 1,
                      "total_pages": 0,
                      "total_count": 0,
                      "results": []
                    }
                  }
                }
              ]
            }
        """.trimIndent()
        val html = ssrHtml(
            loaderData = listOf(loaderItem1, loaderItem2),
            queryData = queryData,
        )

        val result = PublicWorkshopBrowseParser.parse(
            appId = 646570,
            query = WorkshopBrowseQuery(
                page = 1,
                sectionKey = "collections",
                sortKey = WorkshopBrowseQuery.SORT_TREND,
                periodDays = 7,
            ),
            html = html,
            baseUrl = "https://steamcommunity.com/workshop/browse/?appid=646570&section=collections",
        )

        assertEquals(listOf("Language"), result.tagGroups.map { it.label })
        assertEquals(listOf("English", "French"), result.tagGroups.single().tags.map { it.value })
        assertEquals(
            com.slay.workshopnative.data.model.WorkshopBrowseTagGroupSelectionMode.SingleSelect,
            result.tagGroups.single().selectionMode,
        )
        assertEquals(0, result.totalCount)
        assertTrue(result.isExplicitlyEmpty)
    }

    private fun ssrHtml(
        loaderData: List<String>,
        queryData: String,
    ): String {
        val renderContext = """{"queryData":${Json.encodeToString(queryData)}}"""
        return """
            <html>
            <body>
            <script>
            window.SSR = {};
            window.SSR.loaderData = ${Json.encodeToString(loaderData)};
            window.SSR.renderContext = JSON.parse(${Json.encodeToString(renderContext)});
            </script>
            </body>
            </html>
        """.trimIndent()
    }
}
