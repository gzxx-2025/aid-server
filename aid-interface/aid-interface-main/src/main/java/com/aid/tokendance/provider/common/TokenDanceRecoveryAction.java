package com.aid.tokendance.provider.common;

import cn.hutool.core.util.StrUtil;
import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorResult;

/** TokenDance-Recovery-Action 的受控解释。 */
public final class TokenDanceRecoveryAction
{
    public static final String TOP_UP_BALANCE = "top_up_balance";
    public static final String REAUTHORIZE_API_KEY = "reauthorize_api_key";
    public static final String API_KEY_QUOTA = "api_key_quota";

    private TokenDanceRecoveryAction()
    {
    }

    public static TaskErrorResult toTaskError(String action, String rawMessage)
    {
        TaskErrorCode code = switch (StrUtil.blankToDefault(action, "").trim().toLowerCase())
        {
            case TOP_UP_BALANCE -> TaskErrorCode.PROVIDER_BALANCE_INSUFFICIENT;
            case REAUTHORIZE_API_KEY -> TaskErrorCode.UPSTREAM_AUTH_INVALID;
            case API_KEY_QUOTA -> TaskErrorCode.PROVIDER_QUOTA_EXHAUSTED;
            default -> null;
        };
        return code == null ? null : TaskErrorResult.of(code, rawMessage);
    }
}
