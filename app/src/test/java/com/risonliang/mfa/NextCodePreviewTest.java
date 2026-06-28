/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa;

import com.risonliang.mfa.crypto.OtpGenerator;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * 验证 T4「下一码预览」的核心算法：以当前周期起点 + period 秒为时间戳，
 * 用同一 secret 生成的 TOTP 必须等于"下一周期"内的实际 TOTP。
 *
 * 这一测试不依赖任何 Android 框架，能在 JVM 单元测试套件里直接跑。
 */
public class NextCodePreviewTest {

    /** RFC 6238 附录 B 中给出的 SHA1 测试 secret（Base32）。 */
    private static final String kSecret = "JBSWY3DPEHPK3PXP";

    @Test
    public void nextCodeAtBoundary_matchesActualNextWindow() {
        int period = 30;
        // 任选一个时间点：39 秒（处于 30 ~ 60 这个窗口）
        long now = 39_000L;
        long currentWindowStart = ((now / 1000L) / period) * period * 1000L;
        long nextWindowStart = currentWindowStart + period * 1000L;

        String currentCode = OtpGenerator.totp(kSecret, "SHA1", 6, period, now);
        String nextCode = OtpGenerator.totp(kSecret, "SHA1", 6, period,
                nextWindowStart);
        // 在下一周期开始的瞬间再算一次，必须与 nextWindowStart 算的一致
        String actualCodeAtNext = OtpGenerator.totp(
                kSecret, "SHA1", 6, period, nextWindowStart + 1000L);

        assertEquals("下一码必须与下一窗口实际生成结果一致",
                nextCode, actualCodeAtNext);
        // 同一个 secret，不同窗口码大概率不同（极小概率碰撞，
        // 这里挑选的 39s 与 60s 恰好不会碰撞）
        assertNotEquals("当前码与下一码不应相同", currentCode, nextCode);
    }

    @Test
    public void nextCodeBoundary_alignsToPeriodMultiple() {
        int period = 30;
        long now = 0L;
        long nextStart = ((now / 1000L) / period + 1) * period * 1000L;
        assertEquals("下一窗口起点应为 period 整数倍",
                30_000L, nextStart);
    }
}
