package io.github.windtool.tcp.utils;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;

/**
 * TCP 字节流读写辅助工具。
 *
 * @author AprilWind
 */
public final class NettyIoUtils {

    /**
     * 工具类禁止实例化。
     */
    private NettyIoUtils() {
    }

    /**
     * 精确读取指定长度字节。
     *
     * @param in     输入流
     * @param length 期望读取长度
     * @return 读取到的字节
     * @throws IOException 连接关闭或读取失败
     */
    public static byte[] readExact(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count;
            try {
                count = in.read(data, offset, length - offset);
            } catch (SocketTimeoutException e) {
                SocketTimeoutException timeout = new SocketTimeoutException(
                    "期望读取 " + length + " 字节，已收到 " + offset + " 字节，仍缺少 " + (length - offset) + " 字节");
                timeout.initCause(e);
                throw timeout;
            }
            if (count == -1) {
                throw new EOFException("期望读取 " + length + " 字节，仅收到 " + offset);
            }
            offset += count;
        }
        return data;
    }

    /**
     * 合并两段字节。
     *
     * @param source 原始字节
     * @param extra  待追加字节
     * @return 合并后的字节
     */
    public static byte[] appendBytes(byte[] source, byte[] extra) {
        byte[] merged = new byte[source.length + extra.length];
        System.arraycopy(source, 0, merged, 0, source.length);
        System.arraycopy(extra, 0, merged, source.length, extra.length);
        return merged;
    }

    /**
     * 字节数组转十六进制字符串。
     *
     * @param bytes 字节数组
     * @return 空格分隔的大写十六进制字符串
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

}
