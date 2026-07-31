package com.aid.aid.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.aid.aid.domain.AidProjectGenConfig;
import org.apache.ibatis.annotations.Param;

/**
 * 项目级生成配置 Mapper
 *
 * @author 视觉AID
 */
public interface AidProjectGenConfigMapper extends BaseMapper<AidProjectGenConfig>
{
    /**
     * 按项目、用户、场景唯一键原子保存配置。
     * 首次保存时新增，唯一键已存在时更新原记录。
     *
     * @param config 项目级生成配置
     * @return 受影响行数
     */
    int upsertByUniqueKey(@Param("config") AidProjectGenConfig config);
}
