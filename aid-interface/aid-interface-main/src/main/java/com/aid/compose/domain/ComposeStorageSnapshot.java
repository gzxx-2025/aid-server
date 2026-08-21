package com.aid.compose.domain;

import lombok.Data;

/** 合成任务创建时的输出存储归属快照，不保存任何密钥。 */
@Data
public class ComposeStorageSnapshot
{
    private String mode;
    private String bucket;
    private String region;
    private String endpoint;
    private String prefix;
    private String resourceAccessDomain;
}
