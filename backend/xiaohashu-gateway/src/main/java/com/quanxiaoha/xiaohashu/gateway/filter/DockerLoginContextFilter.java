package com.quanxiaoha.xiaohashu.gateway.filter;

import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.stp.StpUtil;
import com.quanxiaoha.framework.common.constant.GlobalConstants;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 容器联调：服务发现切换线程后，基于当前请求恢复 Sa-Token 上下文并透传用户 ID。
 */
@Component
@Profile("docker")
@Order(-95)
public class DockerLoginContextFilter implements GlobalFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        AtomicReference<Object> loginId = new AtomicReference<>();
        // Sa-Token 1.38 的同步上下文使用 ThreadLocal，不能依赖鉴权线程遗留的状态。
        SaReactorSyncHolder.setContext(exchange, () -> loginId.set(StpUtil.getLoginIdDefaultNull()));

        ServerWebExchange forwarded = exchange.mutate().request(request -> request.headers(headers -> {
            headers.remove(GlobalConstants.USER_ID);
            if (loginId.get() != null) {
                headers.set(GlobalConstants.USER_ID, String.valueOf(loginId.get()));
            }
        })).build();
        return chain.filter(forwarded);
    }
}
