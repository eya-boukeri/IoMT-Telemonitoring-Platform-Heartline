package com.medtech.gateway.filter;

import java.net.InetSocketAddress;
import java.util.logging.Logger;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class LoggingFilter implements GlobalFilter, Ordered {

    private static final Logger logger = Logger.getLogger(LoggingFilter.class.getName());

    @Override
    public int getOrder() {
        return -1; // S'exécute avant tous les autres filtres
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = System.currentTimeMillis();
        ServerHttpRequest request = exchange.getRequest();

        String method = request.getMethod() != null ? request.getMethod().name() : "UNKNOWN";
        String path   = request.getURI().getPath();
        String ip     = extractClientIp(request);

        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> ctx.getAuthentication())
            .map(auth -> {
                // Extraire le userId depuis le JWT Keycloak
                if (auth instanceof JwtAuthenticationToken jwtAuth) {
                    Jwt jwt = (Jwt) jwtAuth.getToken();
                    // Keycloak expose preferred_username comme identifiant lisible
                    String userId = jwt.getClaimAsString("preferred_username");
                    return userId != null ? userId : jwt.getSubject();
                }
                return "anonymous";
            })
            .defaultIfEmpty("anonymous")
            .flatMap(userId -> {
                // Injecter X-User-Id dans la requête vers le service downstream
                ServerHttpRequest mutatedRequest = request.mutate()
                    .header("X-User-Id", userId)
                    .header("X-Forwarded-For", ip)
                    .build();

                ServerWebExchange mutatedExchange = exchange.mutate()
                    .request(mutatedRequest)
                    .build();

                return chain.filter(mutatedExchange)
                    .doFinally(signalType -> {
                        long duration = System.currentTimeMillis() - startTime;

                        // CORRECTION : vérification null explicite avant d'appeler .value()
                        var statusCodeObj = exchange.getResponse().getStatusCode();
                        int statusCode = (statusCodeObj != null) ? statusCodeObj.value() : 0;

                        logger.info(String.format(
                            "[GATEWAY] %s %s | user=%s | ip=%s | status=%d | duration=%dms",
                            method, path, userId, ip, statusCode, duration
                        ));
                    });
            });
    }

    private String extractClientIp(ServerHttpRequest request) {
        // Vérifier d'abord les headers proxy
        String xForwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeaders().getFirst("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        return remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : "unknown";
    }
}