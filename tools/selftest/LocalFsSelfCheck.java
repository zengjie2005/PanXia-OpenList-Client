import com.zhj.panxia.LocalFs;
import com.zhj.panxia.LocalFs.Cancel;
import com.zhj.panxia.LocalFs.Entry;
import com.zhj.panxia.LocalFs.OpException;
import com.zhj.panxia.LocalFs.Result;
import com.zhj.panxia.LocalFs.TrashItem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 本地文件层核心流程自检。纯 JDK 运行，不依赖 Android，也不需要 JUnit。
 *
 * 构建与运行（工程根）：
 *   javac -encoding UTF-8 -d <out> app/src/main/java/com/zhj/panxia/LocalFs.java tools/selftest/LocalFsSelfCheck.java
 *   java -cp <out> LocalFsSelfCheck
 */
public class LocalFsSelfCheck {

    private static int pass = 0;
    private static int fail = 0;
    private static File root;

    public static void main(String[] args) throws Exception {
        root = new File(System.getProperty("java.io.tmpdir"),
                "panxia_selftest_" + System.currentTimeMillis());
        if (!root.mkdirs()) {
            throw new IOException("cannot create temp dir " + root);
        }
        try {
            run();
        } finally {
            wipe(root);
        }
        System.out.println("---------------------------------------------");
        System.out.println("RESULT: " + pass + " passed, " + fail + " failed");
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        File dir = new File(root, "work");
        assertTrue("setup dir", dir.mkdirs());

        // ---- 1 空目录
        List<Entry> empty = LocalFs.list(dir.getAbsolutePath());
        check("empty dir -> 0 items", empty.size() == 0);

        // ---- 2 路径不存在
        try {
            LocalFs.list(new File(root, "nope").getAbsolutePath());
            check("missing dir -> throws", false);
        } catch (OpException e) {
            check("missing dir -> NOT_FOUND",
                    LocalFs.REASON_NOT_FOUND.equals(e.reason));
        }

        // ---- 3 名称清洗与路径穿越
        check("safeName keeps Chinese", "中文 文件.txt".equals(LocalFs.safeName("  中文 文件.txt  ")));
        check("safeName strips separators", "ab.txt".equals(LocalFs.safeName("a/b\\.txt")));
        check("safeName strips trailing dots", "ab".equals(LocalFs.safeName("ab... ")));
        expectInvalid("dot name", ".", LocalFs.REASON_INVALID);
        expectInvalid("dotdot name", "..", LocalFs.REASON_INVALID);
        expectInvalid("blank name", "   ", LocalFs.REASON_INVALID);
        try {
            LocalFs.resolve(root.getAbsolutePath(), "../outside.txt");
            check("resolve blocks traversal", false);
        } catch (OpException e) {
            check("resolve blocks traversal", LocalFs.REASON_INVALID.equals(e.reason));
        }

        // ---- 4 新建文件 / 文件夹
        Entry f1 = LocalFs.createFile(dir.getAbsolutePath(), "a.txt", LocalFs.CONFLICT_RENAME);
        check("createFile returns entry", f1 != null && !f1.dir && "txt".equals(f1.ext));
        check("createFile exists on disk", new File(f1.path).isFile());
        Entry d1 = LocalFs.createDir(dir.getAbsolutePath(), "sub", LocalFs.CONFLICT_RENAME);
        check("createDir returns dir entry", d1 != null && d1.dir);
        check("createDir exists on disk", new File(d1.path).isDirectory());

        // ---- 5 列目录字段
        List<Entry> items = LocalFs.list(dir.getAbsolutePath());
        check("list returns 2 items", items.size() == 2);
        boolean metaOk = false;
        for (Entry e : items) {
            if ("a.txt".equals(e.name)) {
                metaOk = e.size == 0 && e.modified > 0 && "txt".equals(e.ext)
                        && e.path.equals(new File(f1.path).getAbsolutePath());
            }
        }
        check("entry has size/time/type/path", metaOk);

        // ---- 6 重名处理
        Entry skipped = LocalFs.createFile(dir.getAbsolutePath(), "a.txt", LocalFs.CONFLICT_SKIP);
        check("conflict SKIP -> null", skipped == null);
        Entry renamed = LocalFs.createFile(dir.getAbsolutePath(), "a.txt", LocalFs.CONFLICT_RENAME);
        check("conflict RENAME -> a (1).txt", renamed != null && "a (1).txt".equals(renamed.name));
        check("original untouched", new File(f1.path).isFile());

        // ---- 7 排序（目录优先 + 各字段）
        File big = new File(dir, "big.bin");
        writeBytes(big, 8 * 1024 * 1024, (byte) 7);
        List<Entry> toSort = LocalFs.list(dir.getAbsolutePath());
        LocalFs.sort(toSort, LocalFs.SORT_NAME, false);
        check("sort name -> folder first", toSort.get(0).dir);
        check("sort name ascending", "a (1).txt".equals(toSort.get(1).name));
        LocalFs.sort(toSort, LocalFs.SORT_SIZE, true);
        check("sort size desc -> folder still first", toSort.get(0).dir);
        check("sort size desc -> big.bin next", "big.bin".equals(toSort.get(1).name));
        LocalFs.sort(toSort, LocalFs.SORT_NAME, true);
        check("sort name desc -> folder still first", toSort.get(0).dir);

        // ---- 8 隐藏过滤
        LocalFs.createFile(dir.getAbsolutePath(), ".hidden", LocalFs.CONFLICT_RENAME);
        List<Entry> all = LocalFs.list(dir.getAbsolutePath());
        List<Entry> visible = LocalFs.filterHidden(all, false);
        check("filterHidden removes dot files", visible.size() == all.size() - 1);
        check("filterHidden(show) keeps all",
                LocalFs.filterHidden(all, true).size() == all.size());

        // ---- 9 递归搜索（大小写、中文、结果）
        File deep = new File(dir, "sub/deeper");
        assertTrue("mkdir deep", deep.mkdirs());
        LocalFs.createFile(deep.getAbsolutePath(), "DeepFile.TXT", LocalFs.CONFLICT_RENAME);
        LocalFs.createFile(deep.getAbsolutePath(), "报告.pdf", LocalFs.CONFLICT_RENAME);
        List<Entry> found = LocalFs.search(dir.getAbsolutePath(), "deep", null);
        check("search recursive + case-insensitive", containsName(found, "DeepFile.TXT"));
        List<Entry> foundCn = LocalFs.search(dir.getAbsolutePath(), "报告", null);
        check("search Chinese name", containsName(foundCn, "报告.pdf"));

        // ---- 10 大文件复制 + 内容一致 + 无半成品
        File copyDir = new File(root, "copy");
        assertTrue("mkdir copy", copyDir.mkdirs());
        Result rCopy = LocalFs.copy(Collections.singletonList(big.getAbsolutePath()),
                copyDir.getAbsolutePath(), LocalFs.CONFLICT_RENAME, null, null);
        check("copy 8MB -> ok=1", rCopy.ok == 1 && rCopy.allOk());
        File copied = new File(copyDir, "big.bin");
        check("copy content identical", copied.isFile()
                && fingerprint(copied) == fingerprint(big));
        check("no .part leftover", noPartFiles(root));

        // ---- 11 复制中断
        final int[] ticks = {0};
        Cancel midway = new Cancel() {
            @Override
            public boolean cancelled() {
                return ++ticks[0] > 2;
            }
        };
        Result rCancel = LocalFs.copy(Collections.singletonList(big.getAbsolutePath()),
                copyDir.getAbsolutePath(), LocalFs.CONFLICT_RENAME, midway, null);
        check("cancel -> not all ok", !rCancel.allOk());
        check("cancel -> no partial file", noPartFiles(root));
        check("cancel -> original intact", fingerprint(big) > 0);

        // ---- 12 冲突策略
        Result rSkip = LocalFs.copy(Collections.singletonList(big.getAbsolutePath()),
                copyDir.getAbsolutePath(), LocalFs.CONFLICT_SKIP, null, null);
        check("copy conflict SKIP", rSkip.ok == 0 && rSkip.skipped == 1);
        Result rRename = LocalFs.copy(Collections.singletonList(big.getAbsolutePath()),
                copyDir.getAbsolutePath(), LocalFs.CONFLICT_RENAME, null, null);
        check("copy conflict RENAME creates new file",
                rRename.ok == 1 && new File(copyDir, "big (1).bin").isFile());

        // ---- 13 目录递归复制
        File treeCopy = new File(root, "treecopy");
        assertTrue("mkdir treecopy", treeCopy.mkdirs());
        Result rTree = LocalFs.copy(Collections.singletonList(new File(dir, "sub").getAbsolutePath()),
                treeCopy.getAbsolutePath(), LocalFs.CONFLICT_RENAME, null, null);
        check("copy directory tree", rTree.ok == 1 && rTree.allOk()
                && new File(treeCopy, "sub/deeper/DeepFile.TXT").isFile());

        // ---- 14 目标不存在 / 混合批量
        Result rBad = LocalFs.copy(Arrays.asList(big.getAbsolutePath(),
                        new File(root, "ghost.bin").getAbsolutePath()),
                new File(root, "no-such-dir").getAbsolutePath(),
                LocalFs.CONFLICT_RENAME, null, null);
        check("missing dest -> error, no crash", rBad.ok == 0 && !rBad.allOk());

        // ---- 15 重命名
        Entry rn = LocalFs.rename(f1.path, "renamed.txt", LocalFs.CONFLICT_RENAME);
        check("rename succeeds", rn != null && "renamed.txt".equals(rn.name)
                && new File(rn.path).isFile() && !new File(f1.path).exists());
        Entry rn2 = LocalFs.rename(rn.path, "big.bin", LocalFs.CONFLICT_RENAME);
        check("rename onto existing auto-renames", rn2 != null
                && !"big.bin".equals(rn2.name) && new File(rn2.path).isFile());

        // ---- 16 移动
        File moveDir = new File(root, "moved");
        assertTrue("mkdir moved", moveDir.mkdirs());
        Result rMove = LocalFs.move(Collections.singletonList(big.getAbsolutePath()),
                moveDir.getAbsolutePath(), LocalFs.CONFLICT_RENAME, null, null);
        check("move ok", rMove.ok == 1 && rMove.allOk());
        check("move source gone", !big.exists());
        check("move target exists", new File(moveDir, "big.bin").isFile());

        // ---- 17 回收站：删除 -> 列出 -> 恢复
        File trash = new File(root, ".PanXiaTrash");
        File victim = new File(dir, "报告.pdf");
        File chinese = new File(dir, "中文名称.txt");
        writeBytes(chinese, 32, (byte) 3);
        Result rTrash = LocalFs.trash(
                Arrays.asList(rn2.path, chinese.getAbsolutePath()), trash.getAbsolutePath());
        check("trash -> ok=2", rTrash.ok == 2 && rTrash.allOk());
        check("trash -> source removed", !new File(rn2.path).exists() && !chinese.exists());
        List<TrashItem> inTrash = LocalFs.listTrash(trash.getAbsolutePath());
        check("trash list has 2 records", inTrash.size() == 2);
        boolean cnOriginOk = false;
        for (TrashItem t : inTrash) {
            if (t.name.contains("中文")) {
                cnOriginOk = t.origin.equals(chinese.getAbsolutePath());
            }
        }
        check("Chinese origin path survives round-trip", cnOriginOk);

        // 目标已存在时应自动改名，绝不覆盖
        writeBytes(chinese, 16, (byte) 9);
        List<String> restoreIds = new ArrayList<String>();
        for (TrashItem t : inTrash) {
            restoreIds.add(t.id);
        }
        Result rRestore = LocalFs.restore(trash.getAbsolutePath(), restoreIds);
        check("restore -> ok=2", rRestore.ok == 2 && rRestore.allOk());
        check("restore preserved existing file (no overwrite)",
                fingerprint(chinese) == fingerprintOf((byte) 9, 16));
        check("restored Chinese file got a distinct name",
                new File(dir, "中文名称 (1).txt").isFile());
        check("trash emptied after restore", LocalFs.listTrash(trash.getAbsolutePath()).isEmpty());

        // ---- 18 回收站清空 / 永久删除
        LocalFs.trash(Collections.singletonList(new File(moveDir, "big.bin").getAbsolutePath()),
                trash.getAbsolutePath());
        check("purgeTrash", LocalFs.purgeTrash(trash.getAbsolutePath())
                && LocalFs.listTrash(trash.getAbsolutePath()).isEmpty());
        Result rDel = LocalFs.deletePermanent(
                Collections.singletonList(new File(root, "already-gone").getAbsolutePath()), null);
        check("delete missing path is idempotent", rDel.allOk());

        // ---- 19 空间 / 格式化 / 类型
        check("freeSpace > 0", LocalFs.freeSpace(root.getAbsolutePath()) > 0);
        check("size 0 B", "0 B".equals(LocalFs.size(0)));
        check("size 1.0 KB", "1.0 KB".equals(LocalFs.size(1024)));
        check("size 1.5 KB", "1.5 KB".equals(LocalFs.size(1536)));
        check("typeName folder", "文件夹".equals(LocalFs.typeName(d1)));
        check("typeName TXT", "TXT 文件".equals(LocalFs.typeName(f1)));

        // ---- 20 深度保护：不因深目录抛未检查异常
        File deepRoot = new File(root, "deeproot");
        assertTrue("mkdir deeproot", deepRoot.mkdirs());
        File cur = deepRoot;
        for (int i = 0; i < 55; i++) {
            cur = new File(cur, "d" + i);
            if (!cur.mkdirs()) {
                break;
            }
        }
        boolean depthSafe = false;
        try {
            LocalFs.search(deepRoot.getAbsolutePath(), "d", null);
            depthSafe = true;
        } catch (OpException e) {
            depthSafe = LocalFs.REASON_TOO_DEEP.equals(e.reason);
        } catch (Throwable t) {
            depthSafe = false;
        }
        check("deep tree handled without crash", depthSafe);

        // ---- 21 权限不足：不得抛未检查异常
        File ro = new File(root, "readonly");
        assertTrue("mkdir readonly", ro.mkdirs());
        //noinspection ResultOfMethodCallIgnored
        ro.setWritable(false, false);
        boolean permSafe;
        try {
            LocalFs.createFile(ro.getAbsolutePath(), "x.txt", LocalFs.CONFLICT_RENAME);
            permSafe = true; // 某些文件系统 setWritable 不生效，成功也算安全
        } catch (OpException e) {
            permSafe = e.reason != null;
        } catch (Throwable t) {
            permSafe = false;
        }
        check("read-only dir does not crash", permSafe);
        //noinspection ResultOfMethodCallIgnored
        ro.setWritable(true, false);
    }

    // ------------------------------------------------------------ helpers

    private static void expectInvalid(String name, String raw, String expectedReason) {
        try {
            LocalFs.safeName(raw);
            check(name + " -> rejected", false);
        } catch (OpException e) {
            check(name + " -> rejected", expectedReason.equals(e.reason));
        }
    }

    private static boolean containsName(List<Entry> list, String name) {
        for (Entry e : list) {
            if (name.equals(e.name)) {
                return true;
            }
        }
        return false;
    }

    private static void writeBytes(File f, int count, byte value) throws IOException {
        FileOutputStream out = new FileOutputStream(f, false);
        try {
            byte[] buf = new byte[64 * 1024];
            Arrays.fill(buf, value);
            int left = count;
            while (left > 0) {
                int n = Math.min(left, buf.length);
                out.write(buf, 0, n);
                left -= n;
            }
            out.flush();
        } finally {
            out.close();
        }
    }

    /** 简单但足够的指纹：长度 + 逐字节加权和 */
    private static long fingerprint(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[64 * 1024];
            long sum = 0;
            long len = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                for (int i = 0; i < n; i++) {
                    sum = sum * 31 + (buf[i] & 0xFF);
                }
                len += n;
            }
            return len * 1000003L + sum;
        } finally {
            in.close();
        }
    }

    private static long fingerprintOf(byte value, int count) {
        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum = sum * 31 + (value & 0xFF);
        }
        return (long) count * 1000003L + sum;
    }

    private static boolean noPartFiles(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return true;
        }
        for (File f : kids) {
            if (f.isDirectory()) {
                if (!noPartFiles(f)) {
                    return false;
                }
            } else if (f.getName().endsWith(".panxia-part")) {
                return false;
            }
        }
        return true;
    }

    private static void wipe(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    wipe(k);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void assertTrue(String name, boolean ok) {
        check(name, ok);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name);
        }
    }
}
