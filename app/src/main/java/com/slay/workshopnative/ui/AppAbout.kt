package com.slay.workshopnative.ui

internal data class AppNoticeItem(
    val title: String,
    val body: String,
)

internal object WorkshopNativeAbout {
    const val appDisplayName = "Workshop Native"
    const val repositoryUrl = "https://github.com/cjtestuse/Workshop-Native"
    const val bilibiliNickname = "CXCXMCX"

    val highlights = listOf(
        "开源免费",
        "匿名优先",
        "官方数据源",
    )

    val disclaimerItems = listOf(
        AppNoticeItem(
            title = "开源免费",
            body = "本软件开源免费，仅提供客户端工具本身，不提供付费解锁、资源售卖或私有后端。",
        ),
        AppNoticeItem(
            title = "匿名优先",
            body = "公开创意工坊内容建议优先使用匿名访问；如果只是下载公开条目，能匿名就匿名。",
        ),
        AppNoticeItem(
            title = "官方来源",
            body = "浏览、登录和下载直接访问 Steam 官方相关接口与内容分发节点，应用更新仅来自 GitHub 官方 Release。",
        ),
        AppNoticeItem(
            title = "账号安全",
            body = "登录仅建议用于访问已购、共享或受限内容。登录态只保存在当前设备并加密存储，但若追求账户绝对安全，不建议长期保持登录下载。",
        ),
        AppNoticeItem(
            title = "使用边界",
            body = "请自行遵守 Steam、内容作者和所在地法律规则，仅访问你有权获取的内容。本应用与 Valve / Steam 无官方关联。",
        ),
    )

    val usageBoundaryItems = listOf(
        AppNoticeItem(
            title = "学习交流与合法使用",
            body = "本应用是开源免费的个人项目，仅用于技术研究、学习交流和个人合法场景下的创意工坊浏览与下载管理。",
        ),
        AppNoticeItem(
            title = "禁止用途",
            body = "本应用不提供破解、资源售卖、账号租借或任何绕过版权、付费授权、平台限制的服务，也不得用于批量抓取、二次分发、商业倒卖或其他与合法使用无关的用途。",
        ),
        AppNoticeItem(
            title = "项目关系",
            body = "应用中的登录、浏览和下载请求均直接连接 Steam 官方相关接口与内容分发节点。本项目与 Valve / Steam 及各创意工坊内容作者无官方关联。",
        ),
        AppNoticeItem(
            title = "用户责任",
            body = "请仅访问、下载和使用你有合法权限获取的内容，并自行遵守 Steam 平台规则、内容作者授权条款及所在地法律法规。因不当使用本应用造成的风险、损失或纠纷，由使用者自行承担。",
        ),
        AppNoticeItem(
            title = "权利反馈",
            body = "若你是相关内容的权利人，认为本项目说明、展示或使用方式存在问题，可通过项目主页联系作者反馈；项目维护者将在核实后尽快处理相关问题。",
        ),
    )
}
