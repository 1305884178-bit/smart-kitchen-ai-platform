package com.smartkitchen.config;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
@Profile("!test")
public class RedisTokenStore implements TokenStore {

    private static final String BLACKLIST = "auth:blacklist:";
    private static final String REFRESH = "auth:refresh:";
    private static final String REFRESH_USER = "auth:refresh:user:";
    private static final String REVOKE = "auth:revoke:";

    private final StringRedisTemplate redis;

    public RedisTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void saveRefreshToken(String jti, Long userId, long ttlMillis) {
        redis.opsForValue().set(REFRESH + jti, String.valueOf(userId), ttlMillis, TimeUnit.MILLISECONDS);
        String userKey = REFRESH_USER + userId;
        redis.opsForSet().add(userKey, jti);
        Long expire = redis.getExpire(userKey, TimeUnit.MILLISECONDS);
        if (expire == null || expire < ttlMillis) {
            redis.expire(userKey, ttlMillis, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public boolean isRefreshValid(String jti, Long userId) {
        String stored = redis.opsForValue().get(REFRESH + jti);
        return stored != null && stored.equals(String.valueOf(userId));
    }

    @Override
    public void removeRefreshToken(String jti, Long userId) {
        redis.delete(REFRESH + jti);
        redis.opsForSet().remove(REFRESH_USER + userId, jti);
    }

    @Override
    public void removeAllRefreshTokens(Long userId) {
        String userKey = REFRESH_USER + userId;
        Set<String> jtis = redis.opsForSet().members(userKey);
        if (jtis != null) {
            for (String jti : jtis) {
                redis.delete(REFRESH + jti);
            }
        }
        redis.delete(userKey);
    }

    @Override
    public void blacklist(String jti, long ttlMillis) {
        if (jti == null || jti.isBlank() || ttlMillis <= 0) {
            return;
        }
        redis.opsForValue().set(BLACKLIST + jti, "1", ttlMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redis.hasKey(BLACKLIST + jti));
    }

    @Override
    public void revokeUser(Long userId, long ttlMillis) {
        redis.opsForValue().set(REVOKE + userId, String.valueOf(System.currentTimeMillis()),
                Math.max(ttlMillis, 1000L), TimeUnit.MILLISECONDS);
        removeAllRefreshTokens(userId);
    }

    @Override
    public boolean isUserRevoked(Long userId, long tokenIssuedAtMillis) {
        String raw = redis.opsForValue().get(REVOKE + userId);
        if (raw == null) {
            return false;
        }
        try {
            return tokenIssuedAtMillis < Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
