package com.quanxiaoha.xiaohashu.gateway.filter;

import cn.dev33.satoken.stp.StpUtil;
import com.quanxiaoha.framework.common.constant.GlobalConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 转发请求时，将用户 ID 添加到 Header 请求头中，透传给下游服务
 * <p>
 * 执行顺序：SaReactorFilter 上标了 @Order(-100)，优先级更高，会先执行。
 * 之所以在这里显式指定 @Order(-90)，是为了确保本过滤器排在 SaReactorFilter（-100）之后、
 * 又排在网关的路由转发过滤器之前。否则本过滤器会落到最低优先级，与 NettyRoutingFilter 同级，
 * 有可能在请求已经转发之后才执行，导致下游服务拿不到用户 ID。
 */
@Component
@Profile("!docker")
@Order(-90)
@Slf4j
public class AddUserId2HeaderFilter implements GlobalFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        log.info("==================> AddUserId2HeaderFilter");

        // 用户 ID
        Long userId = null;
        try {
            // 获取当前登录用户的 ID
            userId = StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            // 若没有登录（接口本身无需鉴权），则不做任何修改，直接放行
            return chain.filter(exchange);
        }

        log.info("## 当前登录的用户 ID: {}", userId);

        Long finalUserId = userId;
        ServerWebExchange newExchange = exchange.mutate()
                // 将用户 ID 设置到请求头中，透传给下游服务
                .request(builder -> builder.header(GlobalConstants.USER_ID, String.valueOf(finalUserId)))
                .build();

        return chain.filter(newExchange);
    }

}
