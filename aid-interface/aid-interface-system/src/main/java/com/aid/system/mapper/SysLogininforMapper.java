package com.aid.system.mapper;

import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.aid.core.domain.SysLogininfor;

/**
 * 系统访问日志情况信息 数据层
 *
 * @author 视觉AID
 */
public interface SysLogininforMapper
{
    /**
     * 新增系统登录日志
     *
     * @param logininfor 访问日志对象
     */
    public void insertLogininfor(SysLogininfor logininfor);

    /**
     * 按时间归档查询：查询早于 cutoff 的登录日志（按主键升序，限量），用于日志归档。
     *
     * @param cutoff 截止时间（早于此时间的将被归档）
     * @param limit  单批最大条数
     * @return 登录日志集合
     */
    public List<SysLogininfor> selectLogininforBeforeTime(@Param("cutoff") Date cutoff, @Param("limit") int limit);


    /**
     * 查询系统登录日志集合
     *
     * @param logininfor 访问日志对象
     * @return 登录记录集合
     */
    public List<SysLogininfor> selectLogininforList(SysLogininfor logininfor);

    /**
     * 批量删除系统登录日志
     *
     * @param infoIds 需要删除的登录日志ID
     * @return 结果
     */
    public int deleteLogininforByIds(Long[] infoIds);

    /**
     * 清空系统登录日志
     *
     * @return 结果
     */
    public int cleanLogininfor();
}
