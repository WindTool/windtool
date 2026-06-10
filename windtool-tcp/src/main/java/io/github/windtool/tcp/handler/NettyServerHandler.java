package io.github.windtool.tcp.handler;

import io.github.windtool.tcp.annotation.FixedMessage;
import io.github.windtool.tcp.core.MessageConfig;
import io.github.windtool.tcp.core.NettyServerErrorContext;

/**
 * 短连接 TCP 服务端业务处理器。
 * <p>
 * 一个处理器描述一类服务端报文如何被识别、解码、处理和编码响应。
 * 简单场景下，一个处理器只对应一个请求类；复杂场景下，可以实现聚合分发处理器，
 * 先按统一帧格式读取报文，再根据交易码返回不同请求类。
 *
 * @param <T> 请求报文对象类型
 * @author AprilWind
 */
public interface NettyServerHandler<T> {

    /**
     * 请求报文对象类型，必须标注 {@link FixedMessage} 注解。
     * <p>
     * 普通处理器会直接使用该类型完成报文体解码；聚合分发处理器可返回 {@code Object.class}，
     * 并通过 {@link #requestClass(byte[], MessageConfig)} 再决定实际类型。
     *
     * @return 请求报文对象类型
     */
    Class<T> requestClass();

    /**
     * 读取 TCP 帧时使用的报文配置。
     * <p>
     * 默认使用 {@link #requestClass()} 上的 {@link FixedMessage} 注解；聚合分发处理器可覆盖该方法，
     * 在未确定具体交易前先按统一报头规则读取完整报文。
     *
     * @return TCP 帧报文配置
     */
    default MessageConfig frameMessage() {
        return MessageConfig.from(requestClass());
    }

    /**
     * 根据原始报文体确定实际请求对象类型。
     * <p>
     * 默认返回 {@link #requestClass()}；聚合分发处理器可通过交易码返回不同请求类。
     *
     * @param body        报文体字节
     * @param messageConfig TCP 帧报文配置
     * @return 实际请求对象类型
     */
    default Class<? extends T> requestClass(byte[] body, MessageConfig messageConfig) {
        return requestClass();
    }

    /**
     * 判断当前处理器是否处理该报文。
     * <p>
     * 多个处理器共存时可通过交易码等报文字段判断；默认处理全部报文。
     * 注意该方法收到的是未解码的报文体，适合做轻量判断，不建议在这里执行业务操作。
     *
     * @param body        报文体字节
     * @param messageConfig 报文配置
     * @return true 表示由当前处理器处理
     */
    default boolean supports(byte[] body, MessageConfig messageConfig) {
        return true;
    }

    /**
     * 处理请求并返回响应对象。
     * <p>
     * 返回 {@code byte[]} 时将直接写回；返回普通对象时会通过 {@link MessageEncoder} 编码后写回；
     * 返回 {@code null} 时只接收不应答。
     * 业务异常可直接抛出；如果当前处理器提供了 {@link #errorResponse(NettyServerErrorContext)}，
     * 服务端会优先构造默认错误回包，否则仅记录日志并关闭本次短连接。
     *
     * @param request 请求对象
     * @return 响应对象、原始响应字节，或 null
     */
    Object handle(T request);

    /**
     * 响应报文配置。
     * <p>
     * 默认读取响应对象上的 {@link FixedMessage} 注解；如果响应报头规则需要沿用请求报文规则，
     * 可在业务处理器中覆盖该方法并返回 {@code requestMessageConfig}。
     * 中心平台的部分响应 VO 只描述字段结构，实际报头/分隔符/MD5 规则与请求一致，
     * 这种场景应覆盖本方法以保持回包格式一致。
     *
     * @param response           响应对象
     * @param requestMessageConfig 请求报文配置
     * @return 响应报文配置
     */
    default MessageConfig responseMessage(Object response, MessageConfig requestMessageConfig) {
        return MessageConfig.from(response.getClass());
    }

    /**
     * 构造异常回包对象或原始字节。
     * <p>
     * 默认不回包；业务处理器可以在这里返回一个标准错误响应对象，或者直接返回完整 TCP 字节流。
     *
     * @param context 本次异常的上下文
     * @return 错误响应对象、原始字节，或 null
     */
    default Object errorResponse(NettyServerErrorContext context) {
        return null;
    }

    /**
     * 错误回包使用的报文配置。
     * <p>
     * 默认沿用正常响应的报文配置；如果请求尚未解码成功，则优先回退到读取 TCP 帧时的报文配置。
     *
     * @param response 错误响应对象
     * @param context  本次异常的上下文
     * @return 错误回包报文配置
     */
    default MessageConfig errorResponseMessage(Object response, NettyServerErrorContext context) {
        MessageConfig requestMessageConfig = context.requestMessageConfig() != null
            ? context.requestMessageConfig()
            : context.frameMessageConfig();
        return responseMessage(response, requestMessageConfig);
    }

}
