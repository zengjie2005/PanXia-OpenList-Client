package com.zhj.panxia;

import android.webkit.MimeTypeMap;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 文件类型判断：决定点击后走哪个预览器。 */
public final class Mime {

    public enum Kind {
        IMAGE, VIDEO, AUDIO, TEXT, PDF, OFFICE, ARCHIVE, OTHER
    }

    private static final Set<String> IMAGE = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "jfif"));

    private static final Set<String> VIDEO = new HashSet<>(Arrays.asList(
            "mp4", "m4v", "mkv", "webm", "mov", "3gp", "avi", "ts", "flv", "wmv", "mpg", "mpeg"));

    private static final Set<String> AUDIO = new HashSet<>(Arrays.asList(
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "ape", "amr", "mid"));

    private static final Set<String> TEXT = new HashSet<>(Arrays.asList(
            "txt", "text", "md", "markdown", "log", "json", "json5", "xml", "yml", "yaml",
            "ini", "cfg", "conf", "properties", "env", "csv", "tsv", "srt", "ass", "vtt",
            "js", "mjs", "cjs", "jsx", "ts", "tsx", "vue", "svelte",
            "java", "kt", "kts", "scala", "groovy", "gradle", "py", "rb", "php", "go", "rs",
            "c", "h", "cc", "cpp", "cxx", "hpp", "cs", "m", "mm", "swift", "dart", "lua", "pl",
            "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1", "psm1",
            "sql", "html", "htm", "xhtml", "css", "scss", "sass", "less", "styl",
            "toml", "tex", "gitignore", "dockerfile", "makefile", "license", "readme"));

    private static final Set<String> OFFICE = new HashSet<>(Arrays.asList(
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "wps", "et", "dps"));

    private static final Set<String> ARCHIVE = new HashSet<>(Arrays.asList(
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "iso"));

    private Mime() {
    }

    public static String ext(String name) {
        if (name == null) {
            return "";
        }
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) {
            return "";
        }
        return name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    public static Kind kind(String name) {
        String e = ext(name);
        if (e.isEmpty()) {
            return Kind.OTHER;
        }
        if (IMAGE.contains(e)) {
            return Kind.IMAGE;
        }
        if (VIDEO.contains(e)) {
            return Kind.VIDEO;
        }
        if (AUDIO.contains(e)) {
            return Kind.AUDIO;
        }
        if (e.equals("pdf")) {
            return Kind.PDF;
        }
        if (OFFICE.contains(e)) {
            return Kind.OFFICE;
        }
        if (TEXT.contains(e)) {
            return Kind.TEXT;
        }
        if (ARCHIVE.contains(e)) {
            return Kind.ARCHIVE;
        }
        return Kind.OTHER;
    }

    /** 是否能在 App 内直接预览（图片 / 音视频 / 文本） */
    public static boolean inline(String name) {
        Kind k = kind(name);
        return k == Kind.IMAGE || k == Kind.VIDEO || k == Kind.AUDIO || k == Kind.TEXT;
    }

    /** 猜 MIME，用于交给系统播放器 / 查看器 */
    public static String mime(String name) {
        String e = ext(name);
        String m = e.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(e);
        if (m != null) {
            return m;
        }
        switch (kind(name)) {
            case IMAGE:
                return "image/*";
            case VIDEO:
                return "video/*";
            case AUDIO:
                return "audio/*";
            case TEXT:
                return "text/plain";
            case PDF:
                return "application/pdf";
            case OFFICE:
                return "application/octet-stream";
            case ARCHIVE:
                return "application/zip";
            default:
                return "application/octet-stream";
        }
    }

    /** 文本查看器用的语法提示（仅影响是否用等宽字体 / Markdown 渲染） */
    public static boolean isMarkdown(String name) {
        String e = ext(name);
        return "md".equals(e) || "markdown".equals(e);
    }

    public static boolean isCode(String name) {
        String e = ext(name);
        Kind k = kind(name);
        if (k != Kind.TEXT) {
            return false;
        }
        switch (e) {
            case "txt":
            case "text":
            case "md":
            case "markdown":
            case "log":
            case "csv":
            case "tsv":
            case "srt":
            case "vtt":
                return false;
            default:
                return true;
        }
    }
}
