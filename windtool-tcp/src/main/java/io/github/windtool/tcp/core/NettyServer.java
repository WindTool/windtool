package io.github.windtool.tcp.core;

import lombok.extern.slf4j.Slf4j;
import io.github.windtool.tcp.config.properties.NettyProperties;
import io.github.windtool.tcp.handler.MessageDecoder;
import io.github.windtool.tcp.handler.MessageEncoder;
import io.github.windtool.tcp.handler.NettyServerErrorHandler;
import io.github.windtool.tcp.handler.NettyServerHandler;
import io.github.windtool.tcp.model.TrailingReadPlan;
import io.github.windtool.tcp.utils.NettyIoUtils;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 短连接 TCP 服务端。
 * <p>
 * 服务端只负责 TCP 层面的生命周期和固定长度报文帧处理：
 * <ol>
 *     <li>监听配置端口，接收一条短连接。</li>
 *     <li>按 {@link MessageConfig} 读取报头、可选分隔符和完整报文体。</li>
 *     <li>根据 {@link NettyServerHandler#supports(byte[], MessageConfig)} 选择业务处理器。</li>
 *     <li>把报文体解码为业务请求对象，调用处理器生成响应。</li>
 *     <li>将响应编码为完整报文写回，随后关闭连接。</li>
 * </ol>
 * 交易码识别、业务校验、数据库操作等逻辑不放在该类中，统一交给处理器完成。
 *
 * @author AprilWind
 */
@Slf4j
public class NettyServer implements SmartLifecycle {

    private final NettyProperties.Server config;
    private final MessageEncoder encoder;
    private final MessageDecoder decoder;
    private final NettyServerErrorHandler errorHandler;
    private final List<NettyServerHandler<?>> handlers;
    private final ExecutorService acceptExecutor;
    private final ExecutorService workerExecutor;

    private volatile boolean running;
    private ServerSocket serverSocket;

    /**
     * 创建短连接 TCP 服务端。
     *
     * @param config       服务端监听和线程池配置
     * @param encoder      报文编码器
     * @param decoder      报文解码器
     * @param errorHandler 服务端异常回包处理器
     * @param handlers     服务端业务处理器列表
     */
    public NettyServer(
        NettyProperties.Server config,
        MessageEncoder encoder,
        MessageDecoder decoder,
        NettyServerErrorHandler errorHandler,
        List<NettyServerHandler<?>> handlers
    ) {
        this.config = config;
        this.encoder = encoder;
        this.decoder = decoder;
        this.errorHandler = errorHandler;
        this.handlers = new ArrayList<>(handlers);
        AnnotationAwareOrderComparator.sort(this.handlers);
        this.acceptExecutor = Executors.newSingleThreadExecutor(namedThreadFactory("netty-server-accept"));
        this.workerExecutor = createWorkerExecutor(config);
    }

    /**
     * 启动 TCP 服务端监听。
     * <p>
     * 启动时会创建 ServerSocket、绑定配置端口，并提交单独的 accept 线程。
     */
    @Override
    public void start() {
        if (running) {
            return;
        }
        if (handlers.isEmpty()) {
            throw new IllegalStateException("未找到 NettyServerHandler，无法启动 Netty 服务端");
        }

        try {
            // 使用显式 bind 可以同时指定监听地址和 backlog，便于内网地址绑定及高并发联调配置。
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(config.getHost(), config.getPort()), config.getBacklog());
            running = true;
            acceptExecutor.execute(this::acceptLoop);
            log.info("初始化 Netty 服务端，监听：{}:{}，backlog：{}，workerCore：{}，workerMax：{}，workerQueue：{}",
                config.getHost(), config.getPort(), config.getBacklog(),
                config.getWorkerCorePoolSize(), config.getWorkerMaxPoolSize(), config.getWorkerQueueCapacity());
        } catch (IOException e) {
            throw new RuntimeException("Netty 服务端启动失败: " + e.getMessage(), e);
        }
    }

    /**
     * 停止 TCP 服务端并关闭线程池。
     */
    @Override
    public void stop() {
        running = false;
        closeServerSocket();
        shutdownExecutor(acceptExecutor);
        shutdownExecutor(workerExecutor);
        log.info("Netty 服务端已停止");
    }

    /**
     * 停止 TCP 服务端并执行 Spring 生命周期回调。
     *
     * @param callback 停止完成后的回调
     */
    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    /**
     * 判断服务端是否处于运行状态。
     *
     * @return true 表示已经启动且未停止
     */
    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 是否随 Spring 容器自动启动。
     *
     * @return true 表示自动启动
     */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /**
     * 返回生命周期阶段。
     *
     * @return 最大阶段值，确保尽量晚启动、早停止
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /**
     * 循环接收客户端短连接并提交到 worker 线程池。
     */
    private void acceptLoop() {
        while (running) {
            try {
                // 每个连接只承载一笔交易，读写完成后在 handleConnection 的 try-with-resources 中关闭。
                Socket socket = serverSocket.accept();
                log.debug("Netty 服务端接收到连接，远端：{}，worker状态：{}", socket.getRemoteSocketAddress(), describeExecutor(workerExecutor));
                submitConnection(socket);
            } catch (SocketException e) {
                if (running) {
                    log.error("Netty 服务端接收连接异常", e);
                }
            } catch (IOException e) {
                if (running) {
                    log.error("Netty 服务端接收连接异常", e);
                }
            }
        }
    }

    /**
     * 将 Socket 连接提交给 worker 线程池处理。
     *
     * @param socket 已建立的客户端连接
     */
    private void submitConnection(Socket socket) {
        try {
            log.debug("提交连接到 worker 线程池，远端：{}，worker状态：{}", socket.getRemoteSocketAddress(), describeExecutor(workerExecutor));
            workerExecutor.execute(() -> handleConnection(socket));
        } catch (RejectedExecutionException e) {
            SocketAddress remoteAddress = socket.getRemoteSocketAddress();
            closeSocket(socket);
            log.warn("Netty 服务端 worker 线程池已满，拒绝连接，远端：{}，core：{}，max：{}，queue：{}",
                remoteAddress,
                config.getWorkerCorePoolSize(),
                config.getWorkerMaxPoolSize(),
                config.getWorkerQueueCapacity());
        }
    }

    /**
     * 处理单个短连接上的一笔 TCP 交易。
     *
     * @param socket 已建立的客户端连接
     */
    private void handleConnection(Socket socket) {
        SocketAddress remoteAddress = socket.getRemoteSocketAddress();
        MessageConfig frameMessageConfig = null;
        NettyServerHandler<?> handler = null;
        Class<?> requestClass = null;
        MessageConfig requestMessageConfig = null;
        Object request = null;
        byte[] frameBody = null;
        try (
            Socket client = socket;
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream()
        ) {
            client.setSoTimeout(config.getReadTimeoutMillis());
            log.info("Netty 服务端 TCP 连接已建立，远端：{}，等待接收应用报文", remoteAddress);
            log.debug("连接上下文，远端：{}，readTimeout={}ms，maxBodyLength={}，worker状态：{}",
                remoteAddress, config.getReadTimeoutMillis(), config.getMaxBodyLength(), describeExecutor(workerExecutor));
            // 这里必须在资源作用域内单独 catch 业务异常。
            // 否则 out 会先被 try-with-resources 关闭，后续默认错误回包就无法真正写到 TCP 连接上。
            try {
                // 第一层处理器只用于确定 TCP 帧格式；普通处理器通常等同于 requestClass 注解，
                // 聚合分发处理器会返回统一帧格式，在未解析交易码前先把完整报文体读出来。
                NettyServerHandler<?> frameHandler = handlers.get(0);
                handler = frameHandler;
                frameMessageConfig = getMessageConfig(frameHandler);
                log.debug("开始读取TCP帧，远端：{}，frameHandler={}，frameMessageConfig={{headerLength={},headerIncludeSelf={},useDelimiter={},delimiter={},appendEndDelimiter={},targetCharset={},systemCharset={},padType={},align={}}}",
                    remoteAddress, frameHandler.getClass().getSimpleName(), frameMessageConfig.headerLength(), frameMessageConfig.headerIncludeSelf(),
                    frameMessageConfig.useDelimiter(), frameMessageConfig.delimiter(),
                    frameMessageConfig.appendEndDelimiter(), frameMessageConfig.targetCharset(), frameMessageConfig.systemCharset(), frameMessageConfig.padType(), frameMessageConfig.align());
                MessageFrame frame = readFrame(in, frameMessageConfig);
                while (frame.body().length == 0) {
                    log.warn("Netty 服务端收到空报文帧，远端：{}，继续等待下一笔报文", remoteAddress);
                    frame = readFrame(in, frameMessageConfig);
                }
                frameBody = frame.body();

                // 第二层处理器负责业务路由。它可以根据原始报文体中的交易码判断是否支持该报文，
                // 并返回真正用于解码的请求对象类型。
                handler = findHandler(frameBody, frameMessageConfig);
                requestClass = handler.requestClass(frameBody, frameMessageConfig);
                requestMessageConfig = getMessageConfig(requestClass);
                log.info("业务处理器匹配完成，远端：{}，handler={}，requestClass={}",
                    remoteAddress, handler.getClass().getSimpleName(), requestClass.getSimpleName());
                log.debug("请求报文配置，远端：{}，requestConfig={{headerLength={},headerIncludeSelf={},useDelimiter={},delimiter={},appendEndDelimiter={},targetCharset={},systemCharset={},padType={},align={}}}",
                    remoteAddress,
                    requestMessageConfig.headerLength(), requestMessageConfig.headerIncludeSelf(), requestMessageConfig.useDelimiter(), requestMessageConfig.delimiter(),
                    requestMessageConfig.appendEndDelimiter(), requestMessageConfig.targetCharset(), requestMessageConfig.systemCharset(), requestMessageConfig.padType(), requestMessageConfig.align());

                byte[] requestBytes = readTrailingBody(in, frameBody, requestClass, requestMessageConfig);
                frameBody = requestBytes;

                log.debug("开始解码请求，远端：{}，requestClass={}，requestLength={}，requestPreview={}",
                    remoteAddress, requestClass.getSimpleName(), requestBytes.length,
                    new String(requestBytes, requestMessageConfig.targetCharset().getCharset()));
                request = decoder.decode(requestBytes, requestClass, requestMessageConfig);
                log.info("Netty 服务端接收到请求，远端：{}，请求对象：{}", remoteAddress, request);

                Object response = handleRequest(handler, request);
                if (response == null) {
                    log.info("Netty 服务端处理完成，远端：{}，无响应报文", remoteAddress);
                    return;
                }

                log.debug("开始编码响应，远端：{}，responseType={}，responseClassHint={}",
                    remoteAddress, response.getClass().getSimpleName(), handler.getClass().getSimpleName());
                byte[] responseBytes = encodeResponse(handler, response, requestMessageConfig);
                log.debug("响应编码完成，远端：{}，responseLength={}，responsePreview={}，responseHex={}",
                    remoteAddress, responseBytes.length, new String(responseBytes, requestMessageConfig.targetCharset().getCharset()), NettyIoUtils.bytesToHex(responseBytes));
                out.write(responseBytes);
                out.flush();
                log.info("Netty 服务端响应已发送，远端：{}，响应长度：{} 字节", remoteAddress, responseBytes.length);
            } catch (SocketTimeoutException e) {
                log.warn("Netty 服务端等待报文超时，远端：{}，超时时间：{}ms，原因：{}",
                    remoteAddress, config.getReadTimeoutMillis(), e.getMessage());
                writeErrorResponse(out, new NettyServerErrorContext(
                    remoteAddress, frameBody, frameMessageConfig, handler, requestClass, requestMessageConfig, request, e));
            } catch (EOFException e) {
                log.warn("Netty 服务端连接已关闭但未收到完整报文，远端：{}，原因：{}", remoteAddress, e.getMessage());
            } catch (Exception e) {
                log.error("Netty 服务端处理连接异常，远端：{}", remoteAddress, e);
                writeErrorResponse(out, new NettyServerErrorContext(
                    remoteAddress, frameBody, frameMessageConfig, handler, requestClass, requestMessageConfig, request, e));
            }
        } catch (Exception e) {
            log.error("Netty 服务端建立连接异常，远端：{}", remoteAddress, e);
        } finally {
            log.debug("Netty 服务端连接处理结束，远端：{}", remoteAddress);
        }
    }

    /**
     * 按报头配置读取一帧完整 TCP 报文体。
     *
     * @param in            Socket 输入流
     * @param messageConfig TCP 帧报文配置
     * @return 已读取的报文体帧
     * @throws IOException 读取失败、长度非法或超过限制时抛出
     */
    private MessageFrame readFrame(InputStream in, MessageConfig messageConfig) throws IOException {
        Charset targetCharset = messageConfig.targetCharset().getCharset();
        int headerLength = messageConfig.headerLength();
        log.debug("开始读取TCP帧头，headerLength={}，useDelimiter={}，appendEndDelimiter={}，headerIncludeSelf={}，targetCharset={}",
            headerLength, messageConfig.useDelimiter(), messageConfig.appendEndDelimiter(), messageConfig.headerIncludeSelf(), targetCharset);

        byte[] header = NettyIoUtils.readExact(in, headerLength);
        // 报头后的分隔符属于链路帧的一部分，不交给业务对象解码；如果协议声明了分隔符，
        // 这里必须先消费掉，否则后续报文体会整体右移 1 字节。
        if (messageConfig.useDelimiter()) {
            byte[] delimiter = NettyIoUtils.readExact(in, 1);
            if (delimiter[0] != (byte) messageConfig.delimiter()) {
                throw new IOException("报头分隔符不匹配，实际值：" + new String(delimiter, targetCharset));
            }
            log.debug("TCP帧头分隔符读取成功，delimiter={}", (char) delimiter[0]);
        }

        String headerStr = new String(header, targetCharset).trim();
        int declaredLength;
        try {
            declaredLength = Integer.parseInt(headerStr);
        } catch (NumberFormatException e) {
            throw new IOException("报头长度非法：" + headerStr, e);
        }

        // headerIncludeSelf=true 表示报头数字是总包长，需要扣除已经读取的报头和报头分隔符。
        int headerTotalLength = headerLength + (messageConfig.useDelimiter() ? 1 : 0);
        int bodyLength = messageConfig.headerIncludeSelf() ? declaredLength - headerTotalLength : declaredLength;
        if (bodyLength < 0) {
            throw new IOException("报文体长度非法：" + bodyLength);
        }
        if (bodyLength > config.getMaxBodyLength()) {
            throw new IOException("报文体长度超过限制：" + bodyLength);
        }

        log.debug("Netty 服务端接收到报头：{}，声明总长度：{} 字节，预计报文体长度：{} 字节",
            headerStr, declaredLength, bodyLength);
        byte[] body = NettyIoUtils.readExact(in, bodyLength);
        log.debug("Netty 服务端接收到报文，报头：{}，报文体长度：{} 字节，bodyPreview={}，bodyHex={}",
            headerStr, body.length, new String(body, targetCharset), NettyIoUtils.bytesToHex(body));
        return new MessageFrame(body);
    }

    /**
     * 根据原始报文体匹配可处理该交易的业务处理器。
     *
     * @param body          报文体字节
     * @param messageConfig TCP 帧报文配置
     * @return 匹配到的业务处理器
     */
    private NettyServerHandler<?> findHandler(byte[] body, MessageConfig messageConfig) {
        for (NettyServerHandler<?> handler : handlers) {
            // supports 收到的是原始报文体字节，适合做轻量路由判断，不承担解码和业务处理。
            if (handler.supports(body, messageConfig)) {
                return handler;
            }
        }
        Charset targetCharset = messageConfig.targetCharset().getCharset();
        throw new IllegalStateException(
            "未找到可处理当前报文的 NettyServerHandler，处理器数量：" + handlers.size()
                + "，报文体：[" + new String(body, targetCharset) + "]"
                + "，hex：[" + NettyIoUtils.bytesToHex(body) + "]"
        );
    }

    /**
     * 调用泛型业务处理器处理请求。
     *
     * @param handler 业务处理器
     * @param request 已解码的请求对象
     * @return 业务响应对象、完整响应字节或 null
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object handleRequest(NettyServerHandler handler, Object request) {
        return handler.handle(request);
    }

    /**
     * 将业务响应对象编码为完整 TCP 回包。
     *
     * @param handler              当前业务处理器
     * @param response             业务响应对象或完整响应字节
     * @param requestMessageConfig 请求报文配置
     * @return 完整 TCP 回包字节
     * @throws Exception 编码失败时抛出
     */
    private byte[] encodeResponse(NettyServerHandler<?> handler, Object response, MessageConfig requestMessageConfig) throws Exception {
        if (response instanceof byte[] bytes) {
            // 业务方返回 byte[] 时认为已经是完整 TCP 响应报文，服务端不再做二次编码。
            return bytes;
        }

        // 默认按响应对象自身注解编码；需要完全沿用请求报文规则时，可由 handler 覆盖 responseMessage。
        MessageConfig responseMessageConfig = handler.responseMessage(response, requestMessageConfig);
        if (responseMessageConfig == null) {
            throw new IllegalArgumentException("响应实体类必须标注 @FixedMessage 注解，或返回 byte[] 原始报文");
        }
        return encoder.encode(response, responseMessageConfig);
    }

    /**
     * 读取指定报文实体类的运行期报文配置。
     *
     * @param messageClass 报文实体类型
     * @return 运行期报文配置
     */
    private MessageConfig getMessageConfig(Class<?> messageClass) {
        return MessageConfig.from(messageClass);
    }

    /**
     * 读取业务处理器声明的 TCP 帧配置。
     *
     * @param handler 业务处理器
     * @return TCP 帧报文配置
     */
    private MessageConfig getMessageConfig(NettyServerHandler<?> handler) {
        // frameMessage 允许“读取 TCP 帧的协议”和“业务请求对象的协议”拆开配置，
        // 聚合分发场景会先按统一帧格式收包，再依据交易码切换到实际 BO。
        MessageConfig messageConfig = handler.frameMessage();
        if (messageConfig == null) {
            throw new IllegalArgumentException("NettyServerHandler 未提供 TCP 帧报文配置：" + handler.getClass().getName());
        }
        return messageConfig;
    }

    /**
     * 分阶段补读不计入报头长度的尾部字段。
     * <p>
     * 典型场景是 4005/5122/5123 这类“总包长只统计前 N 个域”的协议：
     * 先根据模型补读固定长度后缀，再依据长度字段补读最后一个不定长内容字段。
     */
    private byte[] readTrailingBody(InputStream in, byte[] body, Class<?> messageClass, MessageConfig messageConfig) throws IOException {
        TrailingReadPlan trailingPlan = decoder.resolveTrailingReadPlan(messageClass, messageConfig);
        if (trailingPlan.isEmpty()) {
            log.debug("请求无需尾部补读，messageClass={}", messageClass.getSimpleName());
            return body;
        }

        log.debug("请求尾部补读计划，messageClass={}，fixedBytesBeforeVariable={}，hasVariableField={}",
            messageClass.getSimpleName(), trailingPlan.fixedBytesBeforeVariable(), trailingPlan.hasVariableField());
        if (trailingPlan.fixedBytesBeforeVariable() > 0) {
            // 先把“不计包长的固定后缀”补齐，后面的长度字段才能被完整解析出来。
            byte[] fixedTailBytes = NettyIoUtils.readExact(in, trailingPlan.fixedBytesBeforeVariable());
            body = NettyIoUtils.appendBytes(body, fixedTailBytes);
            log.debug("Netty 服务端已补读固定后缀：{} 字节，当前完整报文体长度：{} 字节，tailPreview={}",
                fixedTailBytes.length, body.length, new String(fixedTailBytes, messageConfig.targetCharset().getCharset()));
        }

        if (trailingPlan.hasVariableField()) {
            int variableBytes = decoder.resolveTrailingVariableBytesToRead(body, messageClass, messageConfig);
            log.debug("请求不定长尾部补读判断完成，messageClass={}，variableBytes={}",
                messageClass.getSimpleName(), variableBytes);
            if (variableBytes > 0) {
                // 第二步再根据长度字段补读最后一个不定长内容域。
                byte[] variableTailBytes = NettyIoUtils.readExact(in, variableBytes);
                body = NettyIoUtils.appendBytes(body, variableTailBytes);
                log.debug("Netty 服务端已补读不定长尾部：{} 字节，当前完整报文体长度：{} 字节，tailPreview={}",
                    variableTailBytes.length, body.length, new String(variableTailBytes, messageConfig.targetCharset().getCharset()));
            }
        }

        if (body.length > config.getMaxBodyLength()) {
            throw new IOException("完整报文体长度超过限制：" + body.length);
        }
        return body;
    }

    /**
     * 将默认或业务自定义的错误报文回写到当前短连接。
     * <p>
     * 这里只接受“完整 TCP 报文”字节，具体报文字段由 {@link NettyServerErrorHandler}
     * 和业务处理器共同决定。
     */
    private void writeErrorResponse(OutputStream out, NettyServerErrorContext context) {
        if (out == null) {
            log.warn("异常回包失败，输出流尚未建立，远端：{}", context.remoteAddress());
            return;
        }

        try {
            byte[] errorBytes = errorHandler.buildErrorResponse(context);
            if (errorBytes == null || errorBytes.length == 0) {
                log.debug("当前异常未生成错误回包，远端：{}，handler={}",
                    context.remoteAddress(),
                    context.handler() == null ? "null" : context.handler().getClass().getSimpleName());
                return;
            }
            out.write(errorBytes);
            out.flush();
            log.info("Netty 服务端错误回包已发送，远端：{}，responseLength={}，responseHex={}",
                context.remoteAddress(), errorBytes.length, NettyIoUtils.bytesToHex(errorBytes));
        } catch (Exception ex) {
            log.error("Netty 服务端错误回包发送失败，远端：{}", context.remoteAddress(), ex);
        }
    }

    /**
     * 关闭服务端监听 Socket。
     */
    private void closeServerSocket() {
        if (serverSocket == null) {
            return;
        }
        try {
            serverSocket.close();
        } catch (IOException e) {
            log.warn("关闭 Netty 服务端 ServerSocket 异常", e);
        }
    }

    /**
     * 立即关闭线程池并等待短暂退出。
     *
     * @param executorService 待关闭线程池
     */
    private void shutdownExecutor(ExecutorService executorService) {
        executorService.shutdownNow();
        try {
            if (!executorService.awaitTermination(3, TimeUnit.SECONDS)) {
                log.warn("Netty 服务端线程池未在超时时间内退出");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 创建带固定名称前缀的守护线程工厂。
     *
     * @param prefix 线程名前缀
     * @return 线程工厂
     */
    private static ThreadFactory namedThreadFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(prefix + "-" + thread.getId());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 创建处理客户端连接的有界 worker 线程池。
     *
     * @param config 服务端线程池配置
     * @return worker 线程池
     */
    private static ExecutorService createWorkerExecutor(NettyProperties.Server config) {
        int corePoolSize = config.getWorkerCorePoolSize();
        int maxPoolSize = config.getWorkerMaxPoolSize();
        int queueCapacity = config.getWorkerQueueCapacity();
        int keepAliveSeconds = config.getWorkerKeepAliveSeconds();

        if (corePoolSize <= 0) {
            throw new IllegalArgumentException("netty.server.worker-core-pool-size 必须大于 0");
        }
        if (maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException("netty.server.worker-max-pool-size 不能小于 worker-core-pool-size");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("netty.server.worker-queue-capacity 必须大于 0");
        }

        // 使用有界队列，避免异常流量下无限堆积连接任务把 JVM 内存吃满。
        return new ThreadPoolExecutor(
            corePoolSize,
            maxPoolSize,
            keepAliveSeconds,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            namedThreadFactory("netty-server-worker"),
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * 尽力关闭被拒绝或异常的客户端连接。
     *
     * @param socket 客户端连接
     */
    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 拒绝连接时只需要尽力关闭 Socket。
        }
    }

    /**
     * 输出线程池运行状态摘要。
     *
     * @param executorService 线程池
     * @return 可写入日志的线程池状态
     */
    private String describeExecutor(ExecutorService executorService) {
        if (executorService instanceof ThreadPoolExecutor threadPoolExecutor) {
            return "poolSize=" + threadPoolExecutor.getPoolSize()
                + ",active=" + threadPoolExecutor.getActiveCount()
                + ",queued=" + threadPoolExecutor.getQueue().size()
                + ",completed=" + threadPoolExecutor.getCompletedTaskCount()
                + ",taskCount=" + threadPoolExecutor.getTaskCount();
        }
        return executorService.getClass().getSimpleName();
    }

    /**
     * 统一的 TCP 帧读取结果。
     * <p>
     * 这里只保留报文体字节；报头已经在 readFrame 阶段消费并用于长度校验。
     */
    private record MessageFrame(byte[] body) {
    }

}
