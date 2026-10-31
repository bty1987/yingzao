package com.yingzao.core.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CryptoBox 单测(F4a 内核纯逻辑面):AES-GCM 加解密往返/随机 IV/密文篡改拒绝/
 * HMAC 确定性/脱敏工具。手机号加密落库 + hmac 等值检索是 addons 侧既有惯例
 * (market 卡片/customerprofile 楼盘),本类守住密文格式与空值透传契约。
 */
class CryptoBoxTest {

    private static final String AES_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String HMAC_KEY = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=";

    private final CryptoBox box = new CryptoBox(AES_KEY, HMAC_KEY);

    @Test
    void encryptDecryptRoundtrip() {
        String phone = "13812345678";
        String stored = box.encrypt(phone);
        assertTrue(stored.startsWith("v1:"));
        assertEquals(phone, box.decrypt(stored));
        // 中文与特殊字符
        String text = "欠款 ¥12,000 —《营造法式》";
        assertEquals(text, box.decrypt(box.encrypt(text)));
    }

    @Test
    void randomIvYieldsDifferentCiphertexts() {
        String a = box.encrypt("13812345678");
        String b = box.encrypt("13812345678");
        assertNotEquals(a, b);
        assertEquals("13812345678", box.decrypt(a));
        assertEquals("13812345678", box.decrypt(b));
    }

    @Test
    void nullAndBlankPassthrough() {
        assertNull(box.encrypt(null));
        assertEquals("", box.encrypt(""));
        assertEquals(" ", box.encrypt(" "));
        assertNull(box.decrypt(null));
        assertEquals("", box.decrypt(""));
        // 非 v1: 前缀 = 历史明文,灰度兼容原样返回
        assertEquals("13812345678", box.decrypt("13812345678"));
    }

    @Test
    void tamperedCiphertextRejected() {
        String stored = box.encrypt("13812345678");
        String body = stored.substring("v1:".length());
        // 翻转密文段一个字符(避开固定前缀,GCM 认证标签必拒)
        char flipped = body.charAt(body.length() - 3) == 'A' ? 'B' : 'A';
        String tampered = "v1:" + body.substring(0, body.length() - 3) + flipped
                + body.substring(body.length() - 2);
        assertThrows(IllegalStateException.class, () -> box.decrypt(tampered));
    }

    @Test
    void wrongKeyCannotDecrypt() {
        CryptoBox other = new CryptoBox(
                "BAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", HMAC_KEY);
        String stored = box.encrypt("13812345678");
        assertThrows(IllegalStateException.class, () -> other.decrypt(stored));
    }

    @Test
    void hmacDeterministicAndDistinct() {
        assertEquals(box.hmac("13812345678"), box.hmac("13812345678"));
        assertNotEquals(box.hmac("13812345678"), box.hmac("13812345679"));
        // 同钥两盒结果一致(等值检索契约);不同钥结果不同
        CryptoBox twin = new CryptoBox(AES_KEY, HMAC_KEY);
        assertEquals(box.hmac("13812345678"), twin.hmac("13812345678"));
        CryptoBox stranger = new CryptoBox(AES_KEY,
                "JCEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=");
        assertNotEquals(box.hmac("13812345678"), stranger.hmac("13812345678"));
        // 形状:64 位十六进制小写
        String mac = box.hmac("13812345678");
        assertEquals(64, mac.length());
        assertEquals(mac, mac.toLowerCase());
        // 空值契约:null 透传,空白返回空串
        assertNull(box.hmac(null));
        assertEquals("", box.hmac("  "));
    }

    @Test
    void maskPhoneKeepsHeadAndTail() {
        assertEquals("138****5678", CryptoBox.maskPhone("13812345678"));
        assertEquals("123****5678", CryptoBox.maskPhone("12345678"));
        assertEquals("****", CryptoBox.maskPhone("1234567"));
        assertNull(CryptoBox.maskPhone(null));
        assertEquals("", CryptoBox.maskPhone(""));
    }

    @Test
    void maskAccountShowsTailOnly() {
        assertEquals("****7890", CryptoBox.maskAccount("6222123456787890"));
        assertEquals("****", CryptoBox.maskAccount("123"));
        assertEquals("****", CryptoBox.maskAccount(null));
    }
}
