package com.quanxiaoha.xiaohashu.gateway.auth;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * [Sa-Token 权限认证] 配置类
 */
@Configuration
@Slf4j
public class SaTokenConfigure {

    private static final Set<String> GATEWAY_ACCESSIBLE_USER_ROUTES = Set.of(
            "/user/user/profile",
            "/user/user/update"
    );

    @Value("${xiaohashu.frontend.enabled:false}")
    private boolean frontendEnabled;

    // 注册 Sa-Token 全局过滤器
    @Bean
    public SaReactorFilter getSaReactorFilter() {
        return new SaReactorFilter()
                // 拦截地址
                .addInclude("/**")    /* 拦截全部 path */
                // 鉴权方法：每次访问进入
                .setAuth(obj -> {
                    log.info("==================> SaReactorFilter, Path: {}", SaHolder.getRequest().getRequestPath());

                    // User service endpoints other than profile/update are service-to-service APIs.
                    // Feign calls reach the service directly, so these routes must not be exposed by the gateway.
                    SaRouter.match("/user/user/**").check(request -> {
                        if (isInternalUserApiPath(SaHolder.getRequest().getRequestPath())) {
                            throw new NotPermissionException("internal user API is not exposed through the gateway");
                        }
                    });

                    // 登录校验
                    SaRouter.match("/**") // 拦截所有路由
                            .notMatch("/auth/login") // 排除登录接口
                            .notMatch("/auth/verification/code/send") // 排除验证码发送接口
                            .notMatch("/user/user/profile") // 网关去掉首段后转发至用户主页接口
                            .notMatch(frontendEnabled ? new String[]{
                                    "/note/channel/list", "/note/topic/list", "/note/discover/note/list"
                            } : new String[0]) // 容器演示环境的公开只读首页接口
                            .check(r -> StpUtil.checkLogin()) // 校验是否登录
                    ;

                    // 权限认证 / 角色认证 -- 不同模块，校验不同权限
                    // 这里只是拿登出接口做演示，真实项目里应该按业务模块配置，比如：
                    //   SaRouter.match("/auth/note/publish", r -> StpUtil.checkPermission("app:note:publish"));
                    SaRouter.match("/auth/logout", r -> StpUtil.checkPermission("app:note:publish"));

                    // 更多匹配 ...  */
                })
                // 异常处理方法：每次 setAuth 函数出现异常时进入
                .setError(e -> {
                    // 注意：这里必须手动抛出「具体类型」的异常，交给全局异常处理器 GlobalExceptionHandler 区分处理。
                    // 如果只是 return SaResult.error(...)，SaToken 会统一抛 SaTokenException 父异常，
                    // 全局异常处理器就没法区分到底是「没登录」还是「没权限」了。
                    if (e instanceof NotLoginException) {
                        // 未登录（含未携带 Token、令牌无效、令牌过期）
                        throw new NotLoginException(e.getMessage(), null, null);
                    } else if (e instanceof NotPermissionException || e instanceof NotRoleException) {
                        // 权限不足 / 不具备对应角色，统一按「权限不足」处理
                        throw new NotPermissionException(e.getMessage());
                    } else {
                        // 其他异常
                        throw new RuntimeException(e.getMessage());
                    }
                })
                ;
    }

    static boolean isInternalUserApiPath(String requestPath) {
        return requestPath != null
                && requestPath.startsWith("/user/user/")
                && !GATEWAY_ACCESSIBLE_USER_ROUTES.contains(requestPath);
    }

}
