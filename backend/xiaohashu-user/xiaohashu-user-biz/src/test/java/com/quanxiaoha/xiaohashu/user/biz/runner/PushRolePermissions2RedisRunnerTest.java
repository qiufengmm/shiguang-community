package com.quanxiaoha.xiaohashu.user.biz.runner;

import com.quanxiaoha.xiaohashu.user.biz.domain.dataobject.PermissionDO;
import com.quanxiaoha.xiaohashu.user.biz.domain.dataobject.RoleDO;
import com.quanxiaoha.xiaohashu.user.biz.domain.dataobject.RolePermissionDO;
import com.quanxiaoha.xiaohashu.user.biz.domain.mapper.PermissionDOMapper;
import com.quanxiaoha.xiaohashu.user.biz.domain.mapper.RoleDOMapper;
import com.quanxiaoha.xiaohashu.user.biz.domain.mapper.RolePermissionDOMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushRolePermissions2RedisRunnerTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private RoleDOMapper roleDOMapper;

    @Mock
    private PermissionDOMapper permissionDOMapper;

    @Mock
    private RolePermissionDOMapper rolePermissionDOMapper;

    @Mock
    private ApplicationArguments applicationArguments;

    @InjectMocks
    private PushRolePermissions2RedisRunner runner;

    @Test
    void writesRolePermissionsUsingTheSharedRedisKeys() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent("push.permission.flag", "1", 1, TimeUnit.DAYS)).thenReturn(true);
        when(roleDOMapper.selectEnabledList())
                .thenReturn(List.of(RoleDO.builder().id(1L).roleKey("common_user").build()));
        when(rolePermissionDOMapper.selectByRoleIds(List.of(1L)))
                .thenReturn(List.of(RolePermissionDO.builder().roleId(1L).permissionId(7L).build()));
        when(permissionDOMapper.selectAppEnabledList())
                .thenReturn(List.of(PermissionDO.builder().id(7L).permissionKey("user:read").build()));

        runner.run(applicationArguments);

        verify(valueOperations).set("role:permissions:common_user", "[\"user:read\"]");
    }
}
