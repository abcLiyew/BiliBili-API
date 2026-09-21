package com.esdllm.bilibiliApi.model.data.pojo.login;

import lombok.Data;

/**
 * 「取 RSA 公钥与盐」的响应体（{@code x/passport-login/web/key} 的 {@code data}）。
 *
 * <p>密码登录专用的两个值，缺一不可：
 * <pre>{@code
 * password = base64( RSA_PKCS1( hash + 明文密码 ) )
 * }</pre>
 * 注意是<b>盐拼在明文前面</b>、与明文<b>一起</b>加密，输出 <b>base64</b>（不是 hex）。
 *
 * <p>⚠️ {@link #hash} 的<b>有效期只有 20 秒</b>（官方文档明写）。它不是一个可以缓存复用的公钥 ——
 * 一旦中间隔了用户交互（比如等用户过极验、等短信），拿到的 hash 就过期了，
 * 提交时会返回 {@code 2400 登录秘钥错误}。正确顺序是：
 * <b>先过极验 → 再取 key → 立刻提交</b>。
 *
 * @author 饿死的流浪猫
 */
@Data
public class RsaKeyInfo {

    /**
     * 密码盐，恒为 16 字符，有效期 20 秒。
     *
     * <p>必须拼在明文密码<b>前面</b>后一并加密；本库不缓存它（缓存等于必然过期）。
     */
    private String hash;

    /**
     * RSA 公钥，<b>PEM 格式</b>（{@code -----BEGIN PUBLIC KEY-----} 开头，
     * 中间是 {@code \n} 分隔的 base64，密钥长度 1024 位）。
     *
     * <p>本库用 JDK 自带 JCE 解析（{@code X509EncodedKeySpec} + {@code KeyFactory("RSA")}），
     * <b>零新增依赖</b>；填充方式 {@code RSA/ECB/PKCS1Padding}，
     * 与文档示例里 Python 的 {@code rsa.encrypt}（PKCS#1 v1.5）一致。
     */
    private String key;
}
