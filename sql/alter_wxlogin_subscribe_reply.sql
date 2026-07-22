-- ==========================================================================
-- 微信公众号配置：新增「关注后自动回复内容」配置项（幂等，可反复执行）
-- 适用库：aid（正式）/ aid_test（测试），执行前先 USE 对应库
-- 配置位：aid_config category=wxLogin / config_name=wxLoginSubscribeReply
-- 用途：用户关注公众号（subscribe 事件）后，回调接口按此内容被动回复文本消息；
--       留空则不回复。修改后需在后台「微信公众号配置」点击「同步配置」生效。
-- ==========================================================================

-- 1. 若该配置行曾被软删，先复活（避免后台占位保存时撞 (category, config_name) 唯一索引）
UPDATE aid_config
SET del_flag = '0', update_time = NOW(), update_by = 'admin'
WHERE category = 'wxLogin' AND config_name = 'wxLoginSubscribeReply' AND del_flag = '1';

-- 2. 幂等插入配置行（已存在则跳过）
INSERT INTO aid_config
    (category, config_name, config_value, config_dict, del_flag, order_num, create_time, create_by, remark)
SELECT 'wxLogin', 'wxLoginSubscribeReply', '感谢关注！', '关注后自动回复内容', '0', 6, NOW(), 'admin', '用户关注公众号后自动回复的文本内容，留空不回复'
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM aid_config
    WHERE category = 'wxLogin' AND config_name = 'wxLoginSubscribeReply'
);
