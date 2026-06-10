package io.github.windtool.tcp.handler;

import io.github.windtool.tcp.core.NettyServerErrorContext;

/**
 * 服务端异常回包构造器。
 * <p>
 * 返回 {@code null} 表示本次异常不回写错误报文，只记录日志并关闭连接。
 *
 * @author AprilWind
 */
public interface NettyServerErrorHandler {

    /**
     * 根据异常上下文构造错误回包字节。
     *
     * @param context 本次异常的上下文
     * @return 完整 TCP 回包字节；返回 null 表示不回包
     * @throws Exception 构造失败时抛出
     */
    byte[] buildErrorResponse(NettyServerErrorContext context) throws Exception;
}
