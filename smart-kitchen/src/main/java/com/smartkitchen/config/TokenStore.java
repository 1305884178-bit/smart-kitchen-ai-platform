package com.smartkitchen.config;

/**
 * Access / Refresh Token 的服务端状态：
 * Refresh 白名单、Access 黑名单、用户级吊销时间戳。
 * 生产用 Redis，测试用内存实现。
 */
public interface TokenStore {

    void saveRefreshToken(String jti, Long userId, long ttlMillis);

    boolean isRefreshValid(String jti, Long userId);

    void removeRefreshToken(String jti, Long userId);

    void removeAllRefreshTokens(Long userId);

    void blacklist(String jti, long ttlMillis);

    boolean isBlacklisted(String jti);

    /**
     * 吊销该用户在此时刻之前签发的所有 Access Token（改密 / 全端登出）。
     */
    void revokeUser(Long userId, long ttlMillis);

    /**
     * tokenIssuedAtMillis 早于吊销时间则视为已作废。
     */
    boolean isUserRevoked(Long userId, long tokenIssuedAtMillis);
}
