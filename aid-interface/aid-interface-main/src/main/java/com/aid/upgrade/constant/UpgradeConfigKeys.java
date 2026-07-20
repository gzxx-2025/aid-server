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

    /** 升级器下载地址（页面只读） */
    public static final String KEY_UPDATER_DOWNLOAD_URL = "updater_download_url";

    /** 升级器健康文件路径（为空视为未安装） */
    public static final String KEY_UPDATER_HEALTH_FILE = "updater_health_file";

    /** 升级器任务文件路径（后端原子写入，升级器负责消费） */
    public static final String KEY_UPDATER_TASK_FILE = "updater_task_file";

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
