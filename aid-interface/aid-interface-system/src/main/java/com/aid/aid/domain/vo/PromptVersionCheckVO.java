package com.aid.aid.domain.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 提示词版本检查结果VO
 *
 * @author 视觉AID
 */
@Data
@Builder
public class PromptVersionCheckVO {

    /** 文件标识（remark） */
    private String remark;

    /** 远程最新版本号 */
    private Integer latestVersion;

    /** 本地当前版本号 */
    private Integer currentVersion;

    /** 状态：latest=最新, need_update=需要更新, version_error=版本错误 */
    private String status;
}
