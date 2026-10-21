package com.quanxiaoha.xiaohashu.gateway.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaTokenConfigureTest {

    @Test
    void exposesOnlyUserProfileAndUpdateRoutesToGatewayClients() {
        assertFalse(SaTokenConfigure.isInternalUserApiPath("/user/user/profile"));
        assertFalse(SaTokenConfigure.isInternalUserApiPath("/user/user/update"));
        assertTrue(SaTokenConfigure.isInternalUserApiPath("/user/user/findByPhone"));
        assertTrue(SaTokenConfigure.isInternalUserApiPath("/user/user/register"));
        assertTrue(SaTokenConfigure.isInternalUserApiPath("/user/user/password/update"));
        assertTrue(SaTokenConfigure.isInternalUserApiPath("/user/user/findByIds"));
        assertFalse(SaTokenConfigure.isInternalUserApiPath("/note/note/list"));
    }
}
