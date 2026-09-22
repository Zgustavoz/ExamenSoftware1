package com.diagramas.platform.common.security;

import com.diagramas.platform.common.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/** Emite y valida JWT HS256: sub = userId, claims company_id y roles. */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(AppProperties props) {
        byte[] secret = props.jwt().secret() == null ? new byte[0] : props.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("JWT_SECRET debe tener al menos 32 bytes.");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.expirationMinutes = props.jwt().expirationMinutes();
    }

    public String issue(UUID userId, UUID companyId, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("company_id", companyId.toString())
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationMinutes * 60)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** @throws JwtException si el token es inválido o expiró */
    @SuppressWarnings("unchecked")
    public AuthPrincipal parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        try {
            List<String> roles = (List<String>) claims.get("roles", List.class);
            return new AuthPrincipal(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.get("company_id", String.class)),
                    roles == null ? List.of() : List.copyOf(roles));
        } catch (RuntimeException e) {
            throw new JwtException("Token con claims inválidos", e);
        }
    }
}
