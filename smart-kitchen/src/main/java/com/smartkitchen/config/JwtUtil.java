package com.smartkitchen.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class JwtUtil {

    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";
    public static final String CLAIM_TOKEN_TYPE = "tokenType";

    @Value("${smart-kitchen.jwt.secret}")
    private String secret;

    @Value("${smart-kitchen.jwt.access-expiration}")
    private Long accessExpiration;

    @Value("${smart-kitchen.jwt.refresh-expiration}")
    private Long refreshExpiration;

    private Key getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /** 兼容旧调用：签发 Access Token */
    public String generateToken(Long userId, String role, String openid) {
        return generateAccessToken(userId, role, openid);
    }

    public String generateAccessToken(Long userId, String role, String openid) {
        return buildToken(userId, role, openid, TOKEN_TYPE_ACCESS, accessExpiration);
    }

    public String generateRefreshToken(Long userId, String role) {
        return buildToken(userId, role, null, TOKEN_TYPE_REFRESH, refreshExpiration);
    }

    private String buildToken(Long userId, String role, String openid, String tokenType, long ttlMillis) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("role", role);
        claims.put(CLAIM_TOKEN_TYPE, tokenType);
        if (openid != null) {
            claims.put("openid", openid);
        }

        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setClaims(claims)
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + ttlMillis))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String getTokenType(Claims claims) {
        Object type = claims.get(CLAIM_TOKEN_TYPE);
        return type == null ? TOKEN_TYPE_ACCESS : String.valueOf(type);
    }

    public boolean isAccessToken(Claims claims) {
        return TOKEN_TYPE_ACCESS.equals(getTokenType(claims));
    }

    public boolean isRefreshToken(Claims claims) {
        return TOKEN_TYPE_REFRESH.equals(getTokenType(claims));
    }

    public long remainingTtlMillis(Claims claims) {
        Date exp = claims.getExpiration();
        if (exp == null) {
            return 0L;
        }
        return Math.max(0L, exp.getTime() - System.currentTimeMillis());
    }

    public long issuedAtMillis(Claims claims) {
        Date iat = claims.getIssuedAt();
        return iat == null ? 0L : iat.getTime();
    }

    public Long getAccessExpiration() {
        return accessExpiration;
    }

    public Long getRefreshExpiration() {
        return refreshExpiration;
    }
}
