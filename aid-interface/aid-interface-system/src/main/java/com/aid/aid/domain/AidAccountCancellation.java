package com.aid.aid.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.Date;

/**
 * 账号注销身份记录。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_account_cancellation")
public class AidAccountCancellation extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String identityType;

    private String identityHash;

    private Date cancelledAt;
}
