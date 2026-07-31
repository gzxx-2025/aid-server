package com.aid.prompt.service.impl;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.aid.prompt.constant.EnumDictRegistry;
import com.aid.prompt.dto.EnumDictListRequest;
import com.aid.prompt.service.IEnumDictService;
import com.aid.prompt.vo.EnumDictGroupVO;
import com.aid.prompt.vo.EnumDictItemVO;
import com.aid.common.exception.ServiceException;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 枚举字典 Service 实现
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class EnumDictServiceImpl implements IEnumDictService {

    @Override
    public List<EnumDictGroupVO> listEnums(EnumDictListRequest request) {
        if (Objects.isNull(request) || CollectionUtil.isEmpty(request.getEnumTypes())) {
            log.error("枚举字典查询入参为空");
            throw new ServiceException("类型不能为空");
        }

        // 去重保留顺序
        Set<String> types = new LinkedHashSet<>();
        for (String t : request.getEnumTypes()) {
            if (StrUtil.isNotBlank(t)) {
                types.add(t);
            }
        }

        // 白名单严格校验（严格大小写）
        for (String type : types) {
            if (!EnumDictRegistry.contains(type)) {
                log.error("枚举字典类型非法: {}", type);
                throw new ServiceException("类型错误");
            }
        }

        List<EnumDictGroupVO> result = new ArrayList<>(types.size());
        for (String type : types) {
            EnumDictRegistry.Entry entry = EnumDictRegistry.get(type);
            result.add(EnumDictGroupVO.builder()
                    .enumType(type)
                    .items(toItems(entry.getClazz()))
                    .build());
        }
        return result;
    }

    /** 使用反射读取枚举的 value / desc 字段 */
    private List<EnumDictItemVO> toItems(Class<? extends Enum<?>> clazz) {
        Enum<?>[] values = clazz.getEnumConstants();
        List<EnumDictItemVO> items = new ArrayList<>(values.length);
        for (Enum<?> v : values) {
            try {
                Field valueField = clazz.getDeclaredField("value");
                valueField.setAccessible(true);
                Object valueObj = valueField.get(v);

                Field descField = clazz.getDeclaredField("desc");
                descField.setAccessible(true);
                Object descObj = descField.get(v);

                items.add(EnumDictItemVO.builder()
                        .value(valueObj == null ? null : String.valueOf(valueObj))
                        .desc(descObj == null ? null : String.valueOf(descObj))
                        .build());
            } catch (Exception e) {
                log.error("读取枚举字段失败: class={}, name={}", clazz.getSimpleName(), v.name(), e);
                throw new ServiceException("类型错误");
            }
        }
        return items;
    }
}
