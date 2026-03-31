package com.medtech.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public ReactiveJwtDecoder jwtDecoder() {
        // Vérifie la signature via Keycloak mais ignore la vérification de l'issuer
        // Nécessaire car le token dit localhost:8180 mais Docker utilise keycloak:8080
        return NimbusReactiveJwtDecoder
            .withJwkSetUri("http://keycloak:8080/realms/iot-sante/protocol/openid-connect/certs")
            .build();
    }

    @Bean
    public SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http) {
        http
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/actuator/health").permitAll()
                .pathMatchers("/health").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/vitals/patients").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/vitals/latest/**").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/vitals/history/**").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/vitals/stats/**").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/vitals/recent").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/vitals/stream/**").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/notifications/stream/**").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/notifications/health").permitAll()
                .anyExchange().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtDecoder(jwtDecoder()))
            )
            .csrf(csrf -> csrf.disable());

        return http.build();
    }
}