package io.github.windtool.tcp.utils;

import cn.hutool.crypto.SecureUtil;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Locale;

/**
 * 报文 MD5 签名工具。
 * <p>
 * 编码和解码必须统一走这个工具，避免两端各自实现后出现“签名范围一致，但摘要入口不一致”的问题。
 *
 * @author AprilWind
 */
public final class MessageMd5Utils {

    /**
     * 工具类禁止实例化。
     */
    private MessageMd5Utils() {
    }

    /**
     * 按报文字节生成 MD5 摘要。
     * <p>
     * 传入字节应当是协议最终参与签名的原文序列，包含真实落链路的补齐字符和字段分隔符，
     * 但不包含 TCP 报头长度字段本身；例如 {@code 151|00|1001|...} 只对
     * {@code 00|1001|...} 这一段做 MD5。
     *
     * @param sourceBytes 参与签名的原文字节
     * @return 大写十六进制 MD5 摘要
     */
    public static String digest(byte[] sourceBytes) {
        return SecureUtil.md5(Arrays.toString(sourceBytes)).toUpperCase(Locale.ROOT);
    }

    /**
     * 按报文字节生成 MD5 摘要字节。
     *
     * @param sourceBytes 参与签名的原文字节
     * @param charset     摘要字符串转字节时使用的字符集
     * @return MD5 摘要字节
     */
    public static byte[] digestBytes(byte[] sourceBytes, Charset charset) {
        return digest(sourceBytes).getBytes(charset);
    }

    /**
     * 判断报文中的 MD5 字段与源字节签名是否一致。
     *
     * @param sourceBytes  参与签名的原文字节
     * @param actualDigest 报文中携带的摘要
     * @return true 表示摘要一致
     */
    public static boolean matches(byte[] sourceBytes, String actualDigest) {
        // 忽略大小写，兼容对端返回大写或小写十六进制摘要。
        return digest(sourceBytes).equalsIgnoreCase(actualDigest);
    }
}
