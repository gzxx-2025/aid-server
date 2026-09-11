package com.aid.project.snapshot.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aid.aid.domain.AidAudioAsset;
import com.aid.aid.domain.AidAudioRecord;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicScript;
import com.aid.aid.domain.AidEpisodeEditor;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.domain.AidProjectGenConfig;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.domain.AidRolePropSceneFormImage;
import com.aid.aid.domain.AidRoleVoiceBinding;
import com.aid.aid.domain.AidScenePlot;
import com.aid.aid.domain.AidStoryboard;
import com.aid.rps.voice.vo.RoleVoiceBindingVO;

import lombok.Data;

/** 快照内部文档；接口层会转换为脱敏的节点/连线结构。 */
@Data
public class ProjectSnapshotDocument
{
    private Integer schemaVersion;
    private AidComicProject project;
    private List<AidComicEpisode> episodes = new ArrayList<>();
    private List<AidComicScript> scripts = new ArrayList<>();
    private List<AidRolePropScene> assets = new ArrayList<>();
    private List<AidRolePropSceneForm> forms = new ArrayList<>();
    private List<AidRolePropSceneFormImage> formImages = new ArrayList<>();
    private List<AidScenePlot> scenePlots = new ArrayList<>();
    private List<AidStoryboard> storyboards = new ArrayList<>();
    private List<AidGenRecord> generationRecords = new ArrayList<>();
    private List<AidAudioRecord> audioRecords = new ArrayList<>();
    /** 发布时冻结的对口型状态，避免预览阶段回查实时任务表。 */
    private Map<Long, String> audioLipSyncStatuses = new LinkedHashMap<>();
    private List<AidAudioAsset> audioAssets = new ArrayList<>();
    private List<AidRoleVoiceBinding> voiceBindings = new ArrayList<>();
    /** 发布时按正常角色资产接口批量固化的音色展示契约；旧快照为空时由绑定冗余字段安全降级。 */
    private List<RoleVoiceBindingVO> voiceBindingViews = new ArrayList<>();
    private List<AidProjectGenConfig> generationConfigs = new ArrayList<>();
    private List<AidEpisodeEditor> editors = new ArrayList<>();
}
