package com.aid.aid.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.aid.aid.domain.AidPublishWhitelist;
import com.aid.aid.domain.vo.AidPublishUserVo;
import com.aid.aid.domain.vo.AidPublishWhitelistVo;

/**
 * 作品发布白名单Mapper接口
 *
 * @author 视觉AID
 */
public interface AidPublishWhitelistMapper extends BaseMapper<AidPublishWhitelist>
{
    /**
     * 查询白名单列表（联表用户信息，keyword 匹配昵称/邮箱/手机号）
     *
     * @param keyword 搜索关键字（可空）
     * @return 白名单列表
     */
    List<AidPublishWhitelistVo> selectWhitelistVoList(@Param("keyword") String keyword);

    /**
     * 按 昵称/邮箱/手机号 搜索用户（带发布权限与白名单标记，最多50条）
     *
     * @param keyword 搜索关键字
     * @return 用户列表
     */
    List<AidPublishUserVo> searchPublishUsers(@Param("keyword") String keyword);
}
