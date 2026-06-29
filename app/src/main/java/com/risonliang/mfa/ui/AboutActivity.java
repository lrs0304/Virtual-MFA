/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.widget.Toolbar;
import com.risonliang.mfa.BuildConfig;
import com.risonliang.mfa.R;

/**
 * 「关于」页：展示应用介绍 / 开源链接 / 赞赏入口。
 *
 * 设计原则：
 *  1. 严格遵循 {@link BaseSecureActivity}，与主页一致地启用 FLAG_SECURE 与解锁路由，
 *     避免出现"主页要解锁、关于页能直接看"的安全孔洞。
 *  2. 不申请任何额外权限；不在 Manifest 加 &lt;queries&gt; 包可见性声明 ——
 *     这意味着我们不主动探测支付宝是否安装，而是直接尝试拉起，
 *     失败时用 ActivityNotFoundException 兜底降级到 https 链接（系统浏览器）。
 *     这样 Manifest 保持"零联网、零探测"的最小披露原则。
 *  3. 跳转链路双保险：
 *       a) 优先 alipayqr:// 私有 schema，装了支付宝可一键直达赞赏页（带账号）；
 *       b) Fallback 到 https://qr.alipay.com/...，由系统浏览器或扫一扫接管，
 *          这条路径永不失败（除非设备没有任何浏览器，那种极端机型再 toast）。
 */
public final class AboutActivity extends BaseSecureActivity {

    /**
     * 支付宝赞赏的收款链接（risonliang 个人收款码识别得到的固定 URL）。
     * 同时被 alipayqr:// 私有 schema 与 https fallback 复用。
     */
    private static final String kAlipayDonateUrl =
            "https://qr.alipay.com/fkx18772qmnt6hok1bphm3c";

    /** 展示用的支付宝账号文案（不参与跳转，仅给用户视觉确认收款人）。 */
    private static final String kAlipayAccount = "1415082559@qq.com";

    /** 开源仓库 URL，点击 GitHub 卡片用浏览器打开。 */
    private static final String kGithubUrl =
            "https://github.com/lrs0304/Virtual-MFA";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        InsetsUtils.applySidesAndBottomAsPadding(
                findViewById(R.id.content_about));

        // 版本号取自 BuildConfig，无需手维护字符串，debug 包会自动带 -debug 后缀。
        TextView tvVersion = findViewById(R.id.tv_app_version);
        tvVersion.setText("v" + BuildConfig.VERSION_NAME);

        // GitHub 卡片：URL 文本直显，整卡可点击，浏览器打开。
        TextView tvGithubUrl = findViewById(R.id.tv_github_url);
        tvGithubUrl.setText(kGithubUrl);
        findViewById(R.id.card_github).setOnClickListener(
                v -> openInBrowser(kGithubUrl));

        // 支付宝卡片：副标题展示账号便于用户视觉核对收款人。
        TextView tvAlipayAccount = findViewById(R.id.tv_donate_alipay_account);
        tvAlipayAccount.setText(getString(
                R.string.about_donate_alipay_subtitle, kAlipayAccount));
        findViewById(R.id.card_donate_alipay).setOnClickListener(
                v -> openAlipayDonate());
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    /**
     * 拉起支付宝转账页。优先走私有 schema 直达赞赏页；失败则降级到 https。
     *
     * 之所以不预先 resolveActivity 探测可达性：
     *  - 探测需要在 Manifest 加 &lt;queries&gt; 声明，与"零外部依赖披露"原则相悖；
     *  - try / catch 一次性失败成本极低，反而把决策权让给系统标准的 Intent 路由。
     */
    private void openAlipayDonate() {
        // alipayqr:// 是支付宝官方 App 在 manifest 里注册的私有 scheme，
        // saId=10000007 表示"扫一扫"入口，qrcode 参数即真实的收款 URL。
        // 该格式经过多版本支付宝 App 验证稳定，是社区公认的"打开转账页"姿势。
        String alipaySchema = "alipayqr://platformapi/startapp?saId=10000007"
                + "&qrcode=" + Uri.encode(kAlipayDonateUrl);
        Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(alipaySchema));
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(it);
            return;
        } catch (ActivityNotFoundException ignore) {
            // 没装支付宝或私有 schema 在用户机型上被禁，fallback 到 https。
        }
        // Fallback：用 https 链接走系统浏览器，浏览器内点击通常会再次唤起支付宝。
        if (!openInBrowser(kAlipayDonateUrl)) {
            Toast.makeText(this, R.string.about_donate_alipay_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 用系统默认浏览器打开 url。返回是否成功拉起，便于上层做兜底提示。
     */
    private boolean openInBrowser(String url) {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
            return true;
        } catch (ActivityNotFoundException ignore) {
            return false;
        }
    }
}
