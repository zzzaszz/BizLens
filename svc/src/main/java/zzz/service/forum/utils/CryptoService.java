package zzz.service.forum.utils;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API Key 加解密（AES/GCM）。
 * <p>
 * 密钥由环境变量 AES_KEY 派生（SHA-256 后截取），生产环境必须配置；
 * 未配置时回退开发默认值，仅用于本地联调，严禁用于生产。
 * 解密结果仅在调用 AI 前存在于内存中，用完即弃、不打日志。
 */
@Component
public class CryptoService {

    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final SecretKey aesKey;

    public CryptoService() {
        String raw = System.getenv("AES_KEY");
        if (raw == null || raw.isEmpty()) {
            raw = "bizlens-ai-dev-key-2026";
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha.digest(raw.getBytes(StandardCharsets.UTF_8));
            this.aesKey = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("初始化 AES 密钥失败", e);
        }
    }

    /** AES/GCM 加密 → Base64（iv + 密文） */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("AES 加密失败", e);
        }
    }

    /** Base64 密文 → 明文 */
    public String decrypt(String cipher) {
        if (cipher == null || cipher.isEmpty()) {
            return cipher;
        }
        try {
            byte[] all = Base64.getDecoder().decode(cipher);
            if (all.length <= GCM_IV_BYTES) {
                return cipher;
            }
            byte[] iv = new byte[GCM_IV_BYTES];
            byte[] encrypted = new byte[all.length - iv.length];
            System.arraycopy(all, 0, iv, 0, iv.length);
            System.arraycopy(all, iv.length, encrypted, 0, encrypted.length);
            Cipher cipherInst = Cipher.getInstance("AES/GCM/NoPadding");
            cipherInst.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipherInst.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 非密文（历史明文数据）原样返回，避免解密失败阻断业务
            return cipher;
        }
    }
}
