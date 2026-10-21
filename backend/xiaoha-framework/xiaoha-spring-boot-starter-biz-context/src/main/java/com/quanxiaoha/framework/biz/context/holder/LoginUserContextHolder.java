package com.quanxiaoha.framework.biz.context.holder;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.quanxiaoha.framework.common.constant.GlobalConstants;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 登录用户上下文。
 */
public final class LoginUserContextHolder {

    private static final ThreadLocal<Map<String, Object>> CONTEXT =
            TransmittableThreadLocal.withInitial(HashMap::new);

    private LoginUserContextHolder() {
    }

    public static void setUserId(Object userId) {
        CONTEXT.get().put(GlobalConstants.USER_ID, userId);
    }

    public static Long getUserId() {
        Object value = CONTEXT.get().get(GlobalConstants.USER_ID);
        return Objects.isNull(value) ? null : Long.valueOf(value.toString());
    }

    public static void remove() {
        CONTEXT.remove();
    }
}
