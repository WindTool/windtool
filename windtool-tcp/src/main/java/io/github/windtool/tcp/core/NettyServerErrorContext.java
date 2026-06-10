package io.github.windtool.tcp.core;

import io.github.windtool.tcp.handler.NettyServerHandler;

import java.net.SocketAddress;

/**
 * 服务端异常回包上下文。
 * <p>
 * 该对象把当前连接上已经拿到的有效信息统一交给错误回包链路：
 * 既包括原始报文体 / 帧配置，也包括已成功识别的 handler、requestClass、request 对象。
 * 这样异常即使发生在不同阶段，默认错误回包仍有机会恢复交易码、区位等公共字段。
 *
 * @param remoteAddress        远端地址
 * @param frameBody            已读取的原始报文体；尚未读取成功时可能为 null
 * @param frameMessageConfig   读取 TCP 帧时使用的报文配置
 * @param handler             已匹配到的业务处理器；尚未匹配时可能为 null
 * @param requestClass        已解析出的请求类型；尚未解析时可能为 null
 * @param requestMessageConfig 已解析出的请求报文配置；尚未解析时可能为 null
 * @param request             已解码出的请求对象；尚未解码时可能为 null
 * @param cause               原始异常
 */
public record NettyServerErrorContext(
    SocketAddress remoteAddress,
    byte[] frameBody,
    MessageConfig frameMessageConfig,
    NettyServerHandler<?> handler,
    Class<?> requestClass,
    MessageConfig requestMessageConfig,
    Object request,
    Throwable cause
) {
}
