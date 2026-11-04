package com.zhj.panxia;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.View;

/** 极简 Markdown 渲染：标题 / 粗体 / 行内代码 / 围栏代码块 / 列表 / 引用 / 链接。 */
public final class Md {

    private Md() {
    }

    public static CharSequence render(final Context c, String src, int accent, int dim) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = src.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        boolean inCode = false;
        StringBuilder code = new StringBuilder();

        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("```")) {
                if (inCode) {
                    appendCode(out, code.toString());
                    code.setLength(0);
                }
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                code.append(line).append('\n');
                continue;
            }
            if (t.isEmpty()) {
                out.append('\n');
                continue;
            }
            if (t.startsWith("---") || t.startsWith("***")) {
                out.append("────────────\n");
                continue;
            }
            int level = 0;
            while (level < t.length() && t.charAt(level) == '#') {
                level++;
            }
            if (level > 0 && level <= 6 && level < t.length() && t.charAt(level) == ' ') {
                String text = t.substring(level + 1);
                int st = out.length();
                inline(out, text, accent, dim);
                float size = level == 1 ? 1.6f : level == 2 ? 1.35f : 1.15f;
                out.setSpan(new RelativeSizeSpan(size), st, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new StyleSpan(Typeface.BOLD), st, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(accent), st, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                continue;
            }
            if (t.startsWith("> ")) {
                int st = out.length();
                out.append("│ ");
                inline(out, t.substring(2), accent, dim);
                out.setSpan(new ForegroundColorSpan(dim), st, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                continue;
            }
            if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")) {
                out.append("•  ");
                inline(out, t.substring(2), accent, dim);
                out.append('\n');
                continue;
            }
            inline(out, line, accent, dim);
            out.append('\n');
        }
        if (inCode && code.length() > 0) {
            appendCode(out, code.toString());
        }
        return out;
    }

    private static void appendCode(SpannableStringBuilder out, String code) {
        int st = out.length();
        out.append(code);
        if (!code.endsWith("\n")) {
            out.append('\n');
        }
        out.setSpan(new TypefaceSpan("monospace"), st, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new BackgroundColorSpan(0x22888888), st, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void inline(SpannableStringBuilder sb, String s, final int accent, int dim) {
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i) {
                    int st = sb.length();
                    sb.append(s, i + 1, end);
                    sb.setSpan(new TypefaceSpan("monospace"), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new BackgroundColorSpan(0x22888888), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if (ch == '*' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("**", i + 2);
                if (end > i) {
                    int st = sb.length();
                    sb.append(s, i + 2, end);
                    sb.setSpan(new StyleSpan(Typeface.BOLD), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if (ch == '[') {
                int mid = s.indexOf("](", i);
                int end = mid < 0 ? -1 : s.indexOf(')', mid);
                if (end > mid) {
                    final String url = s.substring(mid + 2, end);
                    int st = sb.length();
                    sb.append(s, i + 1, mid);
                    sb.setSpan(new ForegroundColorSpan(accent), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ClickableSpan() {
                        @Override
                        public void onClick(View w) {
                            try {
                                w.getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                            } catch (Exception ignored) {
                            }
                        }

                        @Override
                        public void updateDrawState(TextPaint ds) {
                            ds.setUnderlineText(true);
                            ds.setColor(accent);
                        }
                    }, st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            sb.append(ch);
            i++;
        }
    }
}
