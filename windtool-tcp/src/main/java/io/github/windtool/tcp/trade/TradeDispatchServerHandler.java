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

    private final Map<String, TradeHandler<?, ?>> tradeHandlerMap;
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
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Class<Object> requestClass() {
        return Object.class;
    }

    @Override
    public MessageConfig frameMessage() {
        return options.frameMessage();
    }

    @Override
    public boolean supports(byte[] body, MessageConfig messageConfig) {
        String tradeCode = resolveTradeCode(body, messageConfig);
        boolean supported = tradeHandlerMap.containsKey(tradeCode);
        log.debug("交易分发支持判断，tradeCode={}，supported={}，registeredCodes={}",
            tradeCode, supported, tradeHandlerMap.keySet());
        return supported;
    }

    @Override
    public Class<?> requestClass(byte[] body, MessageConfig messageConfig) {
        String tradeCode = resolveTradeCode(body, messageConfig);
        Class<?> requestClass = getTradeHandler(tradeCode).requestClass();
        log.info("交易分发命中，tradeCode={}，requestClass={}", tradeCode, requestClass.getSimpleName());
        return requestClass;
    }

    @Override
    public Object handle(Object request) {
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
        return tradeHandler.handle(request);
    }

    private String resolveTradeCode(byte[] body, MessageConfig messageConfig) {
        String tradeCode = extractTradeCode(body, messageConfig);
        if (tradeHandlerMap.containsKey(tradeCode) || !messageConfig.useDelimiter() || !options.scanRegisteredTradeCodes()) {
            return tradeCode;
        }

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
            String message = new String(body, charset);
            String[] fields = message.split(Pattern.quote(String.valueOf(messageConfig.delimiter())), -1);
            int index = options.delimiterTradeCodeField() - 1;
            if (fields.length <= index) {
                throw new IllegalStateException("报文交易码不存在");
            }
            return fields[index].trim();
        }

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
