package io.github.windtool.tcp.config;

import lombok.extern.slf4j.Slf4j;
import io.github.windtool.tcp.config.properties.NettyProperties;
import io.github.windtool.tcp.core.NettyClient;
import io.github.windtool.tcp.core.NettyServer;
import io.github.windtool.tcp.handler.DefaultNettyServerErrorHandler;
import io.github.windtool.tcp.handler.MessageDecoder;
import io.github.windtool.tcp.handler.MessageEncoder;
import io.github.windtool.tcp.handler.NettyServerErrorHandler;
import io.github.windtool.tcp.handler.NettyServerHandler;
import io.github.windtool.tcp.trade.TradeDispatchServerHandler;
import io.github.windtool.tcp.trade.TradeHandler;
import io.github.windtool.tcp.utils.SpringContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Netty 自动装配。
 * <p>
 * 该模块同时提供短连接 TCP 客户端和服务端能力：
 * <ul>
 *     <li>客户端通过 {@code netty.client.enabled=true} 启用，适合本系统主动发起交易。</li>
 *     <li>服务端通过 {@code netty.server.enabled=true} 启用，适合银行/中心主动推送交易。</li>
 *     <li>当业务侧声明 {@link TradeHandler} Bean 时，会自动注册交易码分发处理器。</li>
 * </ul>
 * 自动装配只负责创建基础设施 Bean，具体交易处理逻辑仍由业务模块实现。
 *
 * @author AprilWind
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(NettyProperties.class)
public class NettyConfig {

    @Autowired
    private NettyProperties nettyProperties;

    @Bean
    @ConditionalOnMissingBean
    public SpringContextHolder nettySpringContextHolder() {
        return new SpringContextHolder();
    }

    /**
     * 创建统一报文编码器。
     *
     * @return 报文编码器
     */
    @Bean
    public MessageEncoder messageEncoder() {
        return new MessageEncoder();
    }

    /**
     * 创建统一报文解码器。
     *
     * @return 报文解码器
     */
    @Bean
    public MessageDecoder messageDecoder() {
        return new MessageDecoder();
    }

    /**
     * 创建默认服务端异常回包处理器。
     *
     * @param encoder 固定长度报文编码器
     * @return 默认异常回包处理器
     */
    @Bean
    @ConditionalOnMissingBean(NettyServerErrorHandler.class)
    public NettyServerErrorHandler nettyServerErrorHandler(MessageEncoder encoder) {
        return new DefaultNettyServerErrorHandler(encoder);
    }

    /**
     * 创建短连接 TCP 客户端。
     * <p>
     * 客户端不是长连接池，每次调用 {@link NettyClient#send(Object, Class)} 都会新建 Socket、
     * 发送报文、读取响应，然后关闭连接。
     *
     * @param encoder 固定长度报文编码器
     * @param decoder 固定长度报文解码器
     * @return TCP 客户端
     */
    @Bean
    @ConditionalOnProperty(prefix = "netty.client", name = "enabled", havingValue = "true", matchIfMissing = false)
    public NettyClient nettyClient(MessageEncoder encoder, MessageDecoder decoder) {
        log.info("✅ 初始化 Netty 客户端，目标：{}:{}, enabled={}, host={}, port={}",
            nettyProperties.getClient().getHost(),
            nettyProperties.getClient().getPort(),
            nettyProperties.getClient().getEnabled(),
            nettyProperties.getClient().getHost(),
            nettyProperties.getClient().getPort());
        return new NettyClient(nettyProperties.getClient(), encoder, decoder);
    }

    /**
     * 创建交易码分发处理器。
     * <p>
     * 业务侧只要实现一个或多个 {@link TradeHandler}，这里就会把它们聚合成一个
     * {@link TradeDispatchServerHandler}。服务端收到报文后先解析交易码，再选择对应业务处理器。
     *
     * @param tradeHandlers 所有交易业务处理器
     * @return 交易码分发处理器
     */
    @Bean
    @ConditionalOnBean(TradeHandler.class)
    @ConditionalOnMissingBean(TradeDispatchServerHandler.class)
    public TradeDispatchServerHandler tradeDispatchServerHandler(ObjectProvider<TradeHandler<?, ?>> tradeHandlers) {
        return new TradeDispatchServerHandler(tradeHandlers.orderedStream().toList());
    }

    /**
     * 创建短连接 TCP 服务端。
     * <p>
     * 服务端启动后监听配置端口，每接收一个连接就读取一笔完整报文，调用匹配的
     * {@link NettyServerHandler} 生成响应，写回后关闭该连接。
     *
     * @param encoder  固定长度报文编码器
     * @param decoder  固定长度报文解码器
     * @param errorHandler 服务端异常回包处理器
     * @param handlers 所有服务端报文处理器
     * @return TCP 服务端生命周期 Bean
     */
    @Bean
    @ConditionalOnProperty(prefix = "netty.server", name = "enabled", havingValue = "true", matchIfMissing = false)
    public NettyServer nettyServer(
        MessageEncoder encoder,
        MessageDecoder decoder,
        NettyServerErrorHandler errorHandler,
        ObjectProvider<NettyServerHandler<?>> handlers
    ) {
        List<NettyServerHandler<?>> handlerList = handlers.orderedStream().toList();
        log.info("✅ 初始化 Netty 服务端，监听：{}:{}，enabled={}，backlog={}，readTimeout={}，maxBodyLength={}，处理器数量：{}，workerCore：{}，workerMax：{}，workerQueue：{}，workerKeepAliveSeconds：{}",
            nettyProperties.getServer().getHost(),
            nettyProperties.getServer().getPort(),
            nettyProperties.getServer().getEnabled(),
            nettyProperties.getServer().getBacklog(),
            nettyProperties.getServer().getReadTimeoutMillis(),
            nettyProperties.getServer().getMaxBodyLength(),
            handlerList.size(),
            nettyProperties.getServer().getWorkerCorePoolSize(),
            nettyProperties.getServer().getWorkerMaxPoolSize(),
            nettyProperties.getServer().getWorkerQueueCapacity(),
            nettyProperties.getServer().getWorkerKeepAliveSeconds());
        return new NettyServer(nettyProperties.getServer(), encoder, decoder, errorHandler, handlerList);
    }
}
