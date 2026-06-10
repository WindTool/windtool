package io.github.windtool.tcp.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Netty 配置项。
 * <p>
 * 本模块当前使用 JDK Socket 实现短连接 TCP 通讯，保留 Netty 命名以兼容模块命名和既有配置。
 * 客户端和服务端配置相互独立，可以只启用其中一端，也可以在联调环境同时启用。
 *
 * @author AprilWind
 */
@Data
@ConfigurationProperties(prefix = "netty")
public class NettyProperties {

    /**
     * 客户端配置
     */
    private Client client = new Client();

    /**
     * 服务端配置
     */
    private Server server = new Server();

    /**
     * Netty 客户端配置项
     */
    @Data
    public static class Client {

        /**
         * 是否启用 Netty 客户端。
         * <p>
         * false 时不注册 {@code NettyClient} Bean，避免没有外部目标地址时误发连接。
         */
        private Boolean enabled = false;

        /**
         * 目标服务地址。
         * <p>
         * 客户端每次发送报文时都会连接该地址。
         */
        private String host = "127.0.0.1";

        /**
         * 目标服务端口。
         */
        private Integer port = 9000;

        /**
         * 连接超时时间，单位毫秒。
         */
        private Integer connectTimeoutMillis = 10000;

        /**
         * 单连接读取超时时间，单位毫秒。
         */
        private Integer readTimeoutMillis = 60000;

        /**
         * 最大报文体长度，单位字节。
         */
        private Integer maxBodyLength = 1024 * 1024 * 10;

    }

    /**
     * Netty 服务端配置项
     */
    @Data
    public static class Server {

        /**
         * 是否启用 Netty 服务端。
         * <p>
         * true 时会注册 {@code NettyServer} 生命周期 Bean，并在 Spring 容器启动后开始监听端口。
         */
        private Boolean enabled = false;

        /**
         * 服务监听地址。
         * <p>
         * 默认 {@code 0.0.0.0} 表示监听所有网卡；生产环境可改成指定内网地址。
         */
        private String host = "0.0.0.0";

        /**
         * 服务监听端口。
         * <p>
         * 外部银行/中心系统需要连接该端口推送短连接报文。
         */
        private Integer port = 9001;

        /**
         * 连接等待队列长度。
         * <p>
         * 传给 {@link java.net.ServerSocket#bind(java.net.SocketAddress, int)} 的 backlog，
         * 短时间并发连接较多时可适当调大。
         */
        private Integer backlog = 128;

        /**
         * 单连接读取超时时间，单位毫秒。
         * <p>
         * 防止对端建立连接后长时间不发送完整报文导致工作线程被占住。
         */
        private Integer readTimeoutMillis = 60000;

        /**
         * 最大报文体长度，单位字节。
         * <p>
         * 服务端解析报头后会先校验长度，超过该限制直接拒绝，避免异常长度占用内存。
         */
        private Integer maxBodyLength = 1024 * 1024 * 10;

        /**
         * 服务端 worker 线程池核心线程数。
         */
        private Integer workerCorePoolSize = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);

        /**
         * 服务端 worker 线程池最大线程数。
         */
        private Integer workerMaxPoolSize = Math.max(8, Runtime.getRuntime().availableProcessors() * 4);

        /**
         * worker 任务队列容量。
         */
        private Integer workerQueueCapacity = 1000;

        /**
         * worker 空闲线程存活时间，单位秒。
         */
        private Integer workerKeepAliveSeconds = 60;

    }

}
