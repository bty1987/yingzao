package com.yingzao.core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 敏感字段加密盒(内核平台设施,任何插件可用)。
 *
 * - encrypt/decrypt:AES-256-GCM(随机 12 字节 IV,密文格式 {@code v1:<base64(iv||ct)>}),
 *   用于电话/身份证/门牌/账号等 PII 落库;IV 随机 ⇒ 同明文不同密文,等值检索走 {@link #hmac};
 * - hmac:HMAC-SHA256(十六进制小写),用于密文等值查询与唯一约束(加密列不可等值查,hmac 列可);
 * - 密钥经配置注入,独立于会话令牌密钥(老系统一把钥匙开两把锁的教训);
 *   application.yml 中的默认值仅限本地开发冒烟,生产必须经环境变量覆盖。
 */
@Component
public class CryptoBox {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey aesKey;
    private final SecretKey macKey;
    private final SecureRandom random = new SecureRandom();

    public CryptoBox(
            @Value("${yingzao.crypto.aes-key:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=}") String aesKeyBase64,
            @Value("${yingzao.crypto.hmac-key:ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=}") String hmacKeyBase64) {
        this.aesKey = new SecretKeySpec(Base64.getDecoder().decode(aesKeyBase64), "AES");
        this.macKey = new SecretKeySpec(Base64.getDecoder().decode(hmacKeyBase64), "HmacSHA256");
    }

    /** 加密;null 透传 null,空白串原样返回(空值不加密)。 */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    /** 解密;非本盒格式(无 v1: 前缀)的输入原样返回,便于灰度期兼容历史明文。 */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank() || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] pt = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("decrypt failed", e);
        }
    }

    /** HMAC-SHA256 十六进制小写;null 透传 null,空白串返回空串。 */
    public String hmac(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        if (plaintext.isBlank()) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(macKey);
            byte[] digest = mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("hmac failed", e);
        }
    }

    /** 手机号脱敏:保留前 3 后 4(不足 8 位全遮)。 */
    public static String maskPhone(String phone) {
        return mask(phone, 3, 4);
    }

    /** 账号脱敏:仅展示末 4 位。 */
    public static String maskAccount(String accountNo) {
        if (accountNo == null || accountNo.length() <= 4) {
            return "****";
        }
        return "****" + accountNo.substring(accountNo.length() - 4);
    }

    private static String mask(String value, int head, int tail) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (value.length() <= head + tail) {
            return "****";
        }
        return value.substring(0, head) + "****" + value.substring(value.length() - tail);
    }
}
