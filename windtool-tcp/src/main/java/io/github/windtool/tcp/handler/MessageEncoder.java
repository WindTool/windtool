package io.github.windtool.tcp.handler;

import lombok.extern.slf4j.Slf4j;
import io.github.windtool.tcp.annotation.*;
import io.github.windtool.tcp.core.MessageConfig;
import io.github.windtool.tcp.model.EncodedBody;
import io.github.windtool.tcp.utils.MessageCodecUtils;
import io.github.windtool.tcp.utils.MessageFieldUtils;
import io.github.windtool.tcp.utils.MessageHeaderUtils;
import io.github.windtool.tcp.utils.MessageMd5Utils;
import io.github.windtool.tcp.utils.SpelUtils;
import io.github.windtool.tcp.utils.StringUtils;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.*;

/**
 * 报文编码器。
 * <p>
 * 支持定长字段、分隔符字段以及 {@link FixedField#length()} 小于 0 的不定长字段。
 * 报头长度按字段实际编码后的字节数计算，字段是否计入报头由
 * {@link FixedField#includeInHeaderLength()} 控制。
 * 字段顺序、MD5、循环体和 SpEL 能力分别由独立注解控制。
 *
 * @author AprilWind
 */
@Slf4j
public class MessageEncoder {

    /**
     * 日志中报文预览文本的最大字符数。
     */
    private static final int LOG_PREVIEW_LIMIT = 120;

    /**
     * 截断日志预览文本，避免大报文刷屏。
     */
    private static String previewText(String text) {
        if (text == null) {
            return "null";
        }
        if (text.length() <= LOG_PREVIEW_LIMIT) {
            return text;
        }
        return text.substring(0, LOG_PREVIEW_LIMIT) + "...(len=" + text.length() + ")";
    }

    /**
     * 使用指定运行期配置编码完整 TCP 报文。
     *
     * @param obj           报文实体对象
     * @param messageConfig 报文运行期配置
     * @return 包含报头和报文体的完整报文字节
     * @throws Exception 编码失败时抛出
     */
    public byte[] encode(Object obj, MessageConfig messageConfig) throws Exception {
        return encodeInternal(obj, messageConfig);
    }

    /**
     * 编码完整 TCP 报文的内部入口。
     *
     * @param obj           报文实体对象
     * @param messageConfig 报文运行期配置
     * @return 完整 TCP 报文字节
     * @throws Exception 编码失败时抛出
     */
    private byte[] encodeInternal(Object obj, MessageConfig messageConfig) throws Exception {
        log.debug("开始编码报文，对象类型={}，messageConfig={{headerLength={},headerIncludeSelf={},useDelimiter={},delimiter={},appendEndDelimiter={},targetCharset={},systemCharset={},padType={},align={}}}",
            obj.getClass().getSimpleName(), messageConfig.headerLength(), messageConfig.headerIncludeSelf(),
            messageConfig.useDelimiter(), messageConfig.delimiter(), messageConfig.appendEndDelimiter(), messageConfig.targetCharset(), messageConfig.systemCharset(),
            messageConfig.padType(), messageConfig.align());
        EncodedBody encodedBody = encodeBody(obj, messageConfig);
        log.debug("报文体编码完成，对象类型={}，bodyLength={}，declaredBodyLength={}",
            obj.getClass().getSimpleName(), encodedBody.body().length, encodedBody.declaredBodyLength());

        byte[] fullMessage = MessageHeaderUtils.wrapMessageWithHeader(encodedBody.body(), encodedBody.declaredBodyLength(), messageConfig);
        log.debug("报文编码完成，对象类型={}，fullLength={}，fullPreview={}",
            obj.getClass().getSimpleName(), fullMessage.length,
            previewText(new String(fullMessage, messageConfig.targetCharset().getCharset())));
        return fullMessage;
    }

    /**
     * 编码顶层报文体，并按配置处理报文体末尾分隔符。
     */
    private EncodedBody encodeBody(Object obj, MessageConfig messageConfig) throws Exception {
        EncodedBody encodedBody = encodeBodyInternal(obj, messageConfig, true);
        return removeEndDelimiterIfNeeded(encodedBody, messageConfig);
    }

    /**
     * 编码报文体字段。
     * <p>
     * 循环明细会递归进入本方法；顶层报文才会最终移除末尾分隔符。
     */
    private EncodedBody encodeBodyInternal(Object obj, MessageConfig messageConfig, boolean applyMd5) throws Exception {
        Charset targetCharset = messageConfig.targetCharset().getCharset();
        Charset systemCharset = messageConfig.systemCharset().getCharset();
        boolean delimiter = messageConfig.useDelimiter();
        List<Field> fields = MessageCodecUtils.sortedFields(obj.getClass());
        log.debug("开始编码报文体，对象类型={}，字段数={}，applyMd5={}，delimiter={}，targetCharset={}，systemCharset={}",
            obj.getClass().getSimpleName(), fields.size(), applyMd5, delimiter, targetCharset, systemCharset);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 这里缓存的是最终真正落到链路上的字段字节：包含补齐字符，必要时也包含字段分隔符。
        // MD5 计算必须基于这份字节流，才能与报文原文完全一致。
        Map<Integer, byte[]> fieldBytesByOrder = new LinkedHashMap<>();
        int declaredBodyLength = 0;
        boolean lastDelimiterCounted = false;

        for (Field field : fields) {
            field.setAccessible(true);
            FixedField ff = field.getAnnotation(FixedField.class);
            // order=0 的总包长字段只存在于对象视图中，真实报头在 wrapMessageWithHeader 里单独处理。
            if (MessageCodecUtils.isHeaderField(field, ff, messageConfig)) {
                continue;
            }
            Md5Field md5Field = field.getAnnotation(Md5Field.class);
            if (applyMd5 && md5Field != null && md5Field.mode().encode()) {
                // md5Verify 在 BO/VO 上只承担协议占位职责，编码阶段统一按协议范围自动回填，
                // 调用方不需要也不应该手工传入该字段。
                // 这里参与签名的始终是报文体字段字节，不包含 TCP 报头长度，例如 151|00|1001|...
                // 只会对 00|1001|... 这一段计算 MD5。
                MessageCodecUtils.Md5Range md5Range = MessageCodecUtils.md5Range(md5Field);
                byte[] md5SourceBytes = MessageCodecUtils.resolveMd5SourceBytes(fieldBytesByOrder, out.toByteArray(), md5Range);
                byte[] md5Bytes = MessageMd5Utils.digestBytes(md5SourceBytes, messageConfig.targetCharset().getCharset());
                byte[] md5FieldBytes = MessageCodecUtils.appendDelimiterIfNeeded(md5Bytes, messageConfig);
                out.write(md5FieldBytes);
                declaredBodyLength += countDeclaredLength(md5FieldBytes, ff);
                lastDelimiterCounted = messageConfig.useDelimiter() && ff.includeInHeaderLength();
                log.debug("生成MD5字段，实体={}，字段={}，order={}，range={}~{}，sourceLength={}，md5={}",
                    obj.getClass().getSimpleName(), field.getName(), MessageCodecUtils.resolveOrder(field), md5Range.startOrder(), md5Range.endOrder(),
                    md5SourceBytes.length, previewText(new String(md5Bytes, targetCharset)));
                continue;
            }

            Object valueObj = field.get(obj);
            if (MessageCodecUtils.isListField(field)) {
                // 循环明细逐条递归编码后直接拼接到主报文体，明细项不单独生成顶层 MD5 字段。
                ByteArrayOutputStream listOut = new ByteArrayOutputStream();
                int listDeclaredLength = 0;
                boolean listLastDelimiterCounted = false;
                if (valueObj instanceof List<?> list) {
                    for (Object item : list) {
                        EncodedBody itemBody = encodeBodyInternal(item, messageConfig, false);
                        listOut.write(itemBody.body());
                        listDeclaredLength += itemBody.declaredBodyLength();
                        listLastDelimiterCounted = itemBody.trailingDelimiterCounted();
                    }
                }
                byte[] listBytes = listOut.toByteArray();
                out.write(listBytes);
                if (ff.includeInHeaderLength()) {
                    declaredBodyLength += listDeclaredLength;
                }
                lastDelimiterCounted = ff.includeInHeaderLength() && listLastDelimiterCounted;
                fieldBytesByOrder.put(MessageCodecUtils.resolveOrder(field), listBytes);
            } else {
                // 普通字段会先完成补齐，再根据协议决定是否追加字段分隔符。
                byte[] fieldBytes = encodeField(field, valueObj, ff, messageConfig, targetCharset, systemCharset);
                byte[] bodyFieldBytes = MessageCodecUtils.appendDelimiterIfNeeded(fieldBytes, messageConfig);
                out.write(bodyFieldBytes);
                declaredBodyLength += countDeclaredLength(bodyFieldBytes, ff);
                lastDelimiterCounted = messageConfig.useDelimiter() && ff.includeInHeaderLength();
                fieldBytesByOrder.put(MessageCodecUtils.resolveOrder(field), bodyFieldBytes);
            }
        }

        log.debug("报文体编码结束，实体={}，最终bodyLength={}，declaredBodyLength={}，bodyPreview={}",
            obj.getClass().getSimpleName(), out.size(), declaredBodyLength,
            previewText(new String(out.toByteArray(), targetCharset)));
        return new EncodedBody(out.toByteArray(), declaredBodyLength, lastDelimiterCounted);
    }

    /**
     * 根据 {@link io.github.windtool.tcp.annotation.MessageDelimiter#appendEnd()} 移除报文体最后一个分隔符。
     */
    private EncodedBody removeEndDelimiterIfNeeded(EncodedBody encodedBody, MessageConfig messageConfig) {
        if (!messageConfig.useDelimiter() || messageConfig.appendEndDelimiter() || encodedBody.body().length == 0) {
            return encodedBody;
        }

        byte delimiter = (byte) messageConfig.delimiter();
        byte[] body = encodedBody.body();
        if (body[body.length - 1] != delimiter) {
            return encodedBody;
        }

        byte[] trimmedBody = Arrays.copyOf(body, body.length - 1);
        int declaredBodyLength = encodedBody.declaredBodyLength() - (encodedBody.trailingDelimiterCounted() ? 1 : 0);
        log.debug("按配置移除报文体末尾分隔符，delimiter={}，bodyLength={} -> {}，declaredBodyLength={} -> {}",
            messageConfig.delimiter(), body.length, trimmedBody.length,
            encodedBody.declaredBodyLength(), declaredBodyLength);
        return new EncodedBody(trimmedBody, declaredBodyLength, false);
    }

    /**
     * 统计单个字段写入报头声明长度的字节数。
     */
    private int countDeclaredLength(byte[] bodyFieldBytes, FixedField ff) {
        return ff.includeInHeaderLength() ? bodyFieldBytes.length : 0;
    }

    /**
     * 将单个字段值编码成符合定长定义的字节数组。
     */
    private byte[] encodeField(Field field, Object valueObj, FixedField ff, MessageConfig messageConfig, Charset targetCharset, Charset systemCharset) {
        byte[] bytes = toBytes(field, valueObj, ff, targetCharset, systemCharset);
        // length < 0 表示不定长字段，不补齐、不截断，直接使用实际编码字节。
        if (ff.length() < 0) {
            return bytes;
        }
        int originalLength = MessageFieldUtils.fieldLength(bytes, messageConfig);
        if (originalLength > ff.length()) {
            log.warn("字段顺序[{}]长度超长，定义长度={} {}，原始长度={}",
                MessageCodecUtils.resolveOrder(field), ff.length(), messageConfig.lengthType(), originalLength);
        }
        return MessageFieldUtils.encodeFixedField(bytes, ff, messageConfig);
    }

    /**
     * 将 Java 字段值转换为目标字符集字节。
     */
    private byte[] toBytes(Field field, Object valueObj, FixedField ff, Charset targetCharset, Charset systemCharset) {
        String encodeSpel = resolveEncodeSpel(field, ff);
        if (StringUtils.isNotEmpty(encodeSpel)) {
            valueObj = SpelUtils.eval(valueObj, encodeSpel);
        }
        if (valueObj == null) {
            return new byte[0];
        }
        if (valueObj instanceof byte[] b) {
            return b;
        }
        if (valueObj instanceof Number n) {
            return String.valueOf(n).getBytes(targetCharset);
        }

        byte[] bytes = valueObj.toString().getBytes(systemCharset);
        if (systemCharset.equals(targetCharset)) {
            return bytes;
        }
        return new String(bytes, systemCharset).getBytes(targetCharset);
    }

    /**
     * 解析字段编码前处理表达式。
     */
    private String resolveEncodeSpel(Field field, FixedField fixedField) {
        FieldSpel fieldSpel = field.getAnnotation(FieldSpel.class);
        return fieldSpel == null ? "" : fieldSpel.encode();
    }
}
