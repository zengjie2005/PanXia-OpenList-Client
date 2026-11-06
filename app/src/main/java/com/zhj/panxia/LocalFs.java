package com.zhj.panxia;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 设备本地文件操作层。
 *
 * 刻意不引用任何 android.* 类，因此可以直接用 JDK 编译运行自检
 * （见 tools/selftest/LocalFsSelfCheck.java）。所有公开方法都不抛出
 * 未检查异常，失败统一以 OpException / Result 返回，便于界面给出明确提示。
 *
 * 兼容性：minSdk 24，只使用 java.io.File，不使用 java.nio.file /
 * java.util.Base64 / String.join 等在高版本才可用的 API。
 */
public final class LocalFs {

    private LocalFs() {
    }

    // ---------------------------------------------------------------- 常量

    public static final int SORT_NAME = 0;
    public static final int SORT_SIZE = 1;
    public static final int SORT_TIME = 2;
    public static final int SORT_TYPE = 3;

    /** 冲突时自动改名（最安全，默认） */
    public static final int CONFLICT_RENAME = 0;
    /** 冲突时跳过 */
    public static final int CONFLICT_SKIP = 1;
    /** 冲突时覆盖（会先删掉旧目标） */
    public static final int CONFLICT_OVERWRITE = 2;

    public static final String REASON_NOT_FOUND = "not_found";
    public static final String REASON_EXISTS = "exists";
    public static final String REASON_PERMISSION = "permission";
    public static final String REASON_SPACE = "space";
    public static final String REASON_CANCELLED = "cancelled";
    public static final String REASON_INVALID = "invalid";
    public static final String REASON_IO = "io";
    public static final String REASON_TOO_DEEP = "too_deep";

    private static final int BUFFER = 64 * 1024;
    private static final int MAX_DEPTH = 48;
    private static final int MAX_NAME = 180;
    private static final int MAX_UNIQUE = 999;
    private static final int MAX_SEARCH = 500;

    /** 操作是否被用户中断 */
    public interface Cancel {
        boolean cancelled();
    }

    /** 进度回调，done / total 为字节数；实现里已做节流，不会每个缓冲块都回调 */
    public interface Progress {
        void onProgress(long done, long total);
    }

    // ---------------------------------------------------------------- 数据结构

    public static final class Entry {
        public final String name;
        public final String path;
        public final long size;
        public final long modified;
        public final boolean dir;
        /** 小写扩展名（不含点），目录为空串 */
        public final String ext;

        public Entry(String name, String path, long size, long modified, boolean dir, String ext) {
            this.name = name;
            this.path = path;
            this.size = size;
            this.modified = modified;
            this.dir = dir;
            this.ext = ext == null ? "" : ext;
        }

        /** 稳定标识：同一目录下不会出现两条相同 path，用它做多选集合的键 */
        public String key() {
            return path;
        }

        @Override
        public String toString() {
            return (dir ? "[D] " : "[F] ") + name + " " + size + "B";
        }
    }

    /** 批量操作结果；每一项独立结算，单个失败不影响其他项 */
    public static final class Result {
        public final int ok;
        public final int skipped;
        public final List<String> errors;
        /** 成功产出的新路径，便于界面刷新定位 */
        public final List<String> produced;

        public Result(int ok, int skipped, List<String> errors, List<String> produced) {
            this.ok = ok;
            this.skipped = skipped;
            this.errors = errors == null ? new ArrayList<String>() : errors;
            this.produced = produced == null ? new ArrayList<String>() : produced;
        }

        public boolean allOk() {
            return errors.isEmpty();
        }
    }

    public static final class OpException extends IOException {
        public final String reason;
        public final String path;

        public OpException(String reason, String path, String message) {
            super(message);
            this.reason = reason;
            this.path = path;
        }
    }

    /** 回收站里的一条记录 */
    public static final class TrashItem {
        public final String id;
        public final String origin;
        public final String name;
        public final long time;
        public final long size;
        public final boolean dir;

        public TrashItem(String id, String origin, String name, long time, long size, boolean dir) {
            this.id = id;
            this.origin = origin;
            this.name = name;
            this.time = time;
            this.size = size;
            this.dir = dir;
        }
    }

    // ---------------------------------------------------------------- 名称与路径

    /** 清洗用户输入的名字：拒绝路径穿越、非法字符、保留名、超长名 */
    public static String safeName(String raw) throws OpException {
        if (raw == null) {
            throw new OpException(REASON_INVALID, "", "名称为空");
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            throw new OpException(REASON_INVALID, raw, "名称为空");
        }
        if (".".equals(s) || "..".equals(s)) {
            throw new OpException(REASON_INVALID, raw, "名称不合法");
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < MAX_NAME; i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|' || c < 0x20) {
                continue;
            }
            b.append(c);
        }
        String out = b.toString().trim();
        // 结尾的点或空格在部分系统上会被悄悄截掉，导致同名冲突
        while (out.endsWith(".") || out.endsWith(" ")) {
            out = out.substring(0, out.length() - 1).trim();
        }
        if (out.isEmpty()) {
            throw new OpException(REASON_INVALID, raw, "名称不合法");
        }
        return out;
    }

    /** 目标必须位于 root 之内，防止 ../ 穿越出可操作范围 */
    public static String resolve(String root, String child) throws OpException {
        File r = new File(root);
        File c = new File(child);
        File f = c.isAbsolute() ? c : new File(r, child);
        String rp;
        String fp;
        try {
            rp = r.getCanonicalPath();
            fp = f.getCanonicalPath();
        } catch (IOException e) {
            throw new OpException(REASON_IO, child, "路径无法解析");
        }
        if (!fp.equals(rp) && !fp.startsWith(rp + File.separator)) {
            throw new OpException(REASON_INVALID, fp, "路径超出允许范围");
        }
        return fp;
    }

    /** 生成不冲突的名字：a.txt -> a (1).txt -> a (2).txt */
    public static String uniqueName(String dir, String name) {
        File d = new File(dir);
        if (!new File(d, name).exists()) {
            return name;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; i <= MAX_UNIQUE; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (candidate.length() > MAX_NAME) {
                candidate = base.substring(0, Math.max(1, MAX_NAME - ext.length() - 6))
                        + " (" + i + ")" + ext;
            }
            if (!new File(d, candidate).exists()) {
                return candidate;
            }
        }
        return base + " (" + System.currentTimeMillis() + ")" + ext;
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    private static String parentOf(String path) {
        File p = new File(path).getParentFile();
        return p == null ? "" : p.getAbsolutePath();
    }

    private static void checkDir(String path, boolean writable) throws OpException {
        File f = new File(path);
        if (!f.exists()) {
            throw new OpException(REASON_NOT_FOUND, path, "目录不存在");
        }
        if (!f.isDirectory()) {
            throw new OpException(REASON_INVALID, path, "不是目录");
        }
        if (!f.canRead()) {
            throw new OpException(REASON_PERMISSION, path, "无法读取该目录");
        }
        if (writable && !f.canWrite()) {
            throw new OpException(REASON_PERMISSION, path, "没有写入权限");
        }
    }

    // ---------------------------------------------------------------- 列目录 / 搜索 / 排序

    /** 列目录；空目录返回空列表；不存在 / 不可读抛 OpException */
    public static List<Entry> list(String dir) throws OpException {
        checkDir(dir, false);
        File[] files = new File(dir).listFiles();
        List<Entry> out = new ArrayList<Entry>();
        if (files == null) {
            // listFiles 返回 null 通常是权限问题，Android 上并不总是抛异常
            throw new OpException(REASON_PERMISSION, dir, "无法读取该目录");
        }
        for (File f : files) {
            try {
                out.add(new Entry(f.getName(), f.getAbsolutePath(),
                        f.isDirectory() ? 0L : f.length(), f.lastModified(),
                        f.isDirectory(), f.isDirectory() ? "" : extOf(f.getName())));
            } catch (SecurityException e) {
                throw new OpException(REASON_PERMISSION, f.getAbsolutePath(), "没有访问权限");
            }
        }
        return out;
    }

    /** 在当前目录内递归搜索名字包含 query 的条目；有深度、数量上限，可中断 */
    public static List<Entry> search(String root, String query, Cancel cancel) throws OpException {
        checkDir(root, false);
        List<Entry> out = new ArrayList<Entry>();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.US);
        walk(new File(root), q, out, 0, cancel);
        return out;
    }

    private static void walk(File dir, String q, List<Entry> out, int depth, Cancel cancel)
            throws OpException {
        if (out.size() >= MAX_SEARCH || (cancel != null && cancel.cancelled())) {
            return;
        }
        if (depth > MAX_DEPTH) {
            throw new OpException(REASON_TOO_DEEP, dir.getAbsolutePath(), "目录层级过深");
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return; // 单个子目录读不了就跳过，不让整个搜索失败
        }
        for (File f : files) {
            if (out.size() >= MAX_SEARCH || (cancel != null && cancel.cancelled())) {
                return;
            }
            // 目录层级上限 + 结果上限共同防止符号链接造成的无限递归
            boolean isDir = f.isDirectory();
            if (q.isEmpty() || f.getName().toLowerCase(Locale.US).contains(q)) {
                out.add(new Entry(f.getName(), f.getAbsolutePath(),
                        isDir ? 0L : f.length(), f.lastModified(), isDir,
                        isDir ? "" : extOf(f.getName())));
            }
            if (isDir && !f.getName().startsWith(".PanXiaTrash")) {
                walk(f, q, out, depth + 1, cancel);
            }
        }
    }

    /** 目录优先，其次按 field 排序；名称用不区分大小写的中文友好比较 */
    public static void sort(List<Entry> items, int field, boolean desc) {
        final int f = field;
        Collections.sort(items, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int c = 0;
                if (a.dir != b.dir) {
                    c = a.dir ? -1 : 1;
                } else {
                    switch (f) {
                        case SORT_SIZE:
                            c = compareLong(a.size, b.size);
                            break;
                        case SORT_TIME:
                            c = compareLong(a.modified, b.modified);
                            break;
                        case SORT_TYPE:
                            c = a.ext.compareTo(b.ext);
                            if (c == 0) {
                                c = compareText(a.name, b.name);
                            }
                            break;
                        default:
                            c = compareText(a.name, b.name);
                            break;
                    }
                    if (c == 0) {
                        c = compareText(a.name, b.name);
                    }
                }
                // 目录始终在前，不受升降序影响，避免"升序时文件夹沉底"的困惑
                if (a.dir != b.dir) {
                    return a.dir ? -1 : 1;
                }
                return desc ? -c : c;
            }
        });
    }

    private static int compareLong(long a, long b) {
        return a < b ? -1 : (a == b ? 0 : 1);
    }

    private static int compareText(String a, String b) {
        String x = a == null ? "" : a;
        String y = b == null ? "" : b;
        int c = x.toLowerCase(Locale.US).compareTo(y.toLowerCase(Locale.US));
        return c != 0 ? c : x.compareTo(y);
    }

    /** 隐藏文件过滤交给界面，这里保留完整数据 */
    public static List<Entry> filterHidden(List<Entry> items, boolean showHidden) {
        if (showHidden) {
            return items;
        }
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : items) {
            if (!e.name.startsWith(".")) {
                out.add(e);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 新建 / 重命名

    public static Entry createDir(String dir, String rawName, int conflict) throws OpException {
        String name = safeName(rawName);
        checkDir(dir, true);
        File target = new File(dir, name);
        if (target.exists()) {
            if (conflict == CONFLICT_SKIP) {
                return null;
            }
            if (conflict != CONFLICT_OVERWRITE) {
                name = uniqueName(dir, name);
                target = new File(dir, name);
            } else if (target.isFile()) {
                if (!target.delete()) {
                    throw new OpException(REASON_IO, target.getAbsolutePath(), "无法覆盖同名文件");
                }
            } else {
                throw new OpException(REASON_EXISTS, target.getAbsolutePath(), "同名目录已存在");
            }
        }
        if (!target.mkdirs()) {
            throw new OpException(new File(dir).canWrite() ? REASON_IO : REASON_PERMISSION,
                    target.getAbsolutePath(), "创建文件夹失败");
        }
        return new Entry(name, target.getAbsolutePath(), 0, target.lastModified(), true, "");
    }

    public static Entry createFile(String dir, String rawName, int conflict) throws OpException {
        String name = safeName(rawName);
        checkDir(dir, true);
        File target = new File(dir, name);
        if (target.exists()) {
            if (conflict == CONFLICT_SKIP) {
                return null;
            }
            if (conflict != CONFLICT_OVERWRITE) {
                name = uniqueName(dir, name);
                target = new File(dir, name);
            } else if (!target.isFile()) {
                throw new OpException(REASON_EXISTS, target.getAbsolutePath(), "同名目录已存在");
            }
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(target, false);
            out.flush();
        } catch (IOException e) {
            throw new OpException(new File(dir).canWrite() ? REASON_IO : REASON_PERMISSION,
                    target.getAbsolutePath(), "创建文件失败：" + e.getMessage());
        } finally {
            closeQuietly(out);
        }
        return new Entry(name, target.getAbsolutePath(), 0, target.lastModified(), false, extOf(name));
    }

    /** 重命名；目标同名按冲突策略处理；不允许把目录移进自己 */
    public static Entry rename(String path, String rawNewName, int conflict) throws OpException {
        File src = new File(path);
        if (!src.exists()) {
            throw new OpException(REASON_NOT_FOUND, path, "文件不存在");
        }
        String name = safeName(rawNewName);
        String dir = parentOf(src.getAbsolutePath());
        File dirFile = new File(dir);
        if (!dirFile.canWrite()) {
            throw new OpException(REASON_PERMISSION, dir, "没有写入权限");
        }
        File dst = new File(dir, name);
        if (dst.exists()) {
            if (conflict == CONFLICT_SKIP) {
                return null;
            }
            if (conflict != CONFLICT_OVERWRITE) {
                name = uniqueName(dir, name);
                dst = new File(dir, name);
            } else {
                Result r = deletePermanent(Collections.singletonList(dst.getAbsolutePath()), null);
                if (!r.allOk()) {
                    throw new OpException(REASON_IO, dst.getAbsolutePath(), "无法覆盖同名项");
                }
            }
        }
        if (src.isDirectory() && isInside(dst, src)) {
            throw new OpException(REASON_INVALID, dst.getAbsolutePath(), "不能把目录移到自己里面");
        }
        if (!src.renameTo(dst)) {
            throw new OpException(REASON_IO, path, "重命名失败（可能被占用或跨存储）");
        }
        return new Entry(name, dst.getAbsolutePath(), dst.isDirectory() ? 0 : dst.length(),
                dst.lastModified(), dst.isDirectory(), dst.isDirectory() ? "" : extOf(name));
    }

    private static boolean isInside(File child, File parent) {
        try {
            String c = child.getCanonicalPath();
            String p = parent.getCanonicalPath();
            return c.startsWith(p + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 复制 / 移动

    /** 复制一批路径到 destDir；逐项结算，单项失败不影响其余 */
    public static Result copy(List<String> paths, String destDir, int conflict,
                              Cancel cancel, Progress progress) {
        List<String> errors = new ArrayList<String>();
        List<String> produced = new ArrayList<String>();
        int ok = 0;
        int skipped = 0;
        try {
            checkDir(destDir, true);
        } catch (OpException e) {
            errors.add(e.getMessage());
            return new Result(0, 0, errors, produced);
        }
        long total = 0;
        for (String p : paths) {
            File f = new File(p);
            if (f.exists()) {
                total += treeSize(f);
            }
        }
        long free = freeSpace(destDir);
        if (free > 0 && total > free) {
            errors.add("存储空间不足，需要约 " + total + " 字节，可用 " + free + " 字节");
            return new Result(0, 0, errors, produced);
        }
        final long[] done = {0L};
        for (String p : paths) {
            if (cancel != null && cancel.cancelled()) {
                errors.add("操作已中断，其余 " + (paths.size() - ok - skipped) + " 项未处理");
                break;
            }
            File src = new File(p);
            if (!src.exists()) {
                errors.add("不存在：" + src.getName());
                continue;
            }
            if (isInside(new File(destDir), src) && src.isDirectory()) {
                errors.add("不能把目录复制到自己里面：" + src.getName());
                continue;
            }
            try {
                String targetName = src.getName();
                File dst = new File(destDir, targetName);
                if (dst.exists()) {
                    if (conflict == CONFLICT_SKIP) {
                        skipped++;
                        continue;
                    }
                    if (conflict == CONFLICT_OVERWRITE) {
                        Result del = deletePermanent(
                                Collections.singletonList(dst.getAbsolutePath()), null);
                        if (!del.allOk()) {
                            errors.add("无法覆盖：" + targetName);
                            continue;
                        }
                    } else {
                        targetName = uniqueName(destDir, targetName);
                        dst = new File(destDir, targetName);
                    }
                }
                copyOne(src, dst, cancel, progress, done, total, 0);
                produced.add(dst.getAbsolutePath());
                ok++;
            } catch (OpException e) {
                errors.add(src.getName() + "：" + e.getMessage());
            }
        }
        return new Result(ok, skipped, errors, produced);
    }

    private static void copyOne(File src, File dst, Cancel cancel, Progress progress,
                                long[] done, long total, int depth) throws OpException {
        if (depth > MAX_DEPTH) {
            throw new OpException(REASON_TOO_DEEP, src.getAbsolutePath(), "目录层级过深");
        }
        if (cancel != null && cancel.cancelled()) {
            throw new OpException(REASON_CANCELLED, src.getAbsolutePath(), "操作已中断");
        }
        if (src.isDirectory()) {
            if (!dst.mkdirs() && !dst.isDirectory()) {
                throw new OpException(new File(dst.getParent()).canWrite()
                        ? REASON_IO : REASON_PERMISSION, dst.getAbsolutePath(), "创建目录失败");
            }
            File[] children = src.listFiles();
            if (children == null) {
                throw new OpException(REASON_PERMISSION, src.getAbsolutePath(), "无法读取目录");
            }
            for (File c : children) {
                copyOne(c, new File(dst, c.getName()), cancel, progress, done, total, depth + 1);
            }
            return;
        }
        File part = new File(dst.getAbsolutePath() + ".panxia-part");
        InputStream in = null;
        OutputStream out = null;
        boolean finished = false;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(part, false);
            byte[] buf = new byte[BUFFER];
            int n;
            long sinceReport = 0;
            while ((n = in.read(buf)) != -1) {
                if (cancel != null && cancel.cancelled()) {
                    throw new OpException(REASON_CANCELLED, src.getAbsolutePath(), "操作已中断");
                }
                out.write(buf, 0, n);
                done[0] += n;
                sinceReport += n;
                if (progress != null && sinceReport >= 256 * 1024) {
                    progress.onProgress(done[0], total);
                    sinceReport = 0;
                }
            }
            out.flush();
            closeQuietly(out);
            out = null;
            closeQuietly(in);
            in = null;
            if (dst.exists() && !dst.delete()) {
                throw new OpException(REASON_IO, dst.getAbsolutePath(), "无法替换目标文件");
            }
            if (!part.renameTo(dst)) {
                throw new OpException(REASON_IO, dst.getAbsolutePath(), "写入失败");
            }
            finished = true;
        } catch (OpException e) {
            throw e;
        } catch (IOException e) {
            throw new OpException(src.canRead() ? REASON_IO : REASON_PERMISSION,
                    src.getAbsolutePath(), e.getMessage() == null ? "复制失败" : e.getMessage());
        } finally {
            closeQuietly(out);
            closeQuietly(in);
            // 中断或失败都清掉半成品，绝不留下看起来完整的 .part
            if (!finished && part.exists()) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
        }
    }

    /** 移动：优先同存储 renameTo（瞬时），失败则复制成功后删源 */
    public static Result move(List<String> paths, String destDir, int conflict,
                              Cancel cancel, Progress progress) {
        List<String> errors = new ArrayList<String>();
        List<String> produced = new ArrayList<String>();
        List<String> needCopy = new ArrayList<String>();
        int ok = 0;
        int skipped = 0;
        try {
            checkDir(destDir, true);
        } catch (OpException e) {
            errors.add(e.getMessage());
            return new Result(0, 0, errors, produced);
        }
        for (String p : paths) {
            if (cancel != null && cancel.cancelled()) {
                errors.add("操作已中断");
                break;
            }
            File src = new File(p);
            if (!src.exists()) {
                errors.add("不存在：" + src.getName());
                continue;
            }
            if (isInside(new File(destDir), src) && src.isDirectory()) {
                errors.add("不能把目录移动到自己里面：" + src.getName());
                continue;
            }
            String targetName = src.getName();
            File dst = new File(destDir, targetName);
            if (dst.exists()) {
                if (conflict == CONFLICT_SKIP) {
                    skipped++;
                    continue;
                }
                if (conflict == CONFLICT_OVERWRITE) {
                    Result del = deletePermanent(
                            Collections.singletonList(dst.getAbsolutePath()), null);
                    if (!del.allOk()) {
                        errors.add("无法覆盖：" + targetName);
                        continue;
                    }
                } else {
                    targetName = uniqueName(destDir, targetName);
                    dst = new File(destDir, targetName);
                }
            }
            if (src.renameTo(dst)) {
                produced.add(dst.getAbsolutePath());
                ok++;
            } else {
                needCopy.add(p);
            }
        }
        for (String original : needCopy) {
            if (cancel != null && cancel.cancelled()) {
                errors.add("操作已中断");
                break;
            }
            Result one = copy(Collections.singletonList(original), destDir,
                    conflict, cancel, progress);
            ok += one.ok;
            skipped += one.skipped;
            errors.addAll(one.errors);
            produced.addAll(one.produced);
            // 只有这一项确实复制成功才删源；删不掉只提示，绝不误删数据
            if (one.ok > 0) {
                Result del = deletePermanent(Collections.singletonList(original), null);
                if (!del.allOk()) {
                    errors.add("已复制到目标，但源文件未能删除：" + new File(original).getName());
                }
            }
        }
        return new Result(ok, skipped, errors, produced);
    }

    // ---------------------------------------------------------------- 删除 / 回收站

    /** 删除到回收站：同存储 rename 是瞬时的，且可恢复；失败绝不直接删源 */
    public static Result trash(List<String> paths, String trashDir) {
        List<String> errors = new ArrayList<String>();
        List<String> produced = new ArrayList<String>();
        int ok = 0;
        File root = new File(trashDir);
        if (!root.exists() && !root.mkdirs()) {
            errors.add("无法创建回收站目录");
            return new Result(0, 0, errors, produced);
        }
        long stamp = System.currentTimeMillis();
        int seq = 0;
        for (String p : paths) {
            File src = new File(p);
            if (!src.exists()) {
                errors.add("不存在：" + src.getName());
                continue;
            }
            String id;
            File slot;
            do {
                id = stamp + "_" + (seq++);
                slot = new File(root, id);
            } while (slot.exists());
            if (!slot.mkdirs()) {
                errors.add("回收站写入失败：" + src.getName());
                continue;
            }
            File data = new File(slot, "data");
            boolean renamed = src.renameTo(data);
            if (!renamed) {
                //noinspection ResultOfMethodCallIgnored
                slot.delete();
                errors.add("无法移入回收站（可能跨存储或正被占用）：" + src.getName());
                continue;
            }
            if (!writeText(new File(slot, "origin.txt"), src.getAbsolutePath())) {
                errors.add("回收记录写入失败：" + src.getName()
                        + "（文件仍在 " + data.getAbsolutePath() + "）");
                continue;
            }
            produced.add(id);
            ok++;
        }
        return new Result(ok, 0, errors, produced);
    }

    public static List<TrashItem> listTrash(String trashDir) {
        List<TrashItem> out = new ArrayList<TrashItem>();
        File root = new File(trashDir);
        File[] slots = root.listFiles();
        if (slots == null) {
            return out;
        }
        for (File slot : slots) {
            if (!slot.isDirectory()) {
                continue;
            }
            String origin = readText(new File(slot, "origin.txt"));
            File data = new File(slot, "data");
            if (origin == null || !data.exists()) {
                continue;
            }
            out.add(new TrashItem(slot.getName(), origin, new File(origin).getName(),
                    slot.lastModified(), data.isDirectory() ? 0 : data.length(),
                    data.isDirectory()));
        }
        Collections.sort(out, new Comparator<TrashItem>() {
            @Override
            public int compare(TrashItem a, TrashItem b) {
                return compareLong(b.time, a.time);
            }
        });
        return out;
    }

    /** 恢复指定记录；目标已存在时自动改名，不覆盖现有数据 */
    public static Result restore(String trashDir, List<String> ids) {
        List<String> errors = new ArrayList<String>();
        List<String> produced = new ArrayList<String>();
        int ok = 0;
        for (String id : ids) {
            File slot = new File(trashDir, safeId(id));
            String origin = readText(new File(slot, "origin.txt"));
            File data = new File(slot, "data");
            if (origin == null || !data.exists()) {
                errors.add("记录已损坏：" + id);
                continue;
            }
            File target = new File(origin);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                errors.add("原目录已不存在且无法重建：" + origin);
                continue;
            }
            if (target.exists()) {
                target = new File(parent, uniqueName(parent.getAbsolutePath(), target.getName()));
            }
            if (!data.renameTo(target)) {
                errors.add("恢复失败：" + new File(origin).getName());
                continue;
            }
            deleteRecursive(slot);
            produced.add(target.getAbsolutePath());
            ok++;
        }
        return new Result(ok, 0, errors, produced);
    }

    /** 彻底删除（回收站清空或用户二次确认后的兜底） */
    public static Result deletePermanent(List<String> paths, Cancel cancel) {
        List<String> errors = new ArrayList<String>();
        int ok = 0;
        for (String p : paths) {
            if (cancel != null && cancel.cancelled()) {
                errors.add("操作已中断");
                break;
            }
            File f = new File(p);
            if (!f.exists()) {
                continue; // 已不存在视为成功，避免重复删除报错
            }
            if (deleteRecursive(f)) {
                ok++;
            } else {
                errors.add("删除失败（可能被占用或没有权限）：" + f.getName());
            }
        }
        return new Result(ok, 0, errors, new ArrayList<String>());
    }

    public static boolean purgeTrash(String trashDir) {
        File root = new File(trashDir);
        if (!root.exists()) {
            return true;
        }
        File[] slots = root.listFiles();
        if (slots == null) {
            return false;
        }
        boolean all = true;
        for (File slot : slots) {
            if (!deleteRecursive(slot)) {
                all = false;
            }
        }
        return all;
    }

    private static boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    if (!deleteRecursive(k)) {
                        return false;
                    }
                }
            }
        }
        return f.delete();
    }

    private static String safeId(String id) {
        if (id == null) {
            return "";
        }
        return id.replace("/", "").replace("\\", "").replace("..", "");
    }

    // ---------------------------------------------------------------- 空间 / 工具

    /**
     * 返回可用字节；取不到时返回 -1（调用方不应据此判定失败）。
     * 本类是纯 Java、不依赖 android.*，因此只能用 File#getUsableSpace；
     * StorageManager#getAllocatableBytes 属于 UI 层可选优化，不影响正确性。
     */
    public static long freeSpace(String path) {
        try {
            return new File(path).getUsableSpace();
        } catch (SecurityException e) {
            return -1L;
        }
    }

    public static long treeSize(File f) {
        if (f == null || !f.exists()) {
            return 0L;
        }
        if (!f.isDirectory()) {
            return f.length();
        }
        long n = 0;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                n += treeSize(k);
            }
        }
        return n;
    }

    /** 人类可读大小（纯 Java，界面可直接用，不依赖 Android） */
    public static String size(long bytes) {
        if (bytes < 0) {
            return "-";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double v = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int i = -1;
        do {
            v /= 1024;
            i++;
        } while (v >= 1024 && i < units.length - 1);
        return (v >= 100 ? String.format(Locale.US, "%.0f", v)
                : String.format(Locale.US, "%.1f", v)) + " " + units[i];
    }

    /** 类型名（目录 / 扩展名大写 / 通用"文件"） */
    public static String typeName(Entry e) {
        if (e == null) {
            return "";
        }
        if (e.dir) {
            return "文件夹";
        }
        return e.ext.isEmpty() ? "文件" : e.ext.toUpperCase(Locale.US) + " 文件";
    }

    private static boolean writeText(File f, String text) {
        OutputStream out = null;
        try {
            out = new FileOutputStream(f, false);
            out.write(text.getBytes("UTF-8"));
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(out);
        }
    }

    private static String readText(File f) {
        InputStream in = null;
        try {
            if (!f.exists()) {
                return null;
            }
            in = new FileInputStream(f);
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                all.write(buf, 0, n);
            }
            return new String(all.toByteArray(), "UTF-8").trim();
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 把 OpException 转成面向用户的中文提示 */
    public static String message(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        if (t instanceof OpException) {
            String m = t.getMessage();
            return m == null || m.isEmpty() ? "操作失败" : m;
        }
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    /** 供界面判断是否为权限类错误，用于弹出授权引导 */
    public static boolean isPermission(Throwable t) {
        return t instanceof OpException
                && REASON_PERMISSION.equals(((OpException) t).reason);
    }
}
