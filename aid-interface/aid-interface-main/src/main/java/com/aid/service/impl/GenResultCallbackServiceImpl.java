package com.aid.service.impl;

import com.aid.aid.domain.AidComicAsset;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.service.IAidComicAssetService;
import com.aid.aid.service.IAidGenRecordService;
import com.aid.common.core.domain.entity.SysUser;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.StringUtils;
import com.aid.core.service.ISysUserService;
import com.aid.domain.dto.GenResultCallbackDTO;
import com.aid.enums.GenResultTargetEnum;
import com.aid.enums.MediaTypeEnum;
import com.aid.service.IGenResultCallbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 大模型生成结果回调 Service 实现
 *
 * @author 视觉AID
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class GenResultCallbackServiceImpl implements IGenResultCallbackService {

    private final IAidComicAssetService aidComicAssetService;
    private final IAidGenRecordService aidGenRecordService;
    private final ISysUserService sysUserService;

    /**
     * 处理大模型生成结果回调（完整校验链路，供定时任务调用）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean handleGenResult(GenResultCallbackDTO dto) {
        validateRequired(dto);

        GenResultTargetEnum targetEnum = GenResultTargetEnum.getByValue(dto.getTarget());
        if (targetEnum == null) {
            throw new IllegalArgumentException("非法的 target 值: " + dto.getTarget()
                    + "，合法值: asset / gen_record");
        }

        MediaTypeEnum mediaTypeEnum = MediaTypeEnum.getByValue(dto.getMediaType());
        if (mediaTypeEnum == null) {
            throw new IllegalArgumentException("非法的 mediaType 值: " + dto.getMediaType()
                    + "，合法值: image / video");
        }

        return fillResultUrl(dto.getRecordId(), dto.getFileUrl(), dto.getTarget());
    }

    /**
     * 根据记录ID和分类回填生成的文件URL
     *
     * @param recordId 记录主键（aid_comic_asset.id 或 aid_gen_record.id）
     * @param fileUrl  生成的图片/视频URL
     * @param category 分类：asset（资产表）/ gen_record（抽卡记录表）
     * @return true=回填成功, false=记录不存在
     */
    @Override
    public boolean fillResultUrl(Long recordId, String fileUrl, String category) {
        GenResultTargetEnum targetEnum = GenResultTargetEnum.getByValue(category);
        if (targetEnum == null) {
            throw new IllegalArgumentException("非法的 category 值: " + category
                    + "，合法值: asset / gen_record");
        }
        return switch (targetEnum) {
            case ASSET -> updateAssetUrl(recordId, fileUrl);
            case GEN_RECORD -> updateGenRecordUrl(recordId, fileUrl);
        };
    }
    private boolean updateAssetUrl(Long recordId, String fileUrl) {
        AidComicAsset asset = aidComicAssetService.selectAidComicAssetById(recordId);
        if (asset == null) {
            log.error("资产记录不存在, recordId={}", recordId);
            return false;
        }
        AidComicAsset update = new AidComicAsset();
        update.setId(recordId);
        update.setImageUrl(fileUrl);
        update.setUpdateTime(DateUtils.getNowDate());
        boolean result = aidComicAssetService.updateAidComicAsset(update) > 0;
        log.info("资产回填完成, recordId={}, 更新结果={}", recordId, result);
        return result;
    }
    private boolean updateGenRecordUrl(Long recordId, String fileUrl) {
        AidGenRecord record = aidGenRecordService.selectAidGenRecordById(recordId);
        if (record == null) {
            log.error("生成记录不存在, recordId={}", recordId);
            return false;
        }
        record.setFileUrl(fileUrl);
        record.setStatus(1); // 成功
        record.setUpdateTime(DateUtils.getNowDate());
        boolean result = aidGenRecordService.updateById(record);
        log.info("生成记录回填完成, recordId={}, 更新结果={}", recordId, result);
        return result;
    }
    private void validateRequired(GenResultCallbackDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("回调参数不能为空");
        }
        if (dto.getRecordId() == null) {
            throw new IllegalArgumentException("recordId 不能为空");
        }
        if (dto.getUserId() == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        SysUser user = sysUserService.selectUserById(dto.getUserId());
        if (user == null) {
            log.error("用户不存在, userId={}", dto.getUserId());
            throw new IllegalArgumentException("用户不存在, userId=" + dto.getUserId());
        }
        if (StringUtils.isEmpty(dto.getTarget())) {
            throw new IllegalArgumentException("target 不能为空");
        }
        if (StringUtils.isEmpty(dto.getMediaType())) {
            throw new IllegalArgumentException("mediaType 不能为空");
        }
        if (StringUtils.isEmpty(dto.getFileUrl())) {
            throw new IllegalArgumentException("fileUrl 不能为空");
        }
    }
}
