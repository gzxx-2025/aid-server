package com.aid.compose.domain;

import lombok.Data;

/** 三种媒体处理引擎共用的合成计划。 */
@Data
public class ComposeJobPlan
{
    private Long taskId;
    private String resolution;
    private String codec;
    private String outputObjectPath;
    private ComposeTracks tracks;
    private ComposeStorageSnapshot storage;
}
