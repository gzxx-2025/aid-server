package com.aid.tokendance.mapper;

import com.aid.tokendance.domain.TokenDanceCredential;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

/** TokenDance 凭证 Mapper。 */
public interface TokenDanceCredentialMapper extends BaseMapper<TokenDanceCredential> {
    @Select("SELECT id FROM aid_ai_provider WHERE id = #{providerId} FOR UPDATE")
    Long lockProvider(@Param("providerId") Long providerId);

    @Select("SELECT COALESCE(MAX(credential_version), 0) FROM aid_tokendance_credential WHERE provider_id = #{providerId}")
    Integer selectMaxVersion(@Param("providerId") Long providerId);

    /** OAuth 活跃 Key 同步到通用供应商配置，保证统一探活和后台配置状态使用同一凭证。 */
    @Update("UPDATE aid_ai_provider SET api_key = #{apiKey}, update_by = #{username}, update_time = #{updateTime} "
            + "WHERE id = #{providerId} AND LOWER(TRIM(provider_code)) = 'tokendance'")
    int syncProviderApiKey(@Param("providerId") Long providerId,
                           @Param("apiKey") String apiKey,
                           @Param("username") String username,
                           @Param("updateTime") Date updateTime);

    /** 撤销 TokenDance OAuth 凭证时清空通用供应商镜像，避免探活继续使用已撤销 Key。 */
    @Update("UPDATE aid_ai_provider SET api_key = '', update_by = #{username}, update_time = #{updateTime} "
            + "WHERE id = #{providerId} AND LOWER(TRIM(provider_code)) = 'tokendance'")
    int clearProviderApiKey(@Param("providerId") Long providerId,
                            @Param("username") String username,
                            @Param("updateTime") Date updateTime);
}
