package top.colter.dynamic.bilibili

import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFormSpec
import top.colter.dynamic.core.config.ConfigNumberKind

public data class BilibiliPublisherConfig(
    val pollingIntervalSeconds: Double = 30.0,
    val requestIntervalSeconds: Double = 0.5,
    val replayWindowMinutes: Int = 0,
    val followGroupName: String = "Bot关注",
    val shortUrlResolveTimeoutSeconds: Double = 3.0,
    val liveDetectionEnabled: Boolean = true,
    val liveStatusBatchSize: Int = 30,
    val maxConsecutiveLoginFailures: Int = 3,
    val requestBlockCooldownMinutes: Int = 30,
    val cookiesJson: String = "",
)

public object BilibiliPublisherConfigForm {
    public val spec: ConfigFormSpec = ConfigFormSpec(
        title = "Bilibili 动态源",
        description = "Bilibili 轮询、直播检测、补发、关注分组、短链接解析和风控处理。",
        fields = listOf(
            ConfigFieldSpec(
                path = "pollingIntervalSeconds",
                label = "轮询间隔（秒）",
                type = ConfigFieldType.NUMBER,
                section = "轮询与风控",
                description = "多久检查一次新动态和直播状态。\n最低建议不要低于15秒，太短可能增加风控风险。",
                min = 1,
                restartRequired = true,
                restartTarget = "Bilibili 插件",
            ),
            ConfigFieldSpec(
                path = "requestIntervalSeconds",
                label = "接口请求间隔（秒）",
                type = ConfigFieldType.NUMBER,
                section = "轮询与风控",
                description = "连续请求 Bilibili 接口之间等多久。\n用于降低触发风控的概率。",
                min = 0,
                restartRequired = true,
                restartTarget = "Bilibili 插件",
            ),
            ConfigFieldSpec(
                path = "liveDetectionEnabled",
                label = "直播检测",
                type = ConfigFieldType.BOOLEAN,
                section = "动态与直播",
                description = "是否检测开播和下播。\n关闭后只检测动态，不再产生直播开始和直播结束事件。",
            ),
            ConfigFieldSpec(
                path = "liveStatusBatchSize",
                label = "每批直播查询数量",
                type = ConfigFieldType.NUMBER,
                section = "动态与直播",
                description = "一次最多查询多少个 UP 主直播状态。\n调大可以减少请求次数，但接口失败概率也可能升高。",
                min = 1,
                numberKind = ConfigNumberKind.INTEGER,
            ),
            ConfigFieldSpec(
                path = "followGroupName",
                label = "关注分组名称",
                type = ConfigFieldType.TEXT,
                section = "关注与链接",
                description = "自动关注 UP 主时加入的关注分组。\n留空时只关注，不移动到指定分组。",
            ),
            ConfigFieldSpec(
                path = "replayWindowMinutes",
                label = "补发时间窗口（分钟）",
                type = ConfigFieldType.NUMBER,
                section = "动态与直播",
                description = "启动后补发最近一段时间的新动态。\n设为 0 表示不补发历史动态，只记录当前位置，避免一次性推送旧内容。",
                min = 0,
                numberKind = ConfigNumberKind.INTEGER,
            ),
            ConfigFieldSpec(
                path = "shortUrlResolveTimeoutSeconds",
                label = "短链接解析超时（秒）",
                type = ConfigFieldType.NUMBER,
                section = "关注与链接",
                description = "解析 b23.tv 等短链接时最多等待多久。\n支持小数，例如 0.5 表示 0.5 秒。",
                min = 0,
            ),
            ConfigFieldSpec(
                path = "maxConsecutiveLoginFailures",
                label = "未登录暂停阈值",
                type = ConfigFieldType.NUMBER,
                section = "轮询与风控",
                description = "连续几次检测到未登录后暂停轮询。\n重新登录后会恢复；设为 0 表示不自动暂停轮询。",
                min = 0,
                numberKind = ConfigNumberKind.INTEGER,
            ),
            ConfigFieldSpec(
                path = "requestBlockCooldownMinutes",
                label = "风控暂停时长（分钟）",
                type = ConfigFieldType.NUMBER,
                section = "轮询与风控",
                description = "检测到 Bilibili 请求被风控或拦截后暂停轮询多久。\n设为 0 表示只记录异常，不进入冷却。",
                min = 0,
                numberKind = ConfigNumberKind.INTEGER,
            ),
        ),
    )

    public fun validate(config: BilibiliPublisherConfig) {
        require(config.pollingIntervalSeconds >= 1.0) { "轮询间隔不能小于 1 秒" }
        require(config.requestIntervalSeconds >= 0.0) { "请求间隔不能为负数" }
        require(config.replayWindowMinutes >= 0) { "补发时间窗口不能为负数" }
        require(config.shortUrlResolveTimeoutSeconds > 0.0) { "短链接解析超时必须大于 0 秒" }
        require(config.liveStatusBatchSize >= 1) { "直播状态批量查询数量不能小于 1" }
        require(config.maxConsecutiveLoginFailures >= 0) { "未登录熔断次数不能为负数" }
        require(config.requestBlockCooldownMinutes >= 0) { "风控暂停时长不能为负数" }
    }
}
