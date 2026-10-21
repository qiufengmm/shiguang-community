package com.quanxiaoha.xiaohashu.user.biz.constant;

/**
 * 用户服务 Redis 键常量。
 */
public final class RedisKeyConstants {

    /** 保留认证服务当前使用的键名，避免注册服务切换后计数器从头开始。 */
    public static final String XIAOHASHU_ID_GENERATOR_KEY = "xiaohashu_id_generator";

    private static final String USER_ROLES_KEY_PREFIX = "user:roles:";
    private static final String ROLE_PERMISSIONS_KEY_PREFIX = "role:permissions:";

    /**
     * 用户信息数据 KEY 前缀
     */
    private static final String USER_INFO_KEY_PREFIX = "user:info:";

    /** 用户主页信息缓存前缀 */
    private static final String USER_PROFILE_KEY_PREFIX = "user:profile:";

    private RedisKeyConstants() {
    }

    /**
     * 构建用户信息 KEY
     *
     * @param userId
     * @return
     */
    public static String buildUserInfoKey(Long userId) {
        return USER_INFO_KEY_PREFIX + userId;
    }

    public static String buildUserProfileKey(Long userId) {
        return USER_PROFILE_KEY_PREFIX + userId;
    }

    public static String buildUserRoleKey(Long userId) {
        return USER_ROLES_KEY_PREFIX + userId;
    }

    public static String buildRolePermissionsKey(String roleKey) {
        return ROLE_PERMISSIONS_KEY_PREFIX + roleKey;
    }
}
