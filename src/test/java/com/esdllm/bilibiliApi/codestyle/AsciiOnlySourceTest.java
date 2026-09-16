package com.esdllm.bilibiliApi.codestyle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 源码里的<b>标识符</b>必须全部是 ASCII。
 *
 * <p>中文写注释、写 {@code @DisplayName}、写断言消息都没问题 —— 那些是字符串与注释。
 * 但拿中文当类名 / 方法名 / 变量名，是把编译能否成功押在“运行环境的默认字符编码”上：
 *
 * <ul>
 *   <li>Maven 有 {@code project.build.sourceEncoding=UTF-8} 兜底，所以 {@code mvn test} 是安全的；</li>
 *   <li>但直接 {@code javac}、Gradle、以及某些 IDE 配置<b>不读这个属性</b>，javac 默认按
 *       平台编码读源文件 —— Windows 中文版是 GBK，UTF-8 的中文字节会被解坏，
 *       报「非法字符」或满屏「找不到符号」；</li>
 *   <li>{@code mvn test -Dtest=类#方法名} 在 GBK 控制台传参时，中文参数还会在
 *       {@code sun.jnu.encoding} 环节被改写，表现为“测试明明在，却说找不到”。</li>
 * </ul>
 *
 * <p>中文语义请放进 {@code @DisplayName}（字符串），方法名用英文 —— 两边都不丢。
 *
 * <p>本测试自己当然也得守这条规矩。定位不到源码目录时（例如从 jar 里跑）自动跳过。
 */
class AsciiOnlySourceTest {

    /** 与 test 源集一起被检查；main 同样受约束。 */
    private static final String[] SOURCE_ROOTS = {"src/main/java", "src/test/java"};

    @Test
    @DisplayName("源码标识符只用 ASCII（中文注释与字符串不受限）")
    void identifiersAreAsciiOnly() throws IOException {
        Path projectRoot = Path.of(System.getProperty("user.dir"));

        List<Path> roots = new ArrayList<>();
        for (String rel : SOURCE_ROOTS) {
            Path p = projectRoot.resolve(rel);
            if (Files.isDirectory(p)) {
                roots.add(p);
            }
        }
        assumeTrue(!roots.isEmpty(), "找不到源码目录，跳过（不在项目根目录运行？）");

        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (Path root : roots) {
            for (Path file : javaFilesUnder(root)) {
                scanned++;
                for (String hit : scanIdentifiers(file)) {
                    offenders.add(projectRoot.relativize(file) + "  " + hit);
                }
            }
        }

        assertTrue(scanned > 0, "一个 .java 都没扫到，源码路径判定有问题：" + roots);
        assertTrue(offenders.isEmpty(),
                "标识符里出现了非 ASCII 字符（换到默认编码非 UTF-8 的环境会编译失败）：\n  "
                        + String.join("\n  ", offenders));
    }

    private static List<Path> javaFilesUnder(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    /** 只找“标识符位置”的非 ASCII —— 注释、字符串、字符字面量、文本块全部跳过。 */
    private static List<String> scanIdentifiers(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        List<String> hits = new ArrayList<>();

        int i = 0;
        int n = text.length();
        int startLine = 1;

        while (i < n) {
            char quote = text.charAt(i);

            if (quote == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                while (i < n && text.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }

            if (quote == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) {
                    if (text.charAt(i) == '\n') {
                        startLine++;
                    }
                    i++;
                }
                i += 2;
                continue;
            }

            if (text.startsWith("\"\"\"", i)) {
                i += 3;
                while (i < n && !text.startsWith("\"\"\"", i)) {
                    if (text.charAt(i) == '\\') {
                        i += 2;
                        continue;
                    }
                    if (text.charAt(i) == '\n') {
                        startLine++;
                    }
                    i++;
                }
                i += 3;
                continue;
            }

            if (quote == '"' || quote == '\'') {
                i++;
                while (i < n && text.charAt(i) != quote) {
                    if (text.charAt(i) == '\\') {
                        i += 2;
                        continue;
                    }
                    if (text.charAt(i) == '\n') {
                        startLine++;
                    }
                    i++;
                }
                i++;
                continue;
            }

            if (quote == '\n') {
                startLine++;
                i++;
                continue;
            }

            if (quote > 127 || Character.isJavaIdentifierStart(quote)) {
                int start = i;
                while (i < n && (text.charAt(i) > 127 || Character.isJavaIdentifierPart(text.charAt(i)))) {
                    i++;
                }
                String word = text.substring(start, i);
                if (!isAscii(word)) {
                    hits.add("第 " + startLine + " 行: " + word);
                }
                continue;
            }

            i++;
        }

        return hits;
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }
}
