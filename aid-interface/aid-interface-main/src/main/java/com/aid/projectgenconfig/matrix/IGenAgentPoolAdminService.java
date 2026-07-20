package com.aid.projectgenconfig.matrix;

import java.util.List;
import com.aid.projectgenconfig.matrix.dto.GenPoolSaveCellRequest;
import com.aid.projectgenconfig.matrix.vo.GenPoolCellVO;
import com.aid.projectgenconfig.matrix.vo.GenPoolOptionsVO;

/**
 * 智能体矩阵后台配置业务Service（供管理端可视化矩阵页面使用）。
 *
 * @author 视觉AID
 */
public interface IGenAgentPoolAdminService {

    /**
     * 查询某业务场景(biz)下可选的智能体与模型（供下拉）。
     *
     * @param bizCategoryCode 业务场景编码
     * @return 智能体 + 模型选项
     */
    GenPoolOptionsVO getOptions(String bizCategoryCode);

    /**
     * 查询矩阵（按格子聚合）。
     *
     * @param step 步骤（可空=全部）
     * @return 格子列表
     */
    List<GenPoolCellVO> listMatrix(String step);

    /**
     * 覆盖式保存一个格子（先软删该组合现有行，再插入默认+候选行）。
     *
     * @param request  保存请求
     * @param operator 操作人
     */
    void saveCell(GenPoolSaveCellRequest request, String operator);

    /**
     * 删除一个格子（软删该组合下全部行）。
     *
     * @param step            步骤
     * @param bizCategoryCode 业务场景
     * @param creationMode    创作模式
     * @param scriptType      剧本类型
     * @param operator        操作人
     */
    void deleteCell(String step, String bizCategoryCode, String creationMode, String scriptType, String operator);
}
