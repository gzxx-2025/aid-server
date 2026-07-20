package com.aid.prompt.service.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.aid.aid.domain.AidPromptLib;
import com.aid.aid.service.IAidPromptLibService;
import com.aid.prompt.constant.OfficialPromptCategory;
import com.aid.prompt.dto.OfficialPromptItemDetailRequest;
import com.aid.prompt.dto.OfficialPromptItemListRequest;
import com.aid.prompt.service.IOfficialPromptService;
import com.aid.prompt.vo.OfficialPromptCategoryVO;
import com.aid.prompt.vo.OfficialPromptItemVO;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 官方只读参数词库 Service 实现
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class OfficialPromptServiceImpl implements IOfficialPromptService {

    /** 官方预设用户ID */
    private static final Long OFFICIAL_USER_ID = 0L;
    /** 状态：正常 */
    private static final String STATUS_NORMAL = "0";
    /** 删除标志：未删除 */
    private static final String DEL_FLAG_NORMAL = "0";

    @Resource
    private IAidPromptLibService aidPromptLibService;

    /**
     * 查询官方参数词库分类列表
     * 仅查询字段：prompt_type（用于统计 itemCount）
     */
    @Override
    public List<OfficialPromptCategoryVO> listCategories() {
        // 仅查询 prompt_type 字段，减少数据列返回
        LambdaQueryWrapper<AidPromptLib> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidPromptLib::getPromptType);
        wrapper.eq(AidPromptLib::getUserId, OFFICIAL_USER_ID);
        wrapper.eq(AidPromptLib::getStatus, STATUS_NORMAL);
        wrapper.eq(AidPromptLib::getDelFlag, DEL_FLAG_NORMAL);
        wrapper.in(AidPromptLib::getPromptType, OfficialPromptCategory.codes());

        List<AidPromptLib> list = aidPromptLibService.list(wrapper);
        // 按分类代码统计条数
        Map<String, Long> countMap = list.stream()
                .filter(p -> StrUtil.isNotBlank(p.getPromptType()))
                .collect(Collectors.groupingBy(AidPromptLib::getPromptType, Collectors.counting()));

        // 以白名单为准，按 sortOrder 升序输出，缺失分类 itemCount 为 0
        List<OfficialPromptCategoryVO> result = new ArrayList<>();
        for (OfficialPromptCategory.Definition def : OfficialPromptCategory.all()) {
            Long count = countMap.getOrDefault(def.getCode(), 0L);
            result.add(OfficialPromptCategoryVO.builder()
                    .categoryCode(def.getCode())
                    .categoryName(def.getName())
                    .itemCount(count.intValue())
                    .sortOrder(def.getSortOrder())
                    .build());
        }
        return result;
    }

    /**
     * 按分类分页查询官方参数词条列表
     * 仅查询必要字段：id, promptType, promptName, promptContent, promptContentEn, coverUrl, sortOrder, remark
     */
    @Override
    public IPage<OfficialPromptItemVO> listItems(OfficialPromptItemListRequest request) {
        if (Objects.isNull(request)) {
            log.error("官方词条列表查询入参为空");
            throw new RuntimeException("参数错误");
        }

        // 分页参数归一化：缺省第 1 页，每页 20 条，单页最大 50
        int pageNum = Objects.isNull(request.getPageNum()) || request.getPageNum() < 1 ? 1 : request.getPageNum();
        int pageSize = Objects.isNull(request.getPageSize()) || request.getPageSize() < 1
                ? 20 : Math.min(request.getPageSize(), 50);

        // 合并 categoryCode / categoryCodes，并强校验白名单
        Set<String> codes = resolveCategoryCodes(request.getCategoryCode(), request.getCategoryCodes());

        // 仅返回必要字段
        LambdaQueryWrapper<AidPromptLib> wrapper = Wrappers.lambdaQuery();
        wrapper.select(
                AidPromptLib::getId,
                AidPromptLib::getPromptType,
                AidPromptLib::getPromptName,
                AidPromptLib::getPromptContent,
                AidPromptLib::getPromptContentEn,
                AidPromptLib::getCoverUrl,
                AidPromptLib::getSortOrder,
                AidPromptLib::getRemark
        );
        wrapper.eq(AidPromptLib::getUserId, OFFICIAL_USER_ID);
        wrapper.eq(AidPromptLib::getStatus, STATUS_NORMAL);
        wrapper.eq(AidPromptLib::getDelFlag, DEL_FLAG_NORMAL);
        wrapper.in(AidPromptLib::getPromptType, codes);
        if (StrUtil.isNotBlank(request.getKeyword())) {
            wrapper.like(AidPromptLib::getPromptName, request.getKeyword());
        }
        wrapper.orderByAsc(AidPromptLib::getSortOrder);
        wrapper.orderByAsc(AidPromptLib::getId);

        Page<AidPromptLib> page = new Page<>(pageNum, pageSize);
        IPage<AidPromptLib> result = aidPromptLibService.page(page, wrapper);

        List<OfficialPromptItemVO> voList = result.getRecords().stream()
                .map(this::toItemVO).collect(Collectors.toList());

        Page<OfficialPromptItemVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(voList);
        return voPage;
    }

    /**
     * 查询单个官方参数词条详情
     * 仅返回必要字段
     */
    @Override
    public OfficialPromptItemVO getItemDetail(OfficialPromptItemDetailRequest request) {
        if (Objects.isNull(request) || Objects.isNull(request.getId())) {
            log.error("官方词条详情查询入参非法: {}", request);
            throw new RuntimeException("参数错误");
        }

        // 仅返回必要字段
        LambdaQueryWrapper<AidPromptLib> wrapper = Wrappers.lambdaQuery();
        wrapper.select(
                AidPromptLib::getId,
                AidPromptLib::getPromptType,
                AidPromptLib::getPromptName,
                AidPromptLib::getPromptContent,
                AidPromptLib::getPromptContentEn,
                AidPromptLib::getCoverUrl,
                AidPromptLib::getSortOrder,
                AidPromptLib::getRemark
        );
        wrapper.eq(AidPromptLib::getId, request.getId());
        wrapper.eq(AidPromptLib::getUserId, OFFICIAL_USER_ID);
        wrapper.eq(AidPromptLib::getStatus, STATUS_NORMAL);
        wrapper.eq(AidPromptLib::getDelFlag, DEL_FLAG_NORMAL);
        wrapper.last("LIMIT 1");

        AidPromptLib entity = aidPromptLibService.getOne(wrapper);
        if (Objects.isNull(entity)) {
            log.error("官方词条不存在, id={}", request.getId());
            throw new RuntimeException("词条不存在");
        }
        // 再次校验分类白名单（避免历史脏数据泄露）
        if (!OfficialPromptCategory.isAllowed(entity.getPromptType())) {
            log.error("官方词条分类非法, id={}, promptType={}", entity.getId(), entity.getPromptType());
            throw new RuntimeException("词条不存在");
        }
        return toItemVO(entity);
    }

    /**
     * 解析并校验分类代码（合并 single/multi，去重，全部白名单校验）
     */
    private Set<String> resolveCategoryCodes(String single, List<String> multi) {
        Set<String> merged = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(single)) {
            merged.add(single);
        }
        if (CollectionUtil.isNotEmpty(multi)) {
            for (String code : multi) {
                if (StrUtil.isNotBlank(code)) {
                    merged.add(code);
                }
            }
        }
        if (merged.isEmpty()) {
            log.error("官方词条列表查询未传分类: single={}, multi={}", single, multi);
            throw new RuntimeException("分类必传");
        }
        // 白名单校验
        Set<String> illegal = new HashSet<>();
        for (String code : merged) {
            if (!OfficialPromptCategory.isAllowed(code)) {
                illegal.add(code);
            }
        }
        if (!illegal.isEmpty()) {
            log.error("官方词条列表查询分类非法: {}", illegal);
            throw new RuntimeException("分类非法");
        }
        return merged;
    }

    /** 实体转 VO */
    private OfficialPromptItemVO toItemVO(AidPromptLib entity) {
        return OfficialPromptItemVO.builder()
                .id(entity.getId())
                .categoryCode(entity.getPromptType())
                .categoryName(OfficialPromptCategory.nameOf(entity.getPromptType()))
                .itemName(entity.getPromptName())
                .promptText(entity.getPromptContent())
                .promptTextEn(entity.getPromptContentEn())
                .coverUrl(entity.getCoverUrl())
                .sortOrder(entity.getSortOrder())
                .remark(entity.getRemark())
                .build();
    }
}
