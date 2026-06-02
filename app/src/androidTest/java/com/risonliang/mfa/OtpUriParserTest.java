/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa;

import com.risonliang.mfa.data.OtpUriParser;
import com.risonliang.mfa.model.OtpAccount;
import org.junit.Test;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import static org.junit.Assert.*;

/**
 * OtpUriParser 单元测试（需要 Android 框架支持 android.net.Uri）。
 */
@RunWith(AndroidJUnit4.class)
public class OtpUriParserTest {

    @Test
    public void parse_totp_standard() {
        String uri = "otpauth://totp/Google:user@gmail.com"
                + "?secret=JBSWY3DPEHPK3PXP&issuer=Google"
                + "&algorithm=SHA1&digits=6&period=30";
        OtpAccount acc = OtpUriParser.parse(uri);
        assertNotNull(acc);
        assertEquals("Google", acc.issuer);
        assertEquals("user@gmail.com", acc.account);
        assertEquals("JBSWY3DPEHPK3PXP", acc.secret);
        assertEquals("SHA1", acc.algorithm);
        assertEquals(6, acc.digits);
        assertEquals(30, acc.period);
        assertEquals(OtpAccount.TYPE_TOTP, acc.type);
    }

    @Test
    public void parse_hotp_with_counter() {
        String uri = "otpauth://hotp/Service:me@example.com"
                + "?secret=JBSWY3DPEHPK3PXP&counter=42";
        OtpAccount acc = OtpUriParser.parse(uri);
        assertNotNull(acc);
        assertEquals(OtpAccount.TYPE_HOTP, acc.type);
        assertEquals(42, acc.counter);
        assertEquals("Service", acc.issuer);
        assertEquals("me@example.com", acc.account);
    }

    @Test
    public void parse_minimal_totp() {
        String uri = "otpauth://totp/MyApp?secret=JBSWY3DPEHPK3PXP";
        OtpAccount acc = OtpUriParser.parse(uri);
        assertNotNull(acc);
        assertEquals("MyApp", acc.account);
        assertEquals("JBSWY3DPEHPK3PXP", acc.secret);
        assertEquals(OtpAccount.DEFAULT_ALGO, acc.algorithm);
        assertEquals(OtpAccount.DEFAULT_DIGITS, acc.digits);
        assertEquals(OtpAccount.DEFAULT_PERIOD, acc.period);
    }

    @Test
    public void parse_invalid_scheme() {
        assertNull(OtpUriParser.parse("https://example.com"));
        assertNull(OtpUriParser.parse("otpauth://steam/test?secret=ABC"));
        assertNull(OtpUriParser.parse(null));
        assertNull(OtpUriParser.parse(""));
    }

    @Test
    public void parse_invalid_secret() {
        String uri = "otpauth://totp/Test?secret=INVALID!!!";
        assertNull(OtpUriParser.parse(uri));
    }

    @Test
    public void parse_sha256_algorithm() {
        String uri = "otpauth://totp/Test?secret=JBSWY3DPEHPK3PXP"
                + "&algorithm=SHA256&digits=8&period=60";
        OtpAccount acc = OtpUriParser.parse(uri);
        assertNotNull(acc);
        assertEquals("SHA256", acc.algorithm);
        assertEquals(8, acc.digits);
        assertEquals(60, acc.period);
    }
}
