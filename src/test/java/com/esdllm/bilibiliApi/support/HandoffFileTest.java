package com.esdllm.bilibiliApi.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HandoffFile} 的行为锁定。
 *
 * <p>这里的用例几乎每一条都对应一次真实翻车：BOM 与"seed 覆盖"两个坑都是
 * <b>静默失效</b>类型的（不报错、只是读不到），不锁住就会在下次改动时悄悄回来。
 */
class HandoffFileTest {

    private static final String BOM = "\uFEFF";

    @Nested
    @DisplayName("解析")
    class Parsing {

        @Test
        @DisplayName("key=value 正常读出，键大小写不敏感")
        void readsNormally(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("a.txt");
            Files.writeString(file, "Account=13800000000\nPASSWORD=secret\n");

            Map<String, String> values = HandoffFile.read(file);

            assertEquals("13800000000", values.get("account"));
            assertEquals("secret", values.get("password"));
        }

        @Test
        @DisplayName("🔴 首行带 UTF-8 BOM 时第一个键仍要能读出来（记事本保存会带 BOM）")
        void leadingBomKeepsFirstKey(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("b.txt");
            Files.writeString(file, BOM + "account=13800000000\npassword=secret\n");

            Map<String, String> values = HandoffFile.read(file);

            // 不剥 BOM 的话键会变成 "\ufeffaccount"，取值恒为 null —— 表现为"我明明填了却说没填"
            assertEquals("13800000000", values.get("account"));
            assertEquals("secret", HandoffFile.value(file, "password"));
        }

        @Test
        @DisplayName("BOM 出现在中间行 / 叠加多个也要剥")
        void bomOnMiddleLinesAndRepeated(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("c.txt");
            Files.writeString(file, "# 注释\n" + BOM + BOM + "account=13800000000\n");

            assertEquals("13800000000", HandoffFile.value(file, "account"));
        }

        @Test
        @DisplayName("BOM 也在 value 之前被剥掉（键值同行都带的情况）")
        void bomBeforeValue(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("d.txt");
            Files.writeString(file, "validate=" + BOM + "abc123\n");

            assertEquals("abc123", HandoffFile.value(file, "validate"));
        }

        @Test
        @DisplayName("注释行与空行忽略；无 = 的行忽略；= 两边空格去掉")
        void commentsBlanksAndPadding(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("e.txt");
            Files.writeString(file,
                    """
                            # 这是注释
                            
                              \s
                            一堆没有等号的文字
                              account = 13800000000 \s
                            """);

            Map<String, String> values = HandoffFile.read(file);

            assertEquals(1, values.size());
            assertEquals("13800000000", values.get("account"));
        }

        @Test
        @DisplayName("CRLF 换行同样能解析（Windows 编辑器默认）")
        void CRLF(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("f.txt");
            Files.writeString(file, "account=13800000000\r\npassword=secret\r\n");

            assertEquals("secret", HandoffFile.value(file, "password"));
        }

        @Test
        @DisplayName("空值 / 只有空白视为没有；文件不存在也视为没有")
        void blankValuesAndMissingFile(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("g.txt");
            Files.writeString(file, "account=\npassword=   \n");

            assertNull(HandoffFile.value(file, "account"));
            assertNull(HandoffFile.value(file, "password"));
            assertNull(HandoffFile.value(dir.resolve("不存在.txt"), "account"));
            assertTrue(HandoffFile.lines(dir.resolve("不存在.txt")).isEmpty());
        }
    }

    @Nested
    @DisplayName("写入")
    class Writing {

        @Test
        @DisplayName("文件不存在时写出模板，并报告\"确实写了\"")
        void writesWhenAbsent(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("sub").resolve("new.txt");

            assertTrue(HandoffFile.seedIfAbsent(file, "account=\n"));
            assertEquals("account=\n", Files.readString(file));
        }

        @Test
        @DisplayName("文件是空白时也写出模板")
        void writesWhenBlank(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("blank.txt");
            Files.writeString(file, "   \n\n");

            assertTrue(HandoffFile.seedIfAbsent(file, "account=\n"));
        }

        @Test
        @DisplayName("🔴 已有用户填的值时绝不覆盖（覆盖会抹掉用户刚填的内容）")
        void neverOverwritesExistingValues(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("filled.txt");
            Files.writeString(file, "account=13800000000\n");

            // 用户只填了 account 就来跑密码登录 —— 此时若用无条件写入，account 会被清空
            assertFalse(HandoffFile.seedIfAbsent(file, "account=\npassword=\n"));
            assertEquals("account=13800000000\n", Files.readString(file));
        }

        @Test
        @DisplayName("只有注释行也算\"有内容\"，不再重复写模板")
        void commentsCountAsContent(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("comment-only.txt");
            Files.writeString(file, "# 填好直接重跑\naccount=\n");

            assertFalse(HandoffFile.seedIfAbsent(file, "# 另一个模板\naccount=\n"));
            assertTrue(Files.readString(file).contains("填好直接重跑"));
        }

        @Test
        @DisplayName("overwrite 是无条件覆盖，并自动建父目录（极验/短信这类每轮要换新的场景）")
        void overwriteForcesAndCreatesDirs(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("deep").resolve("geetest-result.txt");
            HandoffFile.overwrite(file, "validate=old\n");
            HandoffFile.overwrite(file, "validate=new\n");

            assertEquals("validate=new\n", Files.readString(file, StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("种子写入的内容用 UTF-8，不含 BOM（避免自己给自己挖 BOM 的坑）")
        void seededFileHasNoBom(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("h.txt");
            HandoffFile.seedIfAbsent(file, "account=\n");

            byte[] raw = Files.readAllBytes(file);
            assertFalse(raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF,
                    "种子文件不该带 BOM，否则首个键会读不出来");
        }
    }
}
