package com.medtech.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Flux;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    @Bean
    public ReactiveJwtDecoder jwtDecoder() {
        return NimbusReactiveJwtDecoder
                .withJwkSetUri(jwkSetUri)
                .build();
    }

    @Bean
    public ReactiveJwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedConverter =
                new JwtGrantedAuthoritiesConverter();
        grantedConverter.setAuthoritiesClaimName("realm_access.roles");
        grantedConverter.setAuthorityPrefix("ROLE_");

        ReactiveJwtAuthenticationConverter converter =
                new ReactiveJwtAuthenticationConverter();

        // CORRECTION : Flux.fromIterable() au lieu de Mono.just()
        converter.setJwtGrantedAuthoritiesConverter(
                jwt -> Flux.fromIterable(grantedConverter.convert(jwt))
        );
        return converter;
    }

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(
            ServerHttpSecurity http,
            ReactiveJwtDecoder jwtDecoder,
            ReactiveJwtAuthenticationConverter jwtAuthConverter) {

        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(ex -> ex
                .pathMatchers("/actuator/health", "/health").permitAll()
                .pathMatchers("/api/vitals/patients",
                              "/api/vitals/latest/**",
                              "/api/vitals/stats/**",
                              "/api/vitals/stream/**",
                              "/api/notifications/health",
                              "/api/notifications/stats",
                              "/api/notifications/stream/**")
                    .permitAll()
                .pathMatchers("/api/ingestion/**").hasRole("DEVICE")
                .pathMatchers("/api/vitals/patient/**",
                              "/api/dashboard/patient/**")
                    .hasAnyRole("PATIENT", "MEDECIN", "ADMIN")
                .pathMatchers("/api/admin/**").hasRole("ADMIN")
                .pathMatchers("/api/vitals/**",
                              "/api/analytics/**",
                              "/api/alerts/**",
                              "/api/dashboard/**")
                    .hasAnyRole("MEDECIN", "ADMIN")
                .anyExchange().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .jwtDecoder(jwtDecoder)
                    .jwtAuthenticationConverter(jwtAuthConverter)
                )
            );

        return http.build();
    }
}