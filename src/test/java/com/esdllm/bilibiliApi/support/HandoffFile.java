package com.esdllm.bilibiliApi.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "人工交接文件"读写：{@code 人类填文件 → 程序轮询读取} 这一套机制的公共实现。
 *
 * <p><b>为什么会有这个类</b>：交互式登录链路上有三处需要人工介入（极验结果、短信验证码、登录凭据），
 * 而 IDEA 的运行控制台对 {@code System.in} 的支持时灵时不灵（取决于是否启用终端模拟），
 * 于是统一走"人改文件、程序轮询文件"——它在任何环境都成立，且每轮的产物天然留痕。
 * 三处各写一套解析迟早会漂移，所以收在这里，并由 {@code HandoffFileTest} 锁住行为。
 *
 * <p><b>两条硬教训（都踩过，故写进契约）</b>：
 * <ol>
 *   <li>🔴 {@link #seedIfAbsent} 与 {@link #overwrite} 必须分开。<b>凡是"文件里可能已有用户刚填的值"
 *       的场景，只能用 {@code seedIfAbsent}</b>：用 {@code overwrite} 会把用户填的值抹回模板
 *       —— 2026-09-16 真机翻过车，用户填了 account 漏了 password，跑密码登录时 account 被清空，
 *       表现为"我明明填了却还说没提供凭据"。</li>
 *   <li>🔴 读取时必须剥掉 BOM（{@code \uFEFF}）。Windows 记事本等编辑器保存 UTF-8 会带 BOM，
 *       首行 {@code account=xxx} 会变成 {@code \uFEFFaccount=xxx}；{@code String.trim()} 不去 BOM
 *       （{@code Character.isWhitespace('\uFEFF') == false}），于是键名成了 {@code \ufeffaccount}，
 *       查 {@code account} 永远查不到 —— 表现为"填了却说没提供"。<b>且不能只剥行首</b>：
 *       BOM 也可能落在 {@code key=} 与值之间（复制粘贴时常见），所以按整行剥。</li>
 * </ol>
 */
public final class HandoffFile {

    /** UTF-8 BOM（零宽不换行空格），只可能出现在文件最开头 */
    private static final String BOM = "\uFEFF";

    private HandoffFile() {
    }

    /**
     * 读交接文件的有效行：剥 BOM → 去首尾空白 → 丢掉空行与 {@code #} 注释行。
     *
     * @param file 交接文件（不存在视为"没有"）
     * @return 有效行，按出现顺序
     * @throws IOException 读文件失败
     */
    public static List<String> lines(Path file) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = stripBom(raw).trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * 把交接文件读成 {@code key=value}（键统一小写）。
     *
     * <p>只认 {@code key=value} 形式：{@code =} 必须在第一位之后，且键里不能有空格，
     * 否则那行被忽略（注释行、贴进来的 JSON 片段等都会自然落到这一类，不需要专门报错）。
     *
     * @param file 交接文件（不存在返回空表）
     * @return 参数表（小写键），保序
     * @throws IOException 读文件失败
     */
    public static Map<String, String> read(Path file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : lines(file)) { // lines() 已把 BOM 剥干净，这里不必再处理
            int eq = line.indexOf('=');
            if (eq > 0) {
                String key = line.substring(0, eq).trim().toLowerCase();
                String value = line.substring(eq + 1).trim();
                if (!key.isEmpty() && !key.contains(" ")) {
                    values.put(key, value);
                }
            }
        }
        return values;
    }

    /**
     * 取一个非空值。
     *
     * @param file 交接文件（不存在视为"没有"）
     * @param key  键（小写）
     * @return 值；不存在、为空、或文件不存在都返回 {@code null}
     * @throws IOException 读文件失败
     */
    public static String value(Path file, String key) throws IOException {
        String value = read(file).get(key);
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * <b>只在文件没有实质内容时</b>写入模板 —— 已有内容一律不动。
     *
     * <p>用于"想把模板摆到用户面前，但用户可能已经填过"的场景。判断标准是
     * "文件不存在，或内容是空白的"；<b>只有注释行也算"有内容"</b>，
     * 因为那正是模板本身，再写一遍毫无意义、还会顺带抹掉并发写入。
     *
     * @param file     交接文件
     * @param template 模板内容
     * @return 是否真的写了（便于调用方决定要不要提示"已生成模板"）
     * @throws IOException 读写失败
     */
    public static boolean seedIfAbsent(Path file, String template) throws IOException {
        if (Files.exists(file) && !Files.readString(file, StandardCharsets.UTF_8).isBlank()) {
            return false;
        }
        overwrite(file, template);
        return true;
    }

    /**
     * 无条件覆盖写入（自动建父目录）。
     *
     * <p>只用于"每轮都必须换新内容"的交接文件（极验结果、短信验证码）——
     * 旧值留着会被误当成本轮的结果（极验 {@code validate} 用过即废，复用只会得到 2406）。
     * <b>凭据文件不要用这个</b>，见 {@link #seedIfAbsent}。
     *
     * @param file    交接文件
     * @param content 新内容
     * @throws IOException 写失败
     */
    public static void overwrite(Path file, String content) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /**
     * 剥掉整行里的 BOM（{@code \uFEFF}）。
     *
     * <p><b>不做"只看行首"的优化</b>：BOM 的位置取决于编辑器与复制粘贴路径 ——
     * 文件签名在行首、但也可能夹在 {@code key=} 与值之间（从网页复制时尤其常见）。
     * 逐位置判断只会漏，而 BOM 本来就不是任何合法输入的一部分，全剥最安全。
     */
    private static String stripBom(String line) {
        return !line.contains(BOM) ? line : line.replace(BOM, "");
    }
}
