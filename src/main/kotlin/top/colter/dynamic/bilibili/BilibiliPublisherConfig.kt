package top.colter.dynamic.bilibili

public data class BilibiliPublisherConfig(
    val pollingIntervalMs: Long = 30_000,
    val fetchLimit: Int = 5,
    val requestIntervalMs: Long = 500,
    val replayWindowHours: Int = 0,
    val followGroupName: String = "",
    val shortUrlResolveTimeoutMs: Long = 3_000,
    val cookiesJson: String = "",
)
