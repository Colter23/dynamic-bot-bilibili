package top.colter.dynamic.bilibili

import top.colter.bilibili.data.user.OfficialVerify
import top.colter.bilibili.data.user.OfficialVerifyType

internal const val BILIBILI_OFFICIAL_INDIVIDUAL_BADGE_KEY: String = "avatarBadge.official.individual"
internal const val BILIBILI_OFFICIAL_INSTITUTION_BADGE_KEY: String = "avatarBadge.official.institution"

internal fun OfficialVerify?.toAvatarBadgeKey(): String? {
    return when (this?.type) {
        null,
        OfficialVerifyType.NONE -> null
        OfficialVerifyType.INDIVIDUAL -> BILIBILI_OFFICIAL_INDIVIDUAL_BADGE_KEY
        else -> BILIBILI_OFFICIAL_INSTITUTION_BADGE_KEY
    }
}
