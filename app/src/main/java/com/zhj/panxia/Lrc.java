package com.zhj.panxia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LRC 歌词解析：支持一行多个时间标签与 [offset:] 校正。 */
public final class Lrc {

    public static class Line {
        public final long time;
        public final String text;

        Line(long time, String text) {
            this.time = time;
            this.text = text;
        }
    }

    private static final Pattern TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    public List<Line> lines = new ArrayList<>();
    public String title = "";
    public String artist = "";

    public static Lrc parse(String raw) {
        Lrc lrc = new Lrc();
        if (raw == null || raw.trim().isEmpty()) {
            return lrc;
        }
        long offset = 0;
        List<Line> out = new ArrayList<>();
        for (String row : raw.split("\\r?\\n")) {
            String line = row.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[ti:")) {
                lrc.title = cut(line);
                continue;
            }
            if (line.startsWith("[ar:")) {
                lrc.artist = cut(line);
                continue;
            }
            if (line.startsWith("[offset:")) {
                try {
                    offset = Long.parseLong(cut(line));
                } catch (NumberFormatException ignored) {
                }
                continue;
            }
            Matcher m = TAG.matcher(line);
            List<Long> times = new ArrayList<>();
            int lastEnd = 0;
            while (m.find()) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                long frac = 0;
                if (m.group(3) != null) {
                    String f = m.group(3);
                    frac = Long.parseLong(f);
                    frac = frac * (f.length() == 1 ? 100 : (f.length() == 2 ? 10 : 1));
                }
                times.add(min * 60000 + sec * 1000 + frac);
                lastEnd = m.end();
            }
            if (times.isEmpty()) {
                continue;
            }
            String text = line.substring(lastEnd).trim();
            for (long t : times) {
                out.add(new Line(t, text));
            }
        }
        Collections.sort(out, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                return Long.compare(a.time, b.time);
            }
        });
        if (offset != 0) {
            List<Line> shifted = new ArrayList<>();
            for (Line l : out) {
                shifted.add(new Line(Math.max(0, l.time - offset), l.text));
            }
            out = shifted;
        }
        lrc.lines = out;
        return lrc;
    }

    private static String cut(String line) {
        int i = line.indexOf(':');
        int j = line.lastIndexOf(']');
        if (i < 0) {
            return "";
        }
        return j > i ? line.substring(i + 1, j).trim() : line.substring(i + 1).trim();
    }

    /** 当前时间对应的行号；没有歌词时返回 -1 */
    public int indexAt(long ms) {
        if (lines.isEmpty()) {
            return -1;
        }
        int lo = 0;
        int hi = lines.size() - 1;
        int ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).time <= ms) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }
}
