package com.aid.common.aid.sms.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmsBaoResponseCodesTest {

    @Test
    void shouldReadStatusFromFirstLineForEveryLineEnding() {
        assertEquals("0", SmsBaoResponseCodes.firstLine("0\n100"));
        assertEquals("0", SmsBaoResponseCodes.firstLine("0\r\n100"));
        assertEquals("0", SmsBaoResponseCodes.firstLine("0\r100"));
        assertTrue(SmsBaoResponseCodes.isSuccess(" 0\n100 "));
    }

    @Test
    void shouldDescribeKnownAndUnknownFailures() {
        assertFalse(SmsBaoResponseCodes.isSuccess("30"));
        assertEquals("短信宝密钥错误", SmsBaoResponseCodes.describe("30"));
        assertEquals("短信宝返回错误：99", SmsBaoResponseCodes.describe("99"));
    }
}
