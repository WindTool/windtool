package io.github.windtool.tcp.handler;

import io.github.windtool.tcp.core.MessageConfig;
import io.github.windtool.tcp.core.NettyServerErrorContext;

/**
 * 默认服务端异常回包实现。
 * <p>
 * 默认行为：
 * 1. 先交给当前 {@link NettyServerHandler} 生成错误响应对象或原始字节；
 * 2. 若返回普通对象，则按处理器给出的报文配置编码；
 * 3. 若处理器不提供错误响应，则本次异常不回包。
 *
 * @author AprilWind
 */
public class DefaultNettyServerErrorHandler implements NettyServerErrorHandler {

    private final MessageEncoder encoder;

    /**
     * 创建默认异常回包处理器。
     *
     * @param encoder 报文编码器
     */
    public DefaultNettyServerErrorHandler(MessageEncoder encoder) {
        this.encoder = encoder;
    }

    /**
     * 根据异常上下文构造完整错误回包字节。
     *
     * @param context 本次异常上下文
     * @return 完整 TCP 错误回包；返回 null 表示不回包
     * @throws Exception 构造或编码失败时抛出
     */
    @Override
    public byte[] buildErrorResponse(NettyServerErrorContext context) throws Exception {
        NettyServerHandler<?> handler = context.handler();
        // 还没匹配到业务处理器时，框架层无法推断具体错误报文格式，直接跳过回包。
        if (handler == null) {
            return null;
        }

        Object response = handler.errorResponse(context);
        // 业务处理器明确返回 null，表示本次异常只记录日志，不向连接写任何错误报文。
        if (response == null) {
            return null;
        }
        // 返回 byte[] 说明业务侧已经构造好了完整 TCP 报文，框架不再二次编码。
        if (response instanceof byte[] bytes) {
            return bytes;
        }

        MessageConfig responseMessageConfig = handler.errorResponseMessage(response, context);
        // 没有可靠的报文配置时，宁可不回包，也不要发送格式不确定的错误报文。
        if (responseMessageConfig == null) {
            return null;
        }
        return encoder.encode(response, responseMessageConfig);
    }
}
