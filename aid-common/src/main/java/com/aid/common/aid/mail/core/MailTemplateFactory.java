package com.aid.common.aid.mail.core;

import com.aid.common.aid.mail.config.MailConfigManager;
import com.aid.common.aid.mail.utils.MailAccount;
import com.aid.common.aid.mail.utils.MailException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Collection;
import java.util.Map;

/**
 * 邮箱模板工厂
 * - 配置通过 MailConfigManager 管理，手动刷新
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailTemplateFactory {

    private final MailConfigManager mailConfigManager;

    /**
     * 获取邮箱账户
     */
    public MailAccount getMailAccount() {
        if (!mailConfigManager.isEnabled()) {
            throw new MailException("邮箱服务未启用");
        }
        return mailConfigManager.getMailAccount();
    }

    /**
     * 刷新配置（配置更新后调用）
     */
    public void refresh() {
        mailConfigManager.refresh();
        log.info("邮箱配置已刷新");
    }

    /**
     * 获取当前配置信息（供前端展示）
     */
    public Map<String, String> getCurrentConfig() {
        return mailConfigManager.getCurrentConfig();
    }
    /**
     * 发送文本邮件
     *
     * @param to      收件人
     * @param subject 标题
     * @param content 正文
     * @return message-id
     */
    public String sendText(String to, String subject, String content) {
        return send(to, subject, content, false);
    }

    /**
     * 发送HTML邮件
     *
     * @param to      收件人
     * @param subject 标题
     * @param content 正文
     * @return message-id
     */
    public String sendHtml(String to, String subject, String content) {
        return send(to, subject, content, true);
    }

    /**
     * 发送邮件。
     *
     * @param to      收件人
     * @param subject 标题
     * @param content 正文
     * @param isHtml  是否为HTML
     * @return message-id
     */
    public String send(String to, String subject, String content, boolean isHtml) {
        return MailUtils.send(getMailAccount(), to, subject, content, isHtml);
    }

    /**
     * 发送邮件（带附件）。
     *
     * @param to      收件人
     * @param subject 标题
     * @param content 正文
     * @param isHtml  是否为HTML
     * @param files   附件列表
     * @return message-id
     */
    public String send(String to, String subject, String content, boolean isHtml, File... files) {
        return MailUtils.send(getMailAccount(), to, subject, content, isHtml, files);
    }

    /**
     * 发送邮件给多人。
     *
     * @param tos     收件人列表
     * @param subject 标题
     * @param content 正文
     * @param isHtml  是否为HTML
     * @return message-id
     */
    public String send(Collection<String> tos, String subject, String content, boolean isHtml) {
        return MailUtils.send(getMailAccount(), tos, subject, content, isHtml);
    }

    // 内部工具类，复用原有发送逻辑
    private static class MailUtils {
        static String send(MailAccount account, String to, String subject, String content, boolean isHtml, File... files) {
            return com.aid.common.aid.mail.utils.MailUtils.send(account, to, subject, content, isHtml, files);
        }

        static String send(MailAccount account, Collection<String> tos, String subject, String content, boolean isHtml) {
            return com.aid.common.aid.mail.utils.MailUtils.send(account, tos, subject, content, isHtml);
        }
    }
}
