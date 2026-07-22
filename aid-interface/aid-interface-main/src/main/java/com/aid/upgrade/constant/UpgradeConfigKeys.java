package com.aid.upgrade.constant;

/**
 * 系统升级与官方网关的 aid_config 配置键常量
 *
 * @author 视觉AID
 */
public final class UpgradeConfigKeys {

    /** 系统升级配置分类 */
    public static final String CATEGORY_SYSTEM_UPGRADE = "system_upgrade";

    /** 版本更新清单地址（发布方维护，页面只读） */
    public static final String KEY_MANIFEST_URL = "manifest_url";

    /** 版本更新清单默认地址：配置缺失时兜底，保证开箱即用无需手工配置 */
    public static final String DEFAULT_MANIFEST_URL =
            "https://gitee.com/gzxx-2025/aid-server/raw/master/release/latest.json";

    /** 使用教程默认地址：清单未下发时兜底 */
    public static final String DEFAULT_DOCS_URL =
            "https://gitee.com/gzxx-2025/aid-server#%E5%BF%AB%E9%80%9F%E5%BC%80%E5%A7%8B";

    /** 提示词开发教程默认地址：清单未下发时兜底 */
    public static final String DEFAULT_PROMPT_DOCS_URL =
            "https://gitee.com/gzxx-2025/aid-server/blob/master/doc/%E6%8F%90%E7%A4%BA%E8%AF%8D%E5%BC%80%E5%8F%91%E6%8C%87%E5%8D%97.md";

    /** 升级器下载地址（页面只读） */
    public static final String KEY_UPDATER_DOWNLOAD_URL = "updater_download_url";

    /** 升级器下载默认地址：配置缺失时兜底 */
    public static final String DEFAULT_UPDATER_DOWNLOAD_URL =
            "https://gitee.com/gzxx-2025/aid-server/releases";

    /** 升级器健康文件路径（自动维护项） */
    public static final String KEY_UPDATER_HEALTH_FILE = "updater_health_file";

    /** 升级器健康文件默认路径：与部署脚本自动安装的升级器约定一致，配置缺失时兜底 */
    public static final String DEFAULT_UPDATER_HEALTH_FILE = "/var/lib/aid-updater/health.json";

    /** 升级器任务文件路径（后端原子写入，升级器负责消费，自动维护项） */
    public static final String KEY_UPDATER_TASK_FILE = "updater_task_file";

    /** 升级器任务文件默认路径：与部署脚本自动安装的升级器约定一致，配置缺失时兜底 */
    public static final String DEFAULT_UPDATER_TASK_FILE = "/var/lib/aid-updater/inbox/task.json";

    /** 接收版本渠道：stable=仅正式版（默认），all=正式版+测试版都接收（取版本更高者提示） */
    public static final String KEY_RELEASE_CHANNEL = "release_channel";

    /** 渠道取值：仅正式版 */
    public static final String CHANNEL_STABLE = "stable";

    /** 渠道取值：正式版+测试版 */
    public static final String CHANNEL_ALL = "all";

    /** 正式清单文件名（测试清单与其同目录发布，文件名为 latest-beta.json） */
    public static final String STABLE_MANIFEST_FILE = "latest.json";

    /** 测试清单文件名 */
    public static final String BETA_MANIFEST_FILE = "latest-beta.json";

    /** 升级前自动备份的保留份数（随任务下发给升级器） */
    public static final String KEY_KEEP_BACKUPS = "keep_backups";

    /** 备份保留份数默认值 */
    public static final int DEFAULT_KEEP_BACKUPS = 3;

    /** 备份保留份数下限 */
    public static final int MIN_KEEP_BACKUPS = 1;

    /** 备份保留份数上限 */
    public static final int MAX_KEEP_BACKUPS = 50;

    /** 官方统一网关配置分类 */
    public static final String CATEGORY_OFFICIAL_GATEWAY = "official_gateway";

    /** 官方网关总开关 */
    public static final String KEY_GATEWAY_ENABLED = "enabled";

    /** 官方网关基础地址 */
    public static final String KEY_GATEWAY_BASE_URL = "base_url";

    /** 官方网关统一密钥 */
    public static final String KEY_GATEWAY_API_KEY = "api_key";

    /** 官方网关例外模型ID列表（逗号分隔，例外模型仍走自有厂商网关） */
    public static final String KEY_GATEWAY_EXCLUDED_MODEL_IDS = "excluded_model_ids";

    /** 官方网关例外厂商ID列表（逗号分隔，例外厂商下全部模型仍走自有厂商网关） */
    public static final String KEY_GATEWAY_EXCLUDED_PROVIDER_IDS = "excluded_provider_ids";

    private UpgradeConfigKeys() {
    }
}
