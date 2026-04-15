package com.medtech.gateway.service;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class UserSyncService {

    private static final Logger log = LoggerFactory.getLogger(UserSyncService.class);

    private final DatabaseClient db;

    public UserSyncService(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Void> syncUser(Jwt jwt) {
        String keycloakId = jwt.getSubject();

        // CORRECTION null-safety : valeurs par défaut explicites
        String username = jwt.getClaimAsString("preferred_username");
        String email    = jwt.getClaimAsString("email");
        String role     = extractPrimaryRole(jwt);

        String safeUsername = (username != null) ? username : "unknown";
        String safeEmail    = (email    != null) ? email    : "";

        return db.sql("""
                INSERT INTO users (keycloak_id, username, email, role)
                VALUES (:kid, :uname, :email, :role)
                ON CONFLICT (keycloak_id) DO UPDATE
                  SET username = EXCLUDED.username,
                      email    = EXCLUDED.email,
                      role     = EXCLUDED.role,
                      active   = TRUE
                """)
            .bind("kid",   keycloakId)
            .bind("uname", safeUsername)
            .bind("email", safeEmail)
            .bind("role",  role)
            .fetch()
            .rowsUpdated()
            // CORRECTION logger : éviter la concaténation inefficace
            .doOnSuccess(n -> log.debug("Synced user: {}", safeUsername))
            .then();
    }

    public Mono<Boolean> patientExists(String username) {
        // CORRECTION null-safety : garde explicite
        if (username == null || username.isBlank()) {
            return Mono.just(false);
        }

        return db.sql("""
                SELECT COUNT(*) AS cnt FROM users
                WHERE username = :uname
                  AND role = 'PATIENT'
                  AND active = TRUE
                """)
            .bind("uname", username)
            .fetch()
            .first()
            .map(row -> ((Number) row.get("cnt")).longValue() > 0)
            .defaultIfEmpty(false);
    }

    public Mono<Boolean> deviceAuthorizedForPatient(String clientId,
                                                     String patientUsername) {
        // CORRECTION null-safety : gardes explicites
        if (clientId == null || patientUsername == null) {
            return Mono.just(false);
        }

        return db.sql("""
                SELECT COUNT(*) AS cnt
                FROM devices d
                JOIN patients p ON d.patient_id = p.id
                JOIN users    u ON p.user_id     = u.id
                WHERE d.keycloak_client_id = :cid
                  AND u.username           = :uname
                  AND d.statut             = 'ACTIF'
                """)
            .bind("cid",   clientId)
            .bind("uname", patientUsername)
            .fetch()
            .first()
            .map(row -> ((Number) row.get("cnt")).longValue() > 0)
            .defaultIfEmpty(false);
    }

    private String extractPrimaryRole(Jwt jwt) {
        try {
            var realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess != null) {
                Object rolesObj = realmAccess.get("roles");
                if (rolesObj instanceof Collection<?> roles) {
                    for (String r : List.of("ADMIN","MEDECIN","PATIENT","DEVICE")) {
                        if (roles.contains(r)) return r;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract role from JWT: {}", e.getMessage());
        }
        return "PATIENT";
    }
}