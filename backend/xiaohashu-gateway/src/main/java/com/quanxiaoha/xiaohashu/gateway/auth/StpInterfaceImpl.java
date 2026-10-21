package com.quanxiaoha.xiaohashu.gateway.auth;

import cn.dev33.satoken.stp.StpInterface;
import cn.hutool.core.collection.CollUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.quanxiaoha.xiaohashu.gateway.constant.RedisKeyConstants;
import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 自定义权限验证接口扩展
 * <p>
 * SaToken 做 checkRole / checkPermission 时，会回调本类拿当前用户的角色 / 权限集合。
 * 数据来源是认证服务启动时推送到 Redis 的那两份数据（key 精确、value 是纯 JSON 字符串）。
 * <p>
 * 说明：这里用 StringRedisTemplate 而不是自定义的 RedisTemplate（Jackson JSON 序列化器）。
 * 因为认证服务写入的就是「纯字符串」，用 Jackson 序列化器读出来的会是 ArrayList，
 * 再赋给 String 变量会直接 ClassCastException。
 */
@Component
@Slf4j
public class StpInterfaceImpl implements StpInterface {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 获取用户权限
     *
     * @param loginId   登录用户 ID（即 StpUtil.login() 的入参）
     * @param loginType
     * @return
     */
    @SneakyThrows
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        log.info("## 获取用户权限列表, loginId: {}", loginId);

        // 构建 用户-角色 Redis Key
        String userRolesKey = RedisKeyConstants.buildUserRoleKey(Long.valueOf(loginId.toString()));

        // 根据用户 ID，从 Redis 中获取该用户的角色集合
        String useRolesValue = stringRedisTemplate.opsForValue().get(userRolesKey);

        if (StringUtils.isBlank(useRolesValue)) {
            return null;
        }

        // 将 JSON 字符串转换为 List<String> 角色集合
        List<String> userRoleKeys = objectMapper.readValue(useRolesValue, new TypeReference<>() {
        });

        if (CollUtil.isNotEmpty(userRoleKeys)) {
            // 查询这些角色对应的权限
            // 构建 角色-权限 Redis Key 集合
            List<String> rolePermissionsKeys = userRoleKeys.stream()
                    .map(RedisKeyConstants::buildRolePermissionsKey)
                    .toList();

            // 通过 key 集合批量查询权限，保证与 Redis 只交互 2 次，提升查询性能
            List<String> rolePermissionsValues = stringRedisTemplate.opsForValue().multiGet(rolePermissionsKeys);

            if (CollUtil.isNotEmpty(rolePermissionsValues)) {
                List<String> permissions = Lists.newArrayList();

                // 遍历所有角色的权限集合，统一添加到 permissions 集合中
                rolePermissionsValues.forEach(jsonValue -> {
                    // 【教程原版代码这里漏了判空，是个 bug】
                    // multiGet 遇到不存在的 key 时，返回的 List 里会占一个 null 坑，
                    // 此时 objectMapper.readValue(null, ...) 会抛 IllegalArgumentException
                    // （注意它不是 JsonProcessingException，下面的 catch 抓不到），
                    // 最终一路冒泡成"系统繁忙"，而不是期望的"权限不足"。
                    // 触发场景很常见：用户身上挂了某个还没配权限数据的角色。
                    if (StringUtils.isBlank(jsonValue)) {
                        return;
                    }
                    try {
                        // 将 JSON 字符串转换为 List<String> 权限集合
                        List<String> rolePermissions = objectMapper.readValue(jsonValue, new TypeReference<>() {
                        });
                        permissions.addAll(rolePermissions);
                    } catch (JsonProcessingException e) {
                        log.error("==> JSON 解析错误: ", e);
                    }
                });

                // 返回此用户所拥有的权限
                return permissions;
            }
        }

        return null;
    }

    /**
     * 获取用户角色
     *
     * @param loginId   登录用户 ID
     * @param loginType
     * @return
     */
    @SneakyThrows
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        log.info("## 获取用户角色列表, loginId: {}", loginId);

        // 构建 用户-角色 Redis Key
        String userRolesKey = RedisKeyConstants.buildUserRoleKey(Long.valueOf(loginId.toString()));

        // 根据用户 ID，从 Redis 中获取该用户的角色集合
        String useRolesValue = stringRedisTemplate.opsForValue().get(userRolesKey);

        if (StringUtils.isBlank(useRolesValue)) {
            return null;
        }

        // 将 JSON 字符串转换为 List<String> 集合
        return objectMapper.readValue(useRolesValue, new TypeReference<>() {
        });
    }

}
