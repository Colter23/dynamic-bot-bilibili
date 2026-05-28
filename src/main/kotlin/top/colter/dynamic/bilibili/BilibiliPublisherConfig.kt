package top.colter.dynamic.bilibili

import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFormSpec

public data class BilibiliPublisherConfig(
    val pollingIntervalMs: Long = 30_000,
    val fetchLimit: Int = 5,
    val requestIntervalMs: Long = 500,
    val replayWindowHours: Int = 0,
    val followGroupName: String = "",
    val shortUrlResolveTimeoutMs: Long = 3_000,
    val liveDetectionEnabled: Boolean = true,
    val liveStatusBatchSize: Int = 50,
    val cookiesJson: String = "",
)

public object BilibiliPublisherConfigForm {
    public val spec: ConfigFormSpec = ConfigFormSpec(
        title = "Bilibili 动态源",
        description = "Bilibili 轮询、补发、关注分组、短链接解析和登录 Cookie 设置。",
        fields = listOf(
            ConfigFieldSpec(
                path = "pollingIntervalMs",
                label = "轮询间隔（毫秒）",
                type = ConfigFieldType.NUMBER,
                section = "轮询",
                min = 1_000,
                restartRequired = true,
                restartTarget = "Bilibili 插件",
            ),
            ConfigFieldSpec(
                path = "fetchLimit",
                label = "每次拉取数量",
                type = ConfigFieldType.NUMBER,
                section = "轮询",
                min = 1,
            ),
            ConfigFieldSpec(
                path = "requestIntervalMs",
                label = "请求间隔（毫秒）",
                type = ConfigFieldType.NUMBER,
                section = "轮询",
                min = 0,
                restartRequired = true,
                restartTarget = "Bilibili 插件",
            ),
            ConfigFieldSpec(
                path = "replayWindowHours",
                label = "补发时间窗口（小时）",
                type = ConfigFieldType.NUMBER,
                section = "补发",
                min = 0,
            ),
            ConfigFieldSpec(
                path = "followGroupName",
                label = "关注分组名称",
                type = ConfigFieldType.TEXT,
                section = "关注",
            ),
            ConfigFieldSpec(
                path = "shortUrlResolveTimeoutMs",
                label = "短链接解析超时（毫秒）",
                type = ConfigFieldType.NUMBER,
                section = "链接",
                min = 1,
            ),
            ConfigFieldSpec(
                path = "cookiesJson",
                label = "登录 Cookie（JSON）",
                type = ConfigFieldType.SECRET,
                section = "登录",
                secret = true,
                restartRequired = true,
                restartTarget = "Bilibili 插件",
            ),
            ConfigFieldSpec(
                path = "liveDetectionEnabled",
                label = "直播检测",
                type = ConfigFieldType.BOOLEAN,
                section = "直播",
            ),
            ConfigFieldSpec(
                path = "liveStatusBatchSize",
                label = "直播状态批量查询数量",
                type = ConfigFieldType.NUMBER,
                section = "直播",
                min = 1,
            ),
        ),
    )

    public fun validate(config: BilibiliPublisherConfig) {
        require(config.pollingIntervalMs >= 1_000) { "轮询间隔不能小于 1000 毫秒" }
        require(config.fetchLimit >= 1) { "每次拉取数量不能小于 1" }
        require(config.requestIntervalMs >= 0) { "请求间隔不能为负数" }
        require(config.replayWindowHours >= 0) { "补发时间窗口不能为负数" }
        require(config.shortUrlResolveTimeoutMs >= 1) { "短链接解析超时不能小于 1 毫秒" }
        require(config.liveStatusBatchSize >= 1) { "直播状态批量查询数量不能小于 1" }
    }
}
