package top.colter.dynamic.bilibili

import top.colter.bilibili.data.user.OfficialVerify
import top.colter.bilibili.data.user.OfficialVerifyType

internal const val BILIBILI_OFFICIAL_BADGE_RESOURCE: String = "PERSONAL_OFFICIAL_VERIFY.svg"

internal fun OfficialVerify?.toOfficialBadgeResource(): String? {
    return this
        ?.takeIf { it.type != OfficialVerifyType.NONE }
        ?.let { BILIBILI_OFFICIAL_BADGE_RESOURCE }
}
