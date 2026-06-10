package io.github.windtool.tcp.model;

/**
 * 报文体编码结果。
 *
 * @param body                     实际编码后的报文体字节
 * @param declaredBodyLength       写入报文头的报文体声明长度
 * @param trailingDelimiterCounted 报文体最后一个分隔符是否已计入声明长度
 * @author AprilWind
 */
public record EncodedBody(byte[] body, int declaredBodyLength, boolean trailingDelimiterCounted) {
}
