package com.aid.compose;

import java.util.Set;

import cn.hutool.core.util.StrUtil;

/**
 * 视频合成常量。
 *
 * @author 视觉AID
 */
public final class ComposeConstants {

    private ComposeConstants() {
    }

    /** 合法输出分辨率档（大小写不敏感，空值走默认档） */
    public static final Set<String> VALID_RESOLUTIONS = Set.of("SD", "HD", "FHD", "2K", "4K");

    /**
     * 校验输出分辨率档是否合法：空值合法（走默认 FHD），非空必须命中合法档位（大小写不敏感）。
     *
     * @param resolution 分辨率档入参
     * @return true=合法
     */
    public static boolean isValidResolution(String resolution) {
        return StrUtil.isBlank(resolution) || VALID_RESOLUTIONS.contains(resolution.trim().toUpperCase());
    }

    /** 媒体类型：合成任务 */
    public static final String MEDIA_TYPE_COMPOSE = "COMPOSE";

    /** 协议标识：按 protocol 路由到 MPS Provider */
    public static final String PROTOCOL_MPS = "tencent-mps";

    /** 业务回填目标：接口1 成片落 aid_gen_record */
    public static final String CALLBACK_GEN_RECORD = "gen_record";

    /** 业务回填目标：接口2 成片回写 aid_episode_editor */
    public static final String CALLBACK_EPISODE_EDITOR = "episode_editor";

    /** aid_gen_record.gen_type 合成成片取值 */
    public static final String GEN_TYPE_COMPOSE = "compose";

    /** aid_episode_editor.export_status：合成中 */
    public static final int EXPORT_STATUS_COMPOSING = 1;

    /** aid_episode_editor.export_status：导出成功 */
    public static final int EXPORT_STATUS_SUCCESS = 2;

    /** aid_episode_editor.export_status：导出失败 */
    public static final int EXPORT_STATUS_FAILED = 3;

    /** aid_gen_record.status：成功 */
    public static final int GEN_STATUS_SUCCESS = 1;

    /** aid_media_task.biz_task_type：配音记录（接口1 一键配音的业务任务类型） */
    public static final String BIZ_TASK_TYPE_AUDIO_RECORD = "audio_record";

    /** 段对齐策略：以配音为准（段长取视频/配音较长者，视频不足自动拉伸填满） */
    public static final String ALIGN_STRATEGY_AUDIO = "AUDIO";

    /** 素材标签：视频（错误文案前缀，如「视频素材异常」） */
    public static final String MATERIAL_LABEL_VIDEO = "视频素材";

    /** 素材标签：配音（错误文案前缀，如「配音素材异常」） */
    public static final String MATERIAL_LABEL_AUDIO = "配音素材";

    /** 素材标签：背景音乐（错误文案前缀，如「背景音乐异常」） */
    public static final String MATERIAL_LABEL_BGM = "背景音乐";
}
