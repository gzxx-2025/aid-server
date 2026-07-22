-- ==========================================================================
-- sys_user：手机号/邮箱未绑定时统一为 NULL（幂等，可反复执行）
--
-- 背景：uk_sys_user_phonenumber / uk_sys_user_email 唯一索引保留，用于并发绑号兜底。
--       历史默认值为空串 ''，唯一索引不允许出现多个空串，导致邮箱/微信静默注册
--       （不写手机号）第二次起插入失败：Duplicate entry '' for key 'uk_sys_user_phonenumber'。
-- 约定：未绑定存 NULL（唯一索引允许多个 NULL）；有值则全局唯一。
-- ==========================================================================

-- 1. 历史空串清洗为 NULL
UPDATE sys_user SET phonenumber = NULL WHERE phonenumber = '';
UPDATE sys_user SET email = NULL WHERE email = '';

-- 2. 默认值改为 NULL，避免 INSERT 省略列时再落成 ''
ALTER TABLE sys_user
  MODIFY COLUMN phonenumber varchar(100) DEFAULT NULL COMMENT '手机号码',
  MODIFY COLUMN email varchar(100) DEFAULT NULL COMMENT '用户邮箱';
