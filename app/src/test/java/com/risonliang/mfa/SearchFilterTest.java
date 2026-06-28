/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa;

import com.risonliang.mfa.model.OtpAccount;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 验证 T1 主列表搜索的过滤逻辑（issuer / account 任一字段大小写不敏感包含命中）。
 * 与 MainActivity 中的 matches 实现保持一致。
 */
public class SearchFilterTest {

    /** 复制 MainActivity 中的 matches 语义，单测里独立实现，避免依赖 Android。 */
    private static boolean matches(OtpAccount acc, String needleLower) {
        if (acc == null) {
            return false;
        }
        if (acc.issuer != null
                && acc.issuer.toLowerCase(Locale.ROOT).contains(needleLower)) {
            return true;
        }
        if (acc.account != null
                && acc.account.toLowerCase(Locale.ROOT).contains(needleLower)) {
            return true;
        }
        return false;
    }

    private static OtpAccount mk(String issuer, String account) {
        OtpAccount a = new OtpAccount();
        a.issuer = issuer;
        a.account = account;
        a.secret = "JBSWY3DPEHPK3PXP";
        return a;
    }

    @Test
    public void caseInsensitive_matchesIssuer() {
        assertTrue(matches(mk("GoOgle", "u@x.com"), "goo"));
        assertTrue(matches(mk("github", "me"), "HUB"));
    }

    @Test
    public void caseInsensitive_matchesAccount() {
        assertTrue(matches(mk("Acme", "alice@example.com"), "ALI"));
    }

    @Test
    public void emptyQuery_isCallerResponsibility_butSubstringStillWorks() {
        // 空字符串 contains 永远 true；MainActivity 上层已做空字符串短路。
        assertTrue(matches(mk("Anything", "user"), ""));
    }

    @Test
    public void filterPipeline_returnsOnlyHits() {
        List<OtpAccount> all = new ArrayList<>();
        all.add(mk("Google", "alice@gmail.com"));
        all.add(mk("GitHub", "bob"));
        all.add(mk("Acme", "carol@acme.io"));

        List<OtpAccount> hits = new ArrayList<>();
        for (OtpAccount a : all) {
            if (matches(a, "git")) {
                hits.add(a);
            }
        }
        assertEquals(1, hits.size());
        assertEquals("GitHub", hits.get(0).issuer);
    }
}
