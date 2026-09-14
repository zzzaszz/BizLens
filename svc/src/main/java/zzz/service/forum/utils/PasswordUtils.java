package zzz.service.forum.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码哈希工具：SHA-256 + 随机盐。
 * <p>
 * 存储格式：{@code salt:hash}（均为 Base64）。不引入额外依赖，使用 JDK 内置实现。
 * <p>
 * 说明：SHA-256 + 盐足以抵御彩虹表；若后续引入 spring-security，可平滑替换为 BCrypt。
 *
 * @author caoshi
 * @since 2026-06-30
 */
public final class PasswordUtils {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SALT_LENGTH = 16;
    private static final String SEPARATOR = ":";

    private PasswordUtils() {
    }

    /**
     * 生成随机盐（Base64）
     */
    private static String generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /**
     * 对明文密码 + 盐做 SHA-256，返回 Base64 哈希
     */
    private static String sha256(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            byte[] hashed = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 加密明文密码，返回 {@code salt:hash} 形式的存储串。
     */
    public static String encode(String rawPassword) {
        if (rawPassword == null) {
            rawPassword = "";
        }
        String salt = generateSalt();
        return salt + SEPARATOR + sha256(rawPassword, salt);
    }

    /**
     * 校验明文密码是否与存储串匹配。
     *
     * @param rawPassword    明文密码
     * @param storedPassword 存储串（salt:hash）
     */
    public static boolean matches(String rawPassword, String storedPassword) {
        if (storedPassword == null || storedPassword.isEmpty()) {
            return false;
        }
        int idx = storedPassword.indexOf(SEPARATOR);
        if (idx <= 0 || idx >= storedPassword.length() - 1) {
            return false;
        }
        String salt = storedPassword.substring(0, idx);
        String hash = storedPassword.substring(idx + 1);
        return sha256(rawPassword, salt).equals(hash);
    }
}
