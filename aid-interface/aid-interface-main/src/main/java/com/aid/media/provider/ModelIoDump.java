package com.aid.media.provider;

import java.io.FileWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 【临时调试工具，测试完请删除】
 * 把"最终下发给大模型的入参"和"上游原始出参"分别追加输出到项目根目录两个文件，
 * 方便本地统计各模型真实的入参/出参结构。
 * 所有 Provider 共用这一份逻辑，避免在每个 Provider 里重复写文件代码。
 */
public final class ModelIoDump {

    // 入参输出文件（根目录）
    private static final String REQ_FILE = "D:/aid_pro/aid-master/model_request.log";
    // 出参输出文件（根目录）
    private static final String RESP_FILE = "D:/aid_pro/aid-master/model_response.log";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ModelIoDump() {
    }

    /** 记录最终下发给大模型的入参（tag 一般传 url 或模型标识） */
    public static void req(String tag, String body) {
        // 【测试日志·上线必删】前缀 [trace|task|C]，使本文件与 model_billing_trace.log 按 taskId 对上
        append(REQ_FILE, TestBillingTraceLog.tag() + tag, body);
    }

    /** 记录上游原始出参，并原样返回 body，便于直接包裹在 return 上 */
    public static String resp(String tag, String body) {
        // 【测试日志·上线必删】前缀 [trace|task|C]，使本文件与 model_billing_trace.log 按 taskId 对上
        append(RESP_FILE, TestBillingTraceLog.tag() + tag, body);
        return body;
    }

    private static synchronized void append(String file, String tag, String body) {
        try (FileWriter w = new FileWriter(file, true)) {
            w.write("==================== " + LocalDateTime.now().format(TS) + " | " + tag + " ====================\n");
            w.write(body == null ? "<null>" : body);
            w.write("\n\n");
        } catch (Exception ignore) {
            // 调试工具，写文件失败不影响主流程
        }
    }
}
