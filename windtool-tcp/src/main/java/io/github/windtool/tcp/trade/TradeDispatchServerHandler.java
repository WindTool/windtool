package io.github.windtool.tcp.trade;

import io.github.windtool.tcp.annotation.FieldOrder;
import io.github.windtool.tcp.annotation.FixedField;
import io.github.windtool.tcp.core.MessageConfig;
import io.github.windtool.tcp.handler.NettyServerHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;

import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 交易码分发服务端处理器。
 * <p>
 * 该处理器面向“同一 TCP 端口承载多种交易码”的服务端场景：先按统一帧配置读取完整报文体，
 * 再从原始报文体中提取交易码，最后将请求交给对应的 {@link TradeHandler}。
 *
 * @author AprilWind
 */
@Slf4j
public class TradeDispatchServerHandler implements NettyServerHandler<Object>, Ordered {

    /**
     * 启动阶段建立交易码到业务处理器的映射，运行期只做 O(1) 查找。
     */
    private final Map<String, TradeHandler<?, ?>> tradeHandlerMap;

    /**
     * 分发规则配置，决定交易码从原始报文或请求对象的哪个位置读取。
     */
    private final TradeDispatchOptions options;

    /**
     * 使用默认分发配置创建交易码分发处理器。
     *
     * @param tradeHandlers 业务模块注册的交易处理器列表
     */
    public TradeDispatchServerHandler(List<TradeHandler<?, ?>> tradeHandlers) {
        this(tradeHandlers, TradeDispatchOptions.defaults());
    }

    /**
     * 创建交易码分发处理器。
     *
     * @param tradeHandlers 业务模块注册的交易处理器列表
     * @param options       交易分发配置
     */
    public TradeDispatchServerHandler(List<TradeHandler<?, ?>> tradeHandlers, TradeDispatchOptions options) {
        this.tradeHandlerMap = new LinkedHashMap<>();
        for (TradeHandler<?, ?> tradeHandler : tradeHandlers) {
            // 交易码必须唯一，否则同一笔报文无法确定应该交给哪个业务处理器。
            TradeHandler<?, ?> old = tradeHandlerMap.put(tradeHandler.tradeCode(), tradeHandler);
            if (old != null) {
                throw new IllegalStateException("交易码重复注册：" + tradeHandler.tradeCode());
            }
        }
        this.options = options;
        log.info("交易分发处理器初始化完成，交易码：{}", tradeHandlerMap.keySet());
    }

    @Override
    public int getOrder() {
        // 聚合分发器应该优先于普通 NettyServerHandler 尝试匹配报文。
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Class<Object> requestClass() {
        // 分发器本身不对应具体请求类型，实际类型会在读取 body 后按交易码决定。
        return Object.class;
    }

    @Override
    public MessageConfig frameMessage() {
        // 读取 TCP 帧时还不知道具体交易类型，所以使用统一帧配置。
        return options.frameMessage();
    }

    @Override
    public boolean supports(byte[] body, MessageConfig messageConfig) {
        // supports 只做轻量路由判断，不解码对象、不执行业务。
        String tradeCode = resolveTradeCode(body, messageConfig);
        boolean supported = tradeHandlerMap.containsKey(tradeCode);
        log.debug("交易分发支持判断，tradeCode={}，supported={}，registeredCodes={}",
            tradeCode, supported, tradeHandlerMap.keySet());
        return supported;
    }

    @Override
    public Class<?> requestClass(byte[] body, MessageConfig messageConfig) {
        // NettyServer 会根据这里返回的类型，把同一个 TCP 端口上的不同交易解码成不同 BO。
        String tradeCode = resolveTradeCode(body, messageConfig);
        Class<?> requestClass = getTradeHandler(tradeCode).requestClass();
        log.info("交易分发命中，tradeCode={}，requestClass={}", tradeCode, requestClass.getSimpleName());
        return requestClass;
    }

    @Override
    public Object handle(Object request) {
        // 请求对象已经完成解码；此处只负责二次按交易码定位 handler 并转调业务逻辑。
        String tradeCode = extractTradeCode(request);
        TradeHandler<?, ?> tradeHandler = getTradeHandler(tradeCode);
        log.info("交易分发开始，交易码：{}，请求类型：{}，tradeHandler={}",
            tradeCode, request.getClass().getSimpleName(), tradeHandler.getClass().getSimpleName());
        Object response = invokeTradeHandler(tradeHandler, request);
        log.info("交易分发结束，交易码：{}，响应类型：{}", tradeCode, response == null ? "null" : response.getClass().getSimpleName());
        return response;
    }

    @Override
    public MessageConfig responseMessage(Object response, MessageConfig requestMessageConfig) {
        // 交易分发场景下，响应通常沿用请求的报头、分隔符、字符集和 MD5 规则。
        return requestMessageConfig;
    }

    private TradeHandler<?, ?> getTradeHandler(String tradeCode) {
        TradeHandler<?, ?> tradeHandler = tradeHandlerMap.get(tradeCode);
        if (tradeHandler == null) {
            throw new IllegalStateException("未注册交易码处理器：" + tradeCode + "，已注册交易码：" + tradeHandlerMap.keySet());
        }
        return tradeHandler;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object invokeTradeHandler(TradeHandler tradeHandler, Object request) {
        // 泛型类型由 requestClass() 和 handler 注册关系保证，运行期统一通过 Object 转调。
        return tradeHandler.handle(request);
    }

    private String resolveTradeCode(byte[] body, MessageConfig messageConfig) {
        String tradeCode = extractTradeCode(body, messageConfig);
        if (tradeHandlerMap.containsKey(tradeCode) || !messageConfig.useDelimiter() || !options.scanRegisteredTradeCodes()) {
            return tradeCode;
        }

        // 宽容处理：某些联调报文可能把交易码放错字段位置，扫描已注册交易码可以给出更友好的分发结果。
        String message = new String(body, messageConfig.targetCharset().getCharset());
        String[] fields = message.split(Pattern.quote(String.valueOf(messageConfig.delimiter())), -1);
        for (String field : fields) {
            String candidate = field.trim();
            if (tradeHandlerMap.containsKey(candidate)) {
                log.warn("交易码未出现在标准字段位置，标准解析值：{}，扫描命中：{}", tradeCode, candidate);
                return candidate;
            }
        }
        return tradeCode;
    }

    private String extractTradeCode(byte[] body, MessageConfig messageConfig) {
        Charset charset = messageConfig.targetCharset().getCharset();
        if (messageConfig.useDelimiter()) {
            // 分隔符协议按字段位置取交易码，默认第 2 个字段。
            String message = new String(body, charset);
            String[] fields = message.split(Pattern.quote(String.valueOf(messageConfig.delimiter())), -1);
            int index = options.delimiterTradeCodeField() - 1;
            if (fields.length <= index) {
                throw new IllegalStateException("报文交易码不存在");
            }
            return fields[index].trim();
        }

        // 无分隔符协议按字节偏移取交易码，默认跳过 2 字节区位代码后读取 4 字节。
        int start = options.fixedTradeCodeOffset();
        int end = start + options.fixedTradeCodeLength();
        if (body.length < end) {
            throw new IllegalStateException("报文长度不足，无法解析交易码");
        }
        return new String(body, start, options.fixedTradeCodeLength(), charset).trim();
    }

    private String extractTradeCode(Object request) {
        for (Field field : request.getClass().getDeclaredFields()) {
            FixedField fixedField = field.getAnnotation(FixedField.class);
            FieldOrder fieldOrder = field.getAnnotation(FieldOrder.class);
            if (fixedField != null && fieldOrder != null && fieldOrder.value() == options.requestTradeCodeFieldOrder()) {
                try {
                    // 已解码请求对象使用字段顺序定位交易码，避免强制要求字段名必须叫 tradeCode。
                    field.setAccessible(true);
                    Object value = field.get(request);
                    if (value == null) {
                        throw new IllegalStateException("交易码字段为空：" + field.getName());
                    }
                    return value.toString().trim();
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("读取交易码字段失败：" + field.getName(), e);
                }
            }
        }
        throw new IllegalStateException("请求对象未定义 order=" + options.requestTradeCodeFieldOrder() + " 的交易码字段：" + request.getClass().getName());
    }

}
