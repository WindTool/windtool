package io.github.windtool.tcp.core;

import lombok.extern.slf4j.Slf4j;
import io.github.windtool.tcp.config.properties.NettyProperties;
import io.github.windtool.tcp.handler.MessageDecoder;
import io.github.windtool.tcp.handler.MessageEncoder;
import io.github.windtool.tcp.model.TrailingReadPlan;
import io.github.windtool.tcp.utils.MessageCodecUtils;
import io.github.windtool.tcp.utils.NettyIoUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * 短连接 TCP 客户端。
 * <p>
 * 客户端按固定长度报文协议工作：请求对象通过 {@link MessageEncoder} 编码为
 * {@code 报头 + 可选分隔符 + 报文体}，发送后立即读取一笔响应报文，最后关闭 Socket。
 * 该类不维护长连接，也不做重试；业务侧如果需要重试，应在调用方根据交易幂等性自行控制。
 *
 * @author AprilWind
 */
@Slf4j
public class NettyClient {

    private final NettyProperties.Client config;
    private final MessageEncoder encoder;
    private final MessageDecoder decoder;

    /**
     * 创建短连接 TCP 客户端。
     *
     * @param config  客户端连接配置
     * @param encoder 报文编码器
     * @param decoder 报文解码器
     */
    public NettyClient(NettyProperties.Client config, MessageEncoder encoder, MessageDecoder decoder) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.config = config;
    }

    /**
     * 发送请求并接收响应
     *
     * <p>处理流程：
     * <ol>
     *     <li>根据请求对象和响应对象的类级报文注解生成 {@link MessageConfig}</li>
     *     <li>建立 TCP 连接并获取输入输出流</li>
     *     <li>将请求对象编码为固定长度报文并发送</li>
     *     <li>读取响应固定长度报头，必要时再消费报头后的分隔符</li>
     *     <li>根据报文体长度读取完整报文体</li>
     *     <li>如果响应 VO 把总包长声明为第 0 个字段，将报头拼回解码字节流</li>
     *     <li>使用 {@link MessageDecoder} 解码响应报文为响应对象</li>
     *     <li>关闭连接并返回响应对象</li>
     * </ol>
     *
     * @param <T>           请求对象类型
     * @param <R>           响应对象类型
     * @param request       请求对象，必须标注报文配置注解
     * @param responseClass 响应对象类
     * @return 解码后的响应对象
     * @throws IllegalArgumentException 如果请求对象或响应对象未标注报文配置注解
     * @throws RuntimeException         TCP 发送/接收或解码异常
     */
    public <T, R> R send(T request, Class<R> responseClass) {
        MessageConfig requestMessageConfig = MessageConfig.from(request.getClass());
        MessageConfig responseMessageConfig = MessageConfig.from(responseClass);

        // 请求格式负责发送编码；响应格式负责读取后的对象解码。
        // 现有中心平台响应 VO 多数没有显式声明 useDelimiter=true，但实际报文仍带 "|"，
        // 因此需要通过 resolveResponseDecodeMessage 按同一协议修正响应解码规则。
        int headerLength = requestMessageConfig.headerLength();
        MessageConfig responseDecodeMessageConfig = resolveResponseDecodeMessage(
            requestMessageConfig, responseMessageConfig, responseClass, headerLength);
        Charset targetCharset = requestMessageConfig.targetCharset().getCharset();
        Charset responseCharset = responseDecodeMessageConfig.targetCharset().getCharset();
        byte[] sendBytes;
        try {
            // 1. 编码报文（在建立TCP连接之前完成）
            sendBytes = encoder.encode(request, requestMessageConfig);
        } catch (Exception e) {
            throw new RuntimeException("报文编码异常: " + e.getMessage(), e);
        }

        log.debug("客户端发送前准备完成，requestType={}，responseType={}，requestConfig={{headerLength={},headerIncludeSelf={},useDelimiter={},delimiter={},appendEndDelimiter={},targetCharset={},systemCharset={},padType={},align={}}}，responseConfig={{headerLength={},headerIncludeSelf={},useDelimiter={},delimiter={},appendEndDelimiter={},targetCharset={},systemCharset={},padType={},align={}}}",
            request.getClass().getSimpleName(), responseClass.getSimpleName(),
            requestMessageConfig.headerLength(), requestMessageConfig.headerIncludeSelf(), requestMessageConfig.useDelimiter(), requestMessageConfig.delimiter(),
            requestMessageConfig.appendEndDelimiter(), requestMessageConfig.targetCharset(), requestMessageConfig.systemCharset(), requestMessageConfig.padType(), requestMessageConfig.align(),
            responseDecodeMessageConfig.headerLength(), responseDecodeMessageConfig.headerIncludeSelf(), responseDecodeMessageConfig.useDelimiter(), responseDecodeMessageConfig.delimiter(),
            responseDecodeMessageConfig.appendEndDelimiter(), responseDecodeMessageConfig.targetCharset(), responseDecodeMessageConfig.systemCharset(), responseDecodeMessageConfig.padType(), responseDecodeMessageConfig.align());
        log.info("➡️ 准备发送报文长度: {} 字节", sendBytes.length);
        log.debug("➡️ 准备发送报文内容: [{}]", new String(sendBytes, targetCharset));
        log.debug("➡️ 发送报文 hex: {}", NettyIoUtils.bytesToHex(sendBytes));

        byte[] responseBytes;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(config.getHost(), config.getPort()), config.getConnectTimeoutMillis());
            socket.setSoTimeout(config.getReadTimeoutMillis());
            try (
                OutputStream out = socket.getOutputStream();
                PushbackInputStream in = new PushbackInputStream(socket.getInputStream(), 1)
            ) {
                log.info("客户端 TCP 连接已建立，本地：{}，远端：{}", socket.getLocalSocketAddress(), socket.getRemoteSocketAddress());
                // 2. 发送报文
                out.write(sendBytes);
                out.flush();
                log.info("✅ 请求报文已发送，等待响应，requestLength={} 字节", sendBytes.length);

                // 3. 接收报头
                log.debug("开始读取响应报头，headerLength={}，responseCharset={}，headerIncludeSelf={}",
                    headerLength, responseCharset, responseMessageConfig.headerIncludeSelf());
                byte[] header = NettyIoUtils.readExact(in, headerLength);
                String headerStr = new String(header, responseCharset).trim();
                log.debug("✅ 接收到报头：{} (长度: {} 字节)，headerHex={}，headerPreview={}",
                    headerStr, headerLength, NettyIoUtils.bytesToHex(header), new String(header, responseCharset));

                log.debug("开始判断响应报头后的分隔符，requestUseDelimiter={}，responseUseDelimiter={}",
                    requestMessageConfig.useDelimiter(), responseMessageConfig.useDelimiter());
                boolean hasHeaderDelimiter = readHeaderDelimiterIfPresent(in, requestMessageConfig, responseDecodeMessageConfig);
                log.debug("响应报头分隔符判断结束，hasHeaderDelimiter={}", hasHeaderDelimiter);

                // 解析报头长度，并根据是否包含自身计算报文体长度
                int totalLength = Integer.parseInt(headerStr);
                int headerWireLength = headerLength + (hasHeaderDelimiter ? 1 : 0);
                // 响应 VO 如果存在 order=0 的总包长字段，说明报头数字计入总包长。
                // 这种场景需要扣掉链路层已经读取的报头和分隔符，剩余才是后续要读取的报文体。
                boolean headerIncludeSelf = responseMessageConfig.headerIncludeSelf()
                    || MessageCodecUtils.hasHeaderField(responseClass, headerLength);
                int bodyLength = headerIncludeSelf ? totalLength - headerWireLength : totalLength;
                if (bodyLength < 0) {
                    throw new IOException("报文体长度非法：" + bodyLength);
                }
                if (bodyLength > config.getMaxBodyLength()) {
                    throw new IOException("响应报文体长度超过限制：" + bodyLength);
                }
                log.debug("响应长度计算完成，headerStr={}，totalLength={}，headerWireLength={}，headerIncludeSelf={}，bodyLength={}，responseIncludesHeader={}",
                    headerStr, totalLength, headerWireLength, headerIncludeSelf, bodyLength,
                    MessageCodecUtils.hasHeaderField(responseClass, headerLength));

                // 读取剩余报文体；如果 VO 需要总包长字段，buildResponseBytes 会把报头拼回去供 codec 解析。
                byte[] body = NettyIoUtils.readExact(in, bodyLength);
                log.debug("响应主体读取完成，bodyLength={}，bodyPreview={}，bodyHex={}",
                    body.length, new String(body, responseCharset), NettyIoUtils.bytesToHex(body));
                body = readTrailingBody(in, body, responseClass, responseDecodeMessageConfig);
                responseBytes = buildResponseBytes(responseClass, responseDecodeMessageConfig, header, hasHeaderDelimiter, body);
                log.debug("响应字节组装完成，responseLength={}，responsePreview={}，responseHex={}",
                    responseBytes.length, new String(responseBytes, responseCharset), NettyIoUtils.bytesToHex(responseBytes));
            }
        } catch (Exception e) {
            // 捕获所有异常并包装为运行时异常
            throw new RuntimeException("TCP 通讯异常: " + e.getMessage(), e);
        }

        // 解码
        log.info("✅ 接收到响应报文，长度: {} 字节", responseBytes.length);
        log.debug("响应报文内容：[{}]", new String(responseBytes, responseCharset));
        log.debug("开始解码响应报文，responseType={}，decodeConfig={{headerLength={},headerIncludeSelf={},useDelimiter={},delimiter={},appendEndDelimiter={},targetCharset={},systemCharset={},padType={},align={}}}",
            responseClass.getSimpleName(), responseDecodeMessageConfig.headerLength(), responseDecodeMessageConfig.headerIncludeSelf(),
            responseDecodeMessageConfig.useDelimiter(), responseDecodeMessageConfig.delimiter(),
            responseDecodeMessageConfig.appendEndDelimiter(), responseDecodeMessageConfig.targetCharset(), responseDecodeMessageConfig.systemCharset(), responseDecodeMessageConfig.padType(), responseDecodeMessageConfig.align());
        R respObj;
        try {
            respObj = decoder.decode(responseBytes, responseClass, responseDecodeMessageConfig);
        } catch (Exception e) {
            throw new RuntimeException("报文解码异常: " + e.getMessage(), e);
        }
        log.info("⬅️ 解析完成，响应对象：{}", respObj);
        return respObj;
    }

    /**
     * 响应类未显式声明分隔符时，沿用请求报文格式处理银行/中心这类同协议响应。
     * <p>
     * 例如请求 BO 标注了 {@code useDelimiter=true}，响应 VO 仅使用默认 {@code @FixedMessage}，
     * 但实际响应仍然是 {@code 总包长|字段1|字段2|...|}。这时直接用响应 VO 注解会导致字段错位，
     * 所以这里会在确认响应 VO 包含总包长字段后沿用请求格式解析响应。
     *
     * @param requestMessageConfig  请求报文配置
     * @param responseMessageConfig 响应类自身声明的报文配置
     * @param responseClass         响应对象类型
     * @param headerLength          报头长度
     * @param <R>                   响应对象类型
     * @return 实际用于响应解码的报文配置
     */
    private <R> MessageConfig resolveResponseDecodeMessage(
        MessageConfig requestMessageConfig,
        MessageConfig responseMessageConfig,
        Class<R> responseClass,
        int headerLength
    ) {
        if (responseMessageConfig.useDelimiter()) {
            return responseMessageConfig;
        }
        if (requestMessageConfig.useDelimiter() && MessageCodecUtils.hasHeaderField(responseClass, headerLength)) {
            return requestMessageConfig;
        }
        return responseMessageConfig;
    }

    /**
     * 响应报头后存在分隔符时读取并消费；不存在时回退给报文体读取。
     * <p>
     * 使用 {@link PushbackInputStream} 是为了兼容不带报头分隔符的协议：如果多读的 1 个字节不是
     * 分隔符，就压回流中，后续按报文体第一个字节继续读取。
     *
     * @param in                    可回退的输入流
     * @param requestMessageConfig  请求报文配置
     * @param responseMessageConfig 响应解码配置
     * @return true 表示已消费报头后的分隔符
     * @throws IOException 读取失败或分隔符不匹配时抛出
     */
    private boolean readHeaderDelimiterIfPresent(
        PushbackInputStream in,
        MessageConfig requestMessageConfig,
        MessageConfig responseMessageConfig
    ) throws IOException {
        boolean required = requestMessageConfig.useDelimiter() || responseMessageConfig.useDelimiter();
        byte delimiter = (byte) (requestMessageConfig.useDelimiter()
            ? requestMessageConfig.delimiter()
            : responseMessageConfig.delimiter());

        int next = in.read();
        if (next == -1) {
            throw new IOException("连接被对端关闭，未读取到报文体");
        }
        log.debug("读取报头后首字节，实际值=0x{}，期望分隔符=0x{}，required={}",
            String.format("%02X", next), String.format("%02X", delimiter), required);
        if ((byte) next == delimiter) {
            log.debug("✅ 接收到报头分隔符：{}", (char) delimiter);
            return true;
        }
        if (required) {
            throw new IOException("报头分隔符不匹配，实际值：" + (char) next);
        }

        in.unread(next);
        return false;
    }

    /**
     * 现有响应 VO 第 0 域为总包长时，需要把报头作为字段拼回去再交给 MessageDecoder。
     * <p>
     * Socket 读取阶段必须先消费报头来计算长度；对象解码阶段又需要这个字段填充 VO 的
     * {@code totalLength}。因此这里会把已经读取过的报头和可选分隔符恢复成完整响应报文。
     *
     * @param responseClass         响应对象类型
     * @param responseMessageConfig 响应解码配置
     * @param header                已读取的报头字节
     * @param hasHeaderDelimiter    是否已读取到报头后的分隔符
     * @param body                  已读取的报文体字节
     * @param <R>                   响应对象类型
     * @return 交给解码器使用的响应字节
     * @throws IOException 当前实现不主动抛出，保留给调用链统一处理
     */
    private <R> byte[] buildResponseBytes(
        Class<R> responseClass,
        MessageConfig responseMessageConfig,
        byte[] header,
        boolean hasHeaderDelimiter,
        byte[] body
    ) throws IOException {
        if (!MessageCodecUtils.hasHeaderField(responseClass, header.length)) {
            return body;
        }

        int delimiterLength = responseMessageConfig.useDelimiter() && hasHeaderDelimiter ? 1 : 0;
        byte[] responseBytes = Arrays.copyOf(header, header.length + delimiterLength + body.length);
        int offset = header.length;
        if (delimiterLength > 0) {
            responseBytes[offset] = (byte) responseMessageConfig.delimiter();
            offset++;
        }
        System.arraycopy(body, 0, responseBytes, offset, body.length);
        return responseBytes;
    }

    /**
     * 按尾部补读计划继续读取不计入报头长度的后缀字段。
     *
     * @param in            Socket 输入流
     * @param body          已按报头声明长度读取的报文体
     * @param messageClass  响应报文实体类型
     * @param messageConfig 响应解码配置
     * @return 补读后的完整报文体
     * @throws IOException 读取失败或超过最大报文体限制时抛出
     */
    private byte[] readTrailingBody(InputStream in, byte[] body, Class<?> messageClass, MessageConfig messageConfig) throws IOException {
        TrailingReadPlan trailingPlan = decoder.resolveTrailingReadPlan(messageClass, messageConfig);
        if (trailingPlan.isEmpty()) {
            log.debug("响应无需尾部补读，messageClass={}", messageClass.getSimpleName());
            return body;
        }

        log.debug("响应尾部补读计划，messageClass={}，fixedBytesBeforeVariable={}，hasVariableField={}",
            messageClass.getSimpleName(), trailingPlan.fixedBytesBeforeVariable(), trailingPlan.hasVariableField());
        if (trailingPlan.fixedBytesBeforeVariable() > 0) {
            // 先把“不计包长的固定后缀”补齐，后面的长度字段才能被完整解析出来。
            byte[] fixedTailBytes = NettyIoUtils.readExact(in, trailingPlan.fixedBytesBeforeVariable());
            body = NettyIoUtils.appendBytes(body, fixedTailBytes);
            log.debug("✅ 已补读响应固定后缀：{} 字节，当前完整报文体长度：{} 字节，tailPreview={}，tailHex={}",
                fixedTailBytes.length, body.length, new String(fixedTailBytes, messageConfig.targetCharset().getCharset()),
                NettyIoUtils.bytesToHex(fixedTailBytes));
        }

        if (trailingPlan.hasVariableField()) {
            int variableBytes = decoder.resolveTrailingVariableBytesToRead(body, messageClass, messageConfig);
            log.debug("响应不定长尾部补读判断完成，messageClass={}，variableBytes={}", messageClass.getSimpleName(), variableBytes);
            if (variableBytes > 0) {
                // 第二步再根据长度字段补读最后一个不定长内容域。
                byte[] variableTailBytes = NettyIoUtils.readExact(in, variableBytes);
                body = NettyIoUtils.appendBytes(body, variableTailBytes);
                log.debug("✅ 已补读响应不定长尾部：{} 字节，当前完整报文体长度：{} 字节，tailPreview={}，tailHex={}",
                    variableTailBytes.length, body.length, new String(variableTailBytes, messageConfig.targetCharset().getCharset()),
                    NettyIoUtils.bytesToHex(variableTailBytes));
            }
        }

        if (body.length > config.getMaxBodyLength()) {
            throw new IOException("完整响应报文体长度超过限制：" + body.length);
        }
        return body;
    }

}
