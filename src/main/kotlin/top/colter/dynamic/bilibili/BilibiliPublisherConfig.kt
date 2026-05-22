package top.colter.dynamic.bilibili

public data class BilibiliPublisherConfig(
    val pollingIntervalMs: Long = 30_000,
    val subscriptionRefreshIntervalMs: Long = 300_000,
    val fetchLimit: Int = 5,
    val requestIntervalMs: Long = 500,
    val cursorPath: String = "config/bilibili-publisher-cursor.yml",
    val cookiesJson: String = "",
)
