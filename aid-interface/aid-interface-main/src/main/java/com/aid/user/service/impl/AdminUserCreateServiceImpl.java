package com.aid.user.service.impl;

import java.security.SecureRandom;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aid.auth.util.SilentRegistrationUtils;
import com.aid.common.constant.AuthConstants;
import com.aid.common.constant.UserConstants;
import com.aid.common.core.domain.entity.SysUser;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.SecurityUtils;
import com.aid.core.service.ISysUserService;
import com.aid.user.dto.AdminUserCreateRequest;
import com.aid.user.service.IAdminUserCreateService;
import com.aid.user.vo.AdminUserCreateVO;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 后台 C 端用户创建服务实现。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class AdminUserCreateServiceImpl implements IAdminUserCreateService {

    private static final String ACCOUNT_TYPE_PHONE = "phone";

    private static final String ACCOUNT_TYPE_EMAIL = "email";

    private static final String DEFAULT_STATUS = "0";

    private static final String DEFAULT_SEX = "2";

    private static final String DEFAULT_OPERATOR = "system";

    private static final int PASSWORD_LENGTH = 12;

    private static final int USERNAME_GENERATE_ATTEMPTS = 5;

    private static final char[] UPPERCASE = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private static final char[] LOWERCASE = "abcdefghijkmnopqrstuvwxyz".toCharArray();

    private static final char[] DIGITS = "23456789".toCharArray();

    private static final char[] PASSWORD_CHARACTERS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Resource
    private ISysUserService userService;

    /**
     * 创建 C 端用户并返回一次性初始密码。
     *
     * @param request 创建请求
     * @param operator 管理员账号
     * @return 创建结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminUserCreateVO createUser(AdminUserCreateRequest request, String operator) {
        if (Objects.isNull(request)) {
            log.info("后台新增用户失败：请求为空");
            throw new ServiceException("参数不能为空");
        }

        String email = normalizeEmail(request.getEmail());
        String phonenumber = StrUtil.trim(request.getPhonenumber());
        boolean hasEmail = StrUtil.isNotBlank(email);
        boolean hasPhone = StrUtil.isNotBlank(phonenumber);
        if (Objects.equals(hasEmail, hasPhone)) {
            log.info("后台新增用户失败：邮箱和手机号未二选一");
            throw new ServiceException("联系方式二选一");
        }
        validateContact(email, phonenumber, hasEmail);

        SysUser user = new SysUser();
        user.setUserName(generateUniqueUserName());
        user.setNickName(SilentRegistrationUtils.generateNickname());
        user.setEmail(hasEmail ? email : null);
        user.setPhonenumber(hasPhone ? phonenumber : null);
        user.setSex(DEFAULT_SEX);
        user.setStatus(DEFAULT_STATUS);
        user.setDelFlag(DEFAULT_STATUS);
        user.setDeptId(SilentRegistrationUtils.DEFAULT_DEPT_ID);
        user.setRoleIds(new Long[]{SilentRegistrationUtils.DEFAULT_ROLE_ID});
        user.setCreateBy(StrUtil.isBlank(operator) ? DEFAULT_OPERATOR : operator);
        user.setCreateTime(new Date());
        user.setPwdUpdateDate(new Date());

        String initialPassword = generateInitialPassword();
        user.setPassword(SecurityUtils.encryptPassword(initialPassword));

        try {
            int rows = userService.insertUser(user);
            if (rows <= 0 || Objects.isNull(user.getUserId())) {
                log.error("后台新增用户失败：用户未写入");
                throw new ServiceException("添加用户失败");
            }
        } catch (DuplicateKeyException e) {
            log.error("后台新增用户失败：联系方式重复, accountType={}",
                    hasEmail ? ACCOUNT_TYPE_EMAIL : ACCOUNT_TYPE_PHONE, e);
            throw new ServiceException("账号已存在");
        }

        String account = hasEmail ? email : phonenumber;
        String accountType = hasEmail ? ACCOUNT_TYPE_EMAIL : ACCOUNT_TYPE_PHONE;
        log.info("后台新增用户成功: userId={}, accountType={}, operator={}",
                user.getUserId(), accountType, user.getCreateBy());
        return AdminUserCreateVO.builder()
                .userId(user.getUserId())
                .account(account)
                .accountType(accountType)
                .password(initialPassword)
                .build();
    }

    /**
     * 校验联系方式格式和唯一性。
     */
    private void validateContact(String email, String phonenumber, boolean hasEmail) {
        SysUser candidate = new SysUser();
        if (hasEmail) {
            if (email.length() > UserConstants.LOGIN_ACCOUNT_MAX_LENGTH
                    || !email.matches(AuthConstants.EMAIL_REGEX)) {
                log.info("后台新增用户失败：邮箱格式错误");
                throw new ServiceException("邮箱格式错误");
            }
            candidate.setEmail(email);
            if (!userService.checkEmailUnique(candidate)) {
                log.info("后台新增用户失败：邮箱已存在");
                throw new ServiceException("邮箱已存在");
            }
            return;
        }

        if (!phonenumber.matches(AuthConstants.PHONE_REGEX)) {
            log.info("后台新增用户失败：手机号格式错误");
            throw new ServiceException("手机号格式错误");
        }
        candidate.setPhonenumber(phonenumber);
        if (!userService.checkPhoneUnique(candidate)) {
            log.info("后台新增用户失败：手机号已存在");
            throw new ServiceException("手机号已存在");
        }
    }

    /**
     * 生成未被占用的内部用户名。
     */
    private String generateUniqueUserName() {
        for (int attempt = 0; attempt < USERNAME_GENERATE_ATTEMPTS; attempt++) {
            String userName = SilentRegistrationUtils.generateUserName();
            SysUser candidate = new SysUser();
            candidate.setUserName(userName);
            if (userService.checkUserNameUnique(candidate)) {
                return userName;
            }
        }
        log.error("后台新增用户失败：内部账号生成冲突");
        throw new ServiceException("账号生成失败");
    }

    /**
     * 生成包含大小写字母和数字的初始密码。
     */
    private String generateInitialPassword() {
        char[] password = new char[PASSWORD_LENGTH];
        password[0] = randomCharacter(UPPERCASE);
        password[1] = randomCharacter(LOWERCASE);
        password[2] = randomCharacter(DIGITS);
        for (int index = 3; index < PASSWORD_LENGTH; index++) {
            password[index] = randomCharacter(PASSWORD_CHARACTERS);
        }
        for (int index = password.length - 1; index > 0; index--) {
            int swapIndex = SECURE_RANDOM.nextInt(index + 1);
            char current = password[index];
            password[index] = password[swapIndex];
            password[swapIndex] = current;
        }
        return new String(password);
    }

    /**
     * 从指定字符集中安全随机取一个字符。
     */
    private char randomCharacter(char[] characters) {
        return characters[SECURE_RANDOM.nextInt(characters.length)];
    }

    /**
     * 统一清洗邮箱大小写和首尾空格。
     */
    private String normalizeEmail(String email) {
        return StrUtil.isBlank(email) ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
