package com.aid.compose.domain;

import lombok.Data;

/**
 * 合成素材文件项（映射 MPS FileInfos[]，InputInfo.Type=URL）。
 *
 * @author 视觉AID
 */
@Data
public class ComposeFileInfo {

    /** 内部生成的文件ID（供轨道 Item 的 SourceMedia.FileId 引用） */
    private String fileId;

    /** 规范化后的素材 URL（喂给 MPS UrlInputInfo.Url） */
    private String url;
}
