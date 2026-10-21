package com.quanxiaoha.framework.biz.operationlog.aspect;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OperationLogSanitizerTest {

    @Test
    void masksCredentialsAndPersonalDataInNestedRequestAndResponseValues() {
        String safe = OperationLogSanitizer.toSafeJson(Map.of(
                "phone", "13812345678",
                "code", "764291",
                "password", "plain-password",
                "nested", Map.of("accessToken", "token-value", "data", "login-token")));

        assertThat(safe).doesNotContain("13812345678", "764291", "plain-password", "token-value", "login-token");
        assertThat(safe).contains("[REDACTED]");
    }
}
