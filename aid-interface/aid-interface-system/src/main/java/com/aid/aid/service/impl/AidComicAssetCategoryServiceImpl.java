package com.aid.aid.service.impl;

import com.aid.aid.domain.AidComicAssetCategory;
import com.aid.aid.mapper.AidComicAssetCategoryMapper;
import com.aid.aid.service.IAidComicAssetCategoryService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 官方风格分类关系Service实现。
 *
 * @author 视觉AID
 */
@Service
public class AidComicAssetCategoryServiceImpl
        extends ServiceImpl<AidComicAssetCategoryMapper, AidComicAssetCategory>
        implements IAidComicAssetCategoryService {
}
