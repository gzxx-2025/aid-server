package com.aid.projectgenconfig.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

import com.aid.agent.vo.AgentInfoVO;
import com.aid.model.vo.AiModelVO;

/**
 * 项目级生成配置返回 VO（单个场景）。
 * 查询时按场景返回；{@code source} 标识取值来源：
 * {@code project}=取自项目配置表；{@code default}=回退智能体矩阵默认；{@code none}=均未配置。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class ProjectGenConfigVO
{
    /** 场景编码 */
    private String sceneCode;

    /** 智能体编码 */
    private String agentCode;

    /** 模型编码 */
    private String modelCode;

    /** 清晰度/分辨率档位（图片类场景有值） */
    private String resolution;

    /** 图片比例（分镜生图场景有值） */
    private String aspectRatio;

    /** 取值来源：project / default / none */
    private String source;

    /**
     * 模式：仅当 source=default 时有值（economy 经济 / performance 性能），
     * 表示该兜底默认取自项目当前生成模式对应的那一套。
     */
    private String mode;

    /**
     * 该场景的可选模型池列表（含每个模型的 capability 信息）。
     */
    private List<AiModelVO> availableModels;

    /**
     * 该场景在当前(创作模式 × 剧本类型)下的可选智能体池（agentCode 列表）。
     * 来源：{@code aid_gen_agent_pool}。仅分镜脚本/分镜图提示词/分镜视频提示词等
     * 受创作模式影响的场景有值；用于前端下拉与生成时的入参校验（防越权选错）。
     * 为空表示该场景在当前创作模式下不适用或未配置池。
     */
    private List<String> agentPool;

    /**
     * 该场景的可选智能体下拉项（含名称，已按当前创作模式过滤）。
     * 由 {@link #agentPool} 的 agentCode 关联 {@code aid_agent} 取名称组装而成，顺序与 {@code agentPool} 一致。
     * 前端**应直接用本字段渲染该场景的智能体下拉**，不要再用「按业务分类列全部智能体」的通用接口，
     * 否则会把当前创作模式下不该出现的智能体（如 i2v 下的「分镜脚本提取-专业版」）也列出来。
     * 每项为 {@link AgentInfoVO}（已屏蔽 prompt_content）。
     */
    private List<AgentInfoVO> agentOptions;

    /**
     * 该场景是否适用于当前(创作模式 × 剧本类型)。
     * 资产类场景（角色/场景/道具 提取·形态·生图、角色卡、分镜生图）通配所有创作模式，恒为 {@code true}；
     * 分镜视频提示词等创作模式专属场景：仅当矩阵在当前创作模式下有配置时为 {@code true}。
     * 例如 i2v 项目下，{@code main_storyboard_video_prompt}(多参/专业)、
     * {@code main_storyboard_video_prompt_grid}(宫格) 不适用 → {@code false}，
     * 前端可据此隐藏/置灰，避免展示空壳场景。
     */
    private Boolean applicable;
}
