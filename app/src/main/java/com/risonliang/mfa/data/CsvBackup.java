/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.data;

import com.risonliang.mfa.crypto.Base32;
import com.risonliang.mfa.model.OtpAccount;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV 明文备份编解码器（RFC4180 子集）。
 *
 * 列定义（首行表头，固定顺序）：
 *   issuer,account,secret,algorithm,digits,period
 *
 * 安全提示：CSV 为明文导出，包含 secret 原文，仅用于跨工具迁移；
 * 调用方必须在 UI 上向用户做高风险确认。
 */
public final class CsvBackup {

    private static final String[] HEADERS = {
            "issuer", "account", "secret", "type", "algorithm",
            "digits", "period", "counter"
    };

    private CsvBackup() {}

    /** 将账号列表以 CSV（UTF-8，不带 BOM）写入流。 */
    public static void export(List<OtpAccount> accounts, OutputStream out)
            throws Exception {
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        writeRow(w, HEADERS);
        for (OtpAccount a : accounts) {
            writeRow(w, new String[]{
                    nullToEmpty(a.issuer),
                    nullToEmpty(a.account),
                    nullToEmpty(a.secret),
                    a.type == null ? OtpAccount.TYPE_TOTP : a.type,
                    a.algorithm == null ? OtpAccount.DEFAULT_ALGO : a.algorithm,
                    Integer.toString(a.digits == 0
                            ? OtpAccount.DEFAULT_DIGITS : a.digits),
                    Integer.toString(a.period == 0
                            ? OtpAccount.DEFAULT_PERIOD : a.period),
                    Long.toString(a.counter)
            });
        }
        w.flush();
    }

    /** 从 CSV 流读取账号列表。会跳过 secret 不合法的行。 */
    public static List<OtpAccount> importFrom(InputStream in) throws Exception {
        List<OtpAccount> result = new ArrayList<>();
        BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder allBuf = new StringBuilder();
        char[] cbuf = new char[4096];
        int n;
        while ((n = br.read(cbuf)) > 0) {
            allBuf.append(cbuf, 0, n);
        }
        List<List<String>> rows = parseAll(allBuf.toString());
        if (rows.isEmpty()) {
            return result;
        }
        Map<String, Integer> idx = headerIndex(rows.get(0));
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.isEmpty() || (row.size() == 1 && row.get(0).isEmpty())) {
                continue;
            }
            String secret = cell(row, idx, "secret");
            if (secret == null) {
                continue;
            }
            secret = secret.replace(" ", "").trim();
            if (!Base32.isValid(secret)) {
                continue;
            }
            OtpAccount a = new OtpAccount();
            a.issuer = cell(row, idx, "issuer");
            a.account = cell(row, idx, "account");
            a.secret = secret;
            String type = cell(row, idx, "type");
            a.type = (type == null || type.isEmpty())
                    ? OtpAccount.TYPE_TOTP : type.toLowerCase();
            String algo = cell(row, idx, "algorithm");
            a.algorithm = (algo == null || algo.isEmpty())
                    ? OtpAccount.DEFAULT_ALGO : algo.toUpperCase();
            a.digits = parseIntOr(cell(row, idx, "digits"),
                    OtpAccount.DEFAULT_DIGITS);
            a.period = parseIntOr(cell(row, idx, "period"),
                    OtpAccount.DEFAULT_PERIOD);
            a.counter = parseLongOr(cell(row, idx, "counter"), 0);
            result.add(a);
        }
        return result;
    }

    private static Map<String, Integer> headerIndex(List<String> header) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String key = header.get(i) == null
                    ? "" : header.get(i).trim().toLowerCase();
            m.put(key, i);
        }
        // 兼容缺表头：若没有 'secret' 列，则按固定顺序兜底。
        if (!m.containsKey("secret")) {
            m.clear();
            for (int i = 0; i < HEADERS.length; i++) {
                m.put(HEADERS[i], i);
            }
        }
        return m;
    }

    private static String cell(List<String> row, Map<String, Integer> idx,
                               String key) {
        Integer i = idx.get(key);
        if (i == null || i < 0 || i >= row.size()) {
            return null;
        }
        String v = row.get(i);
        return v == null ? null : v.trim();
    }

    private static int parseIntOr(String s, int def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLongOr(String s, long def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 写一行 CSV，自动处理需要转义的字段。 */
    private static void writeRow(Writer w, String[] cols) throws Exception {
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) {
                w.write(',');
            }
            w.write(escape(cols[i]));
        }
        w.write("\r\n");
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        boolean needQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needQuote) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                sb.append('"').append('"');
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * 解析整段 CSV 文本为二维列表（行 / 列），符合 RFC4180：
     *  - 字段可以用双引号包围
     *  - 引号内的双引号用 "" 转义
     *  - 引号内允许换行
     */
    private static List<List<String>> parseAll(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int len = text.length();
        while (i < len) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    } else {
                        inQuotes = false;
                        i++;
                        continue;
                    }
                } else {
                    field.append(c);
                    i++;
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                    i++;
                } else if (c == ',') {
                    cur.add(field.toString());
                    field.setLength(0);
                    i++;
                } else if (c == '\r') {
                    // 跳过 \r，等待 \n 处理换行；若单独 \r 也按行结束
                    if (i + 1 < len && text.charAt(i + 1) == '\n') {
                        i++;
                        continue;
                    }
                    cur.add(field.toString());
                    field.setLength(0);
                    rows.add(cur);
                    cur = new ArrayList<>();
                    i++;
                } else if (c == '\n') {
                    cur.add(field.toString());
                    field.setLength(0);
                    rows.add(cur);
                    cur = new ArrayList<>();
                    i++;
                } else {
                    field.append(c);
                    i++;
                }
            }
        }
        // 尾行
        if (field.length() > 0 || !cur.isEmpty()) {
            cur.add(field.toString());
            rows.add(cur);
        }
        return rows;
    }

    /** 单元测试可见：用于排查 header 顺序。 */
    public static List<String> headers() {
        return Arrays.asList(HEADERS);
    }
}
