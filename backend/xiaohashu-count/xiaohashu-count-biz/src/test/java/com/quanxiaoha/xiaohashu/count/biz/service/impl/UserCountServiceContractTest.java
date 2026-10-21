package com.quanxiaoha.xiaohashu.count.biz.service.impl;

import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.UserCountDOMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserCountServiceContractTest {

    @Test
    void userCountMapperShouldQueryByUserId() {
        assertTrue(Arrays.stream(UserCountDOMapper.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("selectByUserId")),
                "UserCountDOMapper should expose selectByUserId");
    }

    @Test
    void userCountServiceAndControllerShouldExist() {
        assertNotNull(loadClass("com.quanxiaoha.xiaohashu.count.biz.service.UserCountService"),
                "UserCountService should exist");
        assertNotNull(loadClass("com.quanxiaoha.xiaohashu.count.biz.controller.UserCountController"),
                "UserCountController should exist");
    }

    private Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }
}
