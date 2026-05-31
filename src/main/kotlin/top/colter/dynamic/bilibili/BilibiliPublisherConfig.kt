package top.colter.dynamic.bilibili

import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFormSpec

public data class BilibiliPublisherConfig(
    val pollingIntervalSeconds: Double = 15.0,
    val requestIntervalSeconds: Double = 0.5,
    val replayWindowMinutes: Int = 0,
    val followGroupName: String = "",
    val shortUrlResolveTimeoutSeconds: Double = 3.0,
    val liveDetectionEnabled: Boolean = true,
    val liveStatusBatchSize: Int = 30,
    val cookiesJson: String = "",
)

public object BilibiliPublisherConfigForm {
    public val spec: ConfigFormSpec = ConfigFormSpec(
        title = "Bilibili 动态源",
        description = "Bilibili 轮询、补发、关注分组、短链接解析和登录 Cookie 设置。",
        fields = listOf(
            ConfigFieldSpec(
                path = "pollingIntervalSeconds",
                label = "轮询间隔（秒）",
                type = ConfigFieldType.NUMBER,
                section = "轮询",
                description = "检测关注 UP 主新动态和直播状态的固定间隔；支持小数，例如 1.5 表示 1.5 秒。",
                min = 1,
                restartRequired = true,
                restartTarget = "Bilibili 插件",
            ),
            ConfigFieldSpec(
                path = "requestIntervalSeconds",
                label = "请求间隔（秒）",
                type = ConfigFieldType.NUMBER,
                section = "轮询",
                description = "连续调用 Bilibili 接口之间的等待时间；支持小数，例如 0.5 表示 0.5 秒。",
                min = 0,
                restartRequired = true,
                restartTarget = "Bilibili 插件",
            ),
            ConfigFieldSpec(
                path = "replayWindowMinutes",
                label = "补发时间窗口（分钟）",
                type = ConfigFieldType.NUMBER,
                section = "补发",
                description = "插件启动或重新登录后补发该时间窗口内游标遗漏的动态；0 表示不补发历史动态。",
                min = 0,
            ),
            ConfigFieldSpec(
                path = "followGroupName",
                label = "关注分组名称",
                type = ConfigFieldType.TEXT,
                section = "关注",
                description = "配置后，插件自动关注 UP 主时会尝试加入该 Bilibili 关注分组；留空则不处理分组。",
            ),
            ConfigFieldSpec(
                path = "shortUrlResolveTimeoutSeconds",
                label = "短链接解析超时（秒）",
                type = ConfigFieldType.NUMBER,
                section = "链接",
                description = "解析 b23.tv 等短链接时等待跳转结果的超时时间；支持小数，例如 0.5 表示 0.5 秒。",
                min = 0,
            ),
            ConfigFieldSpec(
                path = "cookiesJson",
                label = "登录 Cookie（JSON）",
                type = ConfigFieldType.SECRET,
                section = "登录",
                description = "Bilibili 登录 Cookie 的 JSON 内容，通常由后台扫码或 Cookie 登录流程自动保存。",
                secret = true,
                restartRequired = true,
                restartTarget = "Bilibili 插件",
            ),
            ConfigFieldSpec(
                path = "liveDetectionEnabled",
                label = "直播检测",
                type = ConfigFieldType.BOOLEAN,
                section = "直播",
                description = "开启后会检测已订阅发布者的开播和下播事件。",
            ),
            ConfigFieldSpec(
                path = "liveStatusBatchSize",
                label = "直播状态批量查询数量",
                type = ConfigFieldType.NUMBER,
                section = "直播",
                description = "单次直播状态接口最多查询的 UP 主数量，过大可能增加接口失败概率。",
                min = 1,
            ),
        ),
    )

    public fun validate(config: BilibiliPublisherConfig) {
        require(config.pollingIntervalSeconds >= 1.0) { "轮询间隔不能小于 1 秒" }
        require(config.requestIntervalSeconds >= 0.0) { "请求间隔不能为负数" }
        require(config.replayWindowMinutes >= 0) { "补发时间窗口不能为负数" }
        require(config.shortUrlResolveTimeoutSeconds > 0.0) { "短链接解析超时必须大于 0 秒" }
        require(config.liveStatusBatchSize >= 1) { "直播状态批量查询数量不能小于 1" }
    }
}
