package com.aid.media.constants;

/**
 * 图片业务场景常量：厂商无关的业务语义抽象，由 provider 翻译为厂商协议字段。
 */
public final class MediaImageScenario {

    /** 角色设定卡（21:9 横版构图）。阿里万相 → size=2016*864；其他厂商 → 默认 aspect_ratio=21:9。 */
    public static final String CARD_IMAGE = "card_image";

    private MediaImageScenario() {
        // 常量类，禁止实例化
    }
}
