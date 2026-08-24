package com.smartkitchen.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试环境 TokenStore，避免集成测试依赖真实 Redis。
 */
@Component
@Profile("test")
public class InMemoryTokenStore implements TokenStore {

    private static class ExpiringValue {
        final String value;
        final long expireAt;

        ExpiringValue(String value, long ttlMillis) {
            this.value = value;
            this.expireAt = System.currentTimeMillis() + Math.max(ttlMillis, 1L);
        }

        boolean expired() {
            return System.currentTimeMillis() >= expireAt;
        }
    }

    private final Map<String, ExpiringValue> refreshByJti = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> refreshByUser = new ConcurrentHashMap<>();
    private final Map<String, Long> blacklist = new ConcurrentHashMap<>();
    private final Map<Long, ExpiringValue> userRevoke = new ConcurrentHashMap<>();

    @Override
    public void saveRefreshToken(String jti, Long userId, long ttlMillis) {
        refreshByJti.put(jti, new ExpiringValue(String.valueOf(userId), ttlMillis));
        refreshByUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(jti);
    }

    @Override
    public boolean isRefreshValid(String jti, Long userId) {
        ExpiringValue stored = refreshByJti.get(jti);
        if (stored == null || stored.expired()) {
            refreshByJti.remove(jti);
            return false;
        }
        return String.valueOf(userId).equals(stored.value);
    }

    @Override
    public void removeRefreshToken(String jti, Long userId) {
        refreshByJti.remove(jti);
        Set<String> set = refreshByUser.get(userId);
        if (set != null) {
            set.remove(jti);
        }
    }

    @Override
    public void removeAllRefreshTokens(Long userId) {
        Set<String> set = refreshByUser.remove(userId);
        if (set != null) {
            set.forEach(refreshByJti::remove);
        }
    }

    @Override
    public void blacklist(String jti, long ttlMillis) {
        if (jti == null || jti.isBlank() || ttlMillis <= 0) {
            return;
        }
        blacklist.put(jti, System.currentTimeMillis() + ttlMillis);
    }

    @Override
    public boolean isBlacklisted(String jti) {
        if (jti == null) {
            return false;
        }
        Long expireAt = blacklist.get(jti);
        if (expireAt == null) {
            return false;
        }
        if (System.currentTimeMillis() >= expireAt) {
            blacklist.remove(jti);
            return false;
        }
        return true;
    }

    @Override
    public void revokeUser(Long userId, long ttlMillis) {
        userRevoke.put(userId, new ExpiringValue(String.valueOf(System.currentTimeMillis()), ttlMillis));
        removeAllRefreshTokens(userId);
    }

    @Override
    public boolean isUserRevoked(Long userId, long tokenIssuedAtMillis) {
        ExpiringValue stored = userRevoke.get(userId);
        if (stored == null) {
            return false;
        }
        if (stored.expired()) {
            userRevoke.remove(userId);
            return false;
        }
        long revokeAt = Long.parseLong(stored.value);
        return tokenIssuedAtMillis < revokeAt;
    }
}
