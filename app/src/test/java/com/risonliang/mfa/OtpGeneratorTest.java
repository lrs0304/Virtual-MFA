/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa;

import com.risonliang.mfa.crypto.Base32;
import com.risonliang.mfa.crypto.OtpGenerator;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

/**
 * TOTP 单元测试。RFC 6238 标准测试向量。
 */
public class OtpGeneratorTest {

    // RFC 6238 Appendix B 标准测试向量（SHA1, key="12345678901234567890"）
    private static final String SECRET_BASE32 =
            "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"; // 即 "12345678901234567890"

    @Test
    public void totp_rfc6238_sha1_t0_59() {
        // T = 59s -> counter = 1
        String code = OtpGenerator.totp(SECRET_BASE32, "SHA1", 8, 30,
                59L * 1000L);
        assertEquals("94287082", code);
    }

    @Test
    public void totp_rfc6238_sha1_t1111111109() {
        String code = OtpGenerator.totp(SECRET_BASE32, "SHA1", 8, 30,
                1111111109L * 1000L);
        assertEquals("07081804", code);
    }

    @Test
    public void totp_rfc6238_sha1_t1234567890() {
        String code = OtpGenerator.totp(SECRET_BASE32, "SHA1", 8, 30,
                1234567890L * 1000L);
        assertEquals("89005924", code);
    }

    @Test
    public void totp_default_6digits() {
        String code = OtpGenerator.totp(SECRET_BASE32, "SHA1", 6, 30,
                59L * 1000L);
        assertEquals(6, code.length());
        assertEquals("287082", code);
    }

    @Test
    public void remaining_seconds_correct() {
        int r = OtpGenerator.remainingSeconds(30, 0L);
        assertEquals(30, r);
        r = OtpGenerator.remainingSeconds(30, 25_000L);
        assertEquals(5, r);
    }

    @Test
    public void base32_validation() {
        assertTrue(Base32.isValid("JBSWY3DPEHPK3PXP"));
        assertTrue(Base32.isValid("jbswy3dp ehpk3pxp"));
        assertFalse(Base32.isValid("not base32!!!"));
        assertFalse(Base32.isValid(""));
    }

    @Test
    public void base32_decode_roundtrip() {
        byte[] data = Base32.decode("JBSWY3DPEHPK3PXP");
        // "Hello!\xDE\xAD"
        assertEquals(10, data.length);
    }

    @Test
    public void base32_encode_decode_roundtrip() {
        byte[] original = "Hello World".getBytes();
        String encoded = Base32.encode(original);
        byte[] decoded = Base32.decode(encoded);
        assertEquals(new String(original), new String(decoded));
    }

    @Test
    public void base32_encode_known_value() {
        // "12345678901234567890" -> GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ
        byte[] data = "12345678901234567890".getBytes();
        String encoded = Base32.encode(data);
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", encoded);
    }

    @Test
    public void hotp_rfc4226_test_vectors() {
        // RFC 4226 Appendix D 测试向量
        // Secret = "12345678901234567890" (ASCII)
        String[] expected = {
                "755224", "287082", "359152", "969429", "338314",
                "254676", "287922", "162583", "399871", "520489"
        };
        for (int i = 0; i < expected.length; i++) {
            String code = OtpGenerator.hotp(SECRET_BASE32, "SHA1", 6, i);
            assertEquals("HOTP counter=" + i, expected[i], code);
        }
    }
}
