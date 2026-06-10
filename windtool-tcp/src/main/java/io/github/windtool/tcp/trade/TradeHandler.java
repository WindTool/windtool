package io.github.windtool.tcp.trade;

/**
 * 交易业务处理器。
 * <p>
 * 业务模块通过实现该接口接入 TCP 服务端。框架负责完成 Socket 读写、报文编解码和交易码分发；
 * 实现类只需要声明交易码、请求/响应类型，并在 {@link #handle(Object)} 中编写业务处理。
 *
 * @param <T> 请求报文对象类型
 * @param <R> 响应报文对象类型
 * @author AprilWind
 */
public interface TradeHandler<T, R> {

    /**
     * 交易码。
     *
     * @return 交易码
     */
    String tradeCode();

    /**
     * 请求报文对象类型。
     *
     * @return 请求报文对象类型
     */
    Class<T> requestClass();

    /**
     * 响应报文对象类型。
     *
     * @return 响应报文对象类型
     */
    Class<R> responseClass();

    /**
     * 执行业务处理。
     *
     * @param request 请求报文对象
     * @return 响应报文对象
     */
    R handle(T request);

}
