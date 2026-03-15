package com.medtech.gateway.filter;

import java.util.logging.Logger;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class LoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = Logger.getLogger(LoggingFilter.class.getName());

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        long startTime = System.currentTimeMillis();

        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> ctx.getAuthentication().getName())
            .defaultIfEmpty("anonymous")
            .flatMap(userId -> {

                log.info("[GATEWAY] " + request.getMethod()
                    + " " + request.getURI().getPath()
                    + " | user=" + userId
                    + " | ip=" + request.getRemoteAddress());

                ServerHttpRequest mutatedRequest = request.mutate()
                    .header("X-User-Id", userId)
                    .header("X-Gateway", "medtech-gateway")
                    .build();

                return chain.filter(exchange.mutate().request(mutatedRequest).build())
                    .doFinally(signal -> {
                        long duration = System.currentTimeMillis() - startTime;
                        log.info("[GATEWAY] " + request.getMethod()
                            + " " + request.getURI().getPath()
                            + " -> " + duration + "ms");
                    });
            });
    }

    @Override
    public int getOrder() {
        return -1;
    }
}