package io.github.windtool.tcp.handler;

import lombok.extern.slf4j.Slf4j;
import io.github.windtool.tcp.annotation.FieldSpel;
import io.github.windtool.tcp.annotation.FixedField;
import io.github.windtool.tcp.core.MessageConfig;
import io.github.windtool.tcp.annotation.Md5Field;
import io.github.windtool.tcp.enums.Align;
import io.github.windtool.tcp.model.TrailingReadPlan;
import io.github.windtool.tcp.utils.AmountUtil;
import io.github.windtool.tcp.utils.MessageCodecUtils;
import io.github.windtool.tcp.utils.MessageFieldUtils;
import io.github.windtool.tcp.utils.MessageMd5Utils;
import io.github.windtool.tcp.utils.SpelUtils;
import io.github.windtool.tcp.utils.StringUtils;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报文解码器。
 * <p>
 * 支持三类字段边界：定长字段按配置的字节/字符单位截取，分隔符报文按分隔符切域，
 * {@link FixedField#length()} 小于 0 的不定长字段按分隔符或剩余报文字节确定边界。
 * 字段顺序、循环体和 SpEL 能力分别由独立注解控制。
 *
 * @author AprilWind
 */
@Slf4j
public class MessageDecoder {

    /**
     * 日志中报文预览文本的最大字符数。
     */
    private static final int LOG_PREVIEW_LIMIT = 120;

    /**
     * 使用指定运行期配置解码报文体。
     *
     * @param body          已读取的报文体字节
     * @param clazz         目标报文类型
     * @param messageConfig 报文运行期配置
     * @param <T>           目标报文类型
     * @return 解码后的报文对象
     * @throws Exception 解码失败时抛出
     */
    public <T> T decode(byte[] body, Class<T> clazz, MessageConfig messageConfig) throws Exception {
        return decodeBody(body, clazz, messageConfig);
    }

    /**
     * 解析不计入报头长度的尾部结构。
     * <p>
     * 约束：
     * 1. {@code includeInHeaderLength=false} 的字段必须出现在连续后缀中；
     * 2. 尾部最多允许一个不定长字段，且必须位于最后一个字段；
     * 3. 如果存在不定长字段，则它前一个字段必须是长度字段。
     */
    public TrailingReadPlan resolveTrailingReadPlan(Class<?> clazz, MessageConfig messageConfig) {
        List<Field> fields = MessageCodecUtils.sortedFields(clazz);
        int suffixStartIndex = -1;
        for (int i = 0; i < fields.size(); i++) {
            FixedField ff = fields.get(i).getAnnotation(FixedField.class);
            if (!ff.includeInHeaderLength()) {
                suffixStartIndex = i;
                break;
            }
        }
        if (suffixStartIndex < 0) {
            return TrailingReadPlan.none();
        }
        if (MessageFieldUtils.isCharacterLength(messageConfig)) {
            throw new IllegalStateException("字符长度模式暂不支持 includeInHeaderLength=false 的尾部补读，实体："
                + clazz.getName());
        }

        // 这里统计的是“在拿到尾部长度字段之前，链路层至少还要补读多少字节”。
        // 分隔符同样占用链路字节，所以需要一起计入。
        int fixedBytesBeforeVariable = 0;
        for (int i = suffixStartIndex; i < fields.size(); i++) {
            Field field = fields.get(i);
            FixedField ff = field.getAnnotation(FixedField.class);
            if (ff.includeInHeaderLength()) {
                throw new IllegalStateException("includeInHeaderLength=false 字段后不能再出现 true 字段："
                    + clazz.getName() + "." + field.getName());
            }
            if (MessageCodecUtils.isListField(field)) {
                throw new IllegalStateException("尾部补读暂不支持循环明细字段："
                    + clazz.getName() + "." + field.getName());
            }
            if (ff.length() < 0) {
                if (i != fields.size() - 1) {
                    throw new IllegalStateException("不定长字段必须位于报文最后一个字段："
                        + clazz.getName() + "." + field.getName());
                }
                return new TrailingReadPlan(suffixStartIndex, fixedBytesBeforeVariable, i);
            }
            fixedBytesBeforeVariable += ff.length();
            if (messageConfig.useDelimiter() && (i < fields.size() - 1 || messageConfig.appendEndDelimiter())) {
                fixedBytesBeforeVariable += 1;
            }
        }
        return new TrailingReadPlan(suffixStartIndex, fixedBytesBeforeVariable, -1);
    }

    /**
     * 在补读完固定长度后缀后，继续解析最后一个不定长字段需要补读的字节数。
     */
    public int resolveTrailingVariableBytesToRead(byte[] body, Class<?> clazz, MessageConfig messageConfig) {
        TrailingReadPlan plan = resolveTrailingReadPlan(clazz, messageConfig);
        if (!plan.hasVariableField()) {
            return 0;
        }

        List<Field> fields = MessageCodecUtils.sortedFields(clazz);
        int lengthFieldIndex = plan.variableFieldIndex() - 1;
        if (lengthFieldIndex < 0) {
            throw new IllegalStateException("不定长字段前缺少长度字段：" + clazz.getName());
        }

        Field lengthField = fields.get(lengthFieldIndex);
        FixedField lengthFieldAnno = lengthField.getAnnotation(FixedField.class);
        if (lengthFieldAnno.length() < 0) {
            throw new IllegalStateException("不定长字段前的长度字段不能也是不定长字段："
                + clazz.getName() + "." + lengthField.getName());
        }

        String lengthText = resolveFieldText(body, fields, lengthFieldIndex, messageConfig, lengthFieldAnno);
        if (StringUtils.isBlank(lengthText)) {
            throw new IllegalStateException("尾部长度字段为空，实体=" + clazz.getSimpleName()
                + "，长度字段=" + lengthField.getName());
        }

        try {
            int variableLength = Integer.parseInt(lengthText.trim());
            boolean readEndDelimiter = messageConfig.useDelimiter() && messageConfig.appendEndDelimiter();
            int trailingBytes = variableLength + (readEndDelimiter ? 1 : 0);
            log.debug("解析不定长尾部成功，实体={}，长度字段={}，长度文本={}，尾部字节数={}，是否补读末尾分隔符={}，最终补读字节数={}",
                clazz.getSimpleName(), lengthField.getName(), lengthText, variableLength, readEndDelimiter, trailingBytes);
            return trailingBytes;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("解析尾部长度失败，实体=" + clazz.getSimpleName()
                + "，字段=" + lengthField.getName()
                + "，值=" + lengthText, e);
        }
    }

    /**
     * 根据配置选择分隔符解码或定长游标解码。
     */
    private <T> T decodeBody(byte[] body, Class<T> clazz, MessageConfig messageConfig) throws Exception {
        List<Field> fields = MessageCodecUtils.sortedFields(clazz);
        log.debug("开始解码报文体，目标类型={}，bodyLength={}，字段数={}，useDelimiter={}，bodyPreview={}",
            clazz.getSimpleName(), body.length, fields.size(), messageConfig.useDelimiter(),
            previewText(new String(body, messageConfig.targetCharset().getCharset())));
        if (messageConfig.useDelimiter() && fields.stream().noneMatch(MessageCodecUtils::isListField)) {
            return decodeDelimitedBody(body, clazz, messageConfig, fields);
        }
        return decodeFixedBody(body, clazz, messageConfig, fields);
    }

    /**
     * 按字段分隔符切分报文体并填充对象。
     */
    private <T> T decodeDelimitedBody(byte[] body, Class<T> clazz, MessageConfig messageConfig, List<Field> fields) throws Exception {
        Charset targetCharset = messageConfig.targetCharset().getCharset();
        Charset systemCharset = messageConfig.systemCharset().getCharset();
        T obj = clazz.getDeclaredConstructor().newInstance();
        List<byte[]> segments = MessageCodecUtils.splitByDelimiter(body, (byte) messageConfig.delimiter());
        log.debug("按分隔符解码报文体，目标类型={}，segments={}，fields={}，delimiter={}，bodyPreview={}",
            clazz.getSimpleName(), segments.size(), fields.size(), messageConfig.delimiter(),
            previewText(new String(body, targetCharset)));

        if (segments.size() < fields.size()) {
            throw new IllegalStateException("报文字段数量不足，期望：" + fields.size() + "，实际：" + segments.size());
        }
        if (segments.size() > fields.size()) {
            log.warn("报文字段数量超过实体定义，实体：{}，期望：{}，实际：{}，多余字段将被忽略",
                clazz.getSimpleName(), fields.size(), segments.size());
        }

        // 与编码端保持一致，MD5 校验基于“切域后仍保持原样的字段链路字节”进行。
        Map<Integer, byte[]> encodedBytesByOrder = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            field.setAccessible(true);
            FixedField ff = field.getAnnotation(FixedField.class);
            Md5Field md5Field = field.getAnnotation(Md5Field.class);
            byte[] fieldBytes = segments.get(i);
            if (ff.length() >= 0 && MessageFieldUtils.fieldLength(fieldBytes, messageConfig) != ff.length()) {
                byte[] valueBytes = MessageFieldUtils.trimPadBytes(fieldBytes, ff.padType().getValue(), true);
                String valueText = new String(valueBytes, targetCharset);
                log.warn("字段[{}]长度不符合定义，字段顺序：{}，定义长度：{} {}，原始域长度：{} 字节/{} 字符，去补齐后内容长度：{} 字节/{} 字符",
                    field.getName(), MessageCodecUtils.resolveOrder(field), ff.length(), messageConfig.lengthType(),
                    fieldBytes.length, new String(fieldBytes, targetCharset).length(),
                    valueText.getBytes(targetCharset).length, valueText.length());
            }

            if (md5Field != null && md5Field.mode().decode()) {
                // 必须先校验再做 trim / 类型转换，避免处理后的字段值与真实报文原文脱钩。
                verifyMd5(field, fieldBytes, encodedBytesByOrder, md5Field, messageConfig);
            }

            Object value = decodeField(fieldBytes, ff, targetCharset, systemCharset, field.getType(), true);
            String decodeSpel = resolveDecodeSpel(field);
            if (StringUtils.isNotEmpty(decodeSpel)) {
                value = SpelUtils.eval(value, decodeSpel);
            }
            field.set(obj, value);
            encodedBytesByOrder.put(MessageCodecUtils.resolveOrder(field), MessageCodecUtils.appendDelimiterIfNeeded(fieldBytes, messageConfig));
        }
        return obj;
    }

    /**
     * 按固定长度游标截取字段并填充对象。
     */
    private <T> T decodeFixedBody(byte[] body, Class<T> clazz, MessageConfig messageConfig, List<Field> fields) throws Exception {
        Charset targetCharset = messageConfig.targetCharset().getCharset();
        Charset systemCharset = messageConfig.systemCharset().getCharset();
        T obj = clazz.getDeclaredConstructor().newInstance();
        int pos = 0;
        // 记录已经消费的原始报文字节，供后续 MD5 还原使用。
        Map<Integer, byte[]> encodedBytesByOrder = new LinkedHashMap<>();
        log.debug("按固定长度解码报文体，目标类型={}，bodyLength={}，字段数={}，useDelimiter={}，bodyPreview={}",
            clazz.getSimpleName(), body.length, fields.size(), messageConfig.useDelimiter(),
            previewText(new String(body, targetCharset)));
        for (int fieldIndex = 0; fieldIndex < fields.size(); fieldIndex++) {
            Field field = fields.get(fieldIndex);
            field.setAccessible(true);
            FixedField ff = field.getAnnotation(FixedField.class);
            Md5Field md5Field = field.getAnnotation(Md5Field.class);

            if (MessageCodecUtils.isListField(field)) {
                Class<?> itemClass = (Class<?>) ((java.lang.reflect.ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
                int itemLength = calcEntryLength(itemClass, messageConfig);
                int endItemLength = messageConfig.useDelimiter() && !messageConfig.appendEndDelimiter()
                    ? itemLength - 1
                    : itemLength;
                List<Object> list = new ArrayList<>();
                ByteArrayOutputStream listBytes = new ByteArrayOutputStream();
                while (pos + itemLength <= body.length) {
                    byte[] itemBytes = Arrays.copyOfRange(body, pos, pos + itemLength);
                    Object item = decodeBody(itemBytes, itemClass, messageConfig);
                    list.add(item);
                    listBytes.write(itemBytes, 0, itemBytes.length);
                    pos += itemLength;
                }
                if (endItemLength > 0 && pos < body.length && pos + endItemLength == body.length) {
                    byte[] itemBytes = Arrays.copyOfRange(body, pos, pos + endItemLength);
                    Object item = decodeBody(itemBytes, itemClass, messageConfig);
                    list.add(item);
                    listBytes.write(itemBytes, 0, itemBytes.length);
                    pos += endItemLength;
                }
                field.set(obj, list);
                encodedBytesByOrder.put(MessageCodecUtils.resolveOrder(field), listBytes.toByteArray());
            } else {
                int len = ff.length();
                byte[] fieldBytes;
                boolean delimiterConsumed = false;
                if (len < 0) {
                    if (messageConfig.useDelimiter()) {
                        int delimiterPos = MessageCodecUtils.indexOf(body, (byte) messageConfig.delimiter(), pos);
                        int end = delimiterPos >= 0 ? delimiterPos : body.length;
                        fieldBytes = Arrays.copyOfRange(body, pos, end);
                        pos = delimiterPos >= 0 ? delimiterPos + 1 : body.length;
                        delimiterConsumed = delimiterPos >= 0;
                    } else if (fieldIndex == fields.size() - 1) {
                        fieldBytes = Arrays.copyOfRange(body, pos, body.length);
                        pos = body.length;
                    } else {
                        throw new IllegalStateException("不定长字段只能作为无分隔符报文的最后一个字段："
                            + field.getName() + "，字段顺序：" + MessageCodecUtils.resolveOrder(field));
                    }
                } else {
                    MessageFieldUtils.FieldSlice fieldSlice = MessageFieldUtils.readFixedFieldBytes(
                        body, pos, len, field.getName(), MessageCodecUtils.resolveOrder(field), messageConfig);
                    fieldBytes = fieldSlice.bytes();
                    pos = fieldSlice.nextPos();
                }

                if (md5Field != null && md5Field.mode().decode()) {
                    // 固定长度协议同样在字段解码前完成 MD5 比对。
                    verifyMd5(field, fieldBytes, encodedBytesByOrder, md5Field, messageConfig);
                }

                Object value = decodeField(fieldBytes, ff, targetCharset, systemCharset, field.getType());
                String decodeSpel = resolveDecodeSpel(field);
                if (StringUtils.isNotEmpty(decodeSpel)) {
                    value = SpelUtils.eval(value, decodeSpel);
                }

                field.set(obj, value);

                if (messageConfig.useDelimiter() && !delimiterConsumed && pos < body.length) {
                    // “定长字段 + 分隔符”协议里，字段内容截取完后还要额外消费 1 个分隔符字节。
                    byte delimiter = (byte) messageConfig.delimiter();
                    if (body[pos] != delimiter) {
                        throw new IllegalStateException("字段分隔符不匹配，字段：" + field.getName()
                            + "，字段顺序：" + MessageCodecUtils.resolveOrder(field)
                            + "，字段长度：" + len
                            + "，期望分隔符位置：" + pos
                            + "，实际字节：0x" + String.format("%02X", body[pos])
                            + "，报文体长度：" + body.length);
                    }
                    pos++;
                }
                byte[] sourceBytes = delimiterConsumed
                    ? MessageCodecUtils.appendDelimiterIfNeeded(fieldBytes, messageConfig)
                    : fieldBytes;
                encodedBytesByOrder.put(MessageCodecUtils.resolveOrder(field), sourceBytes);
            }
        }
        log.debug("固定长度报文解码结束，目标类型={}，最终pos={}，bodyLength={}", clazz.getSimpleName(), pos, body.length);
        return obj;
    }

    /**
     * 解码字段字节，并按字段补齐方向去掉补齐字符。
     */
    private Object decodeField(byte[] bytes, FixedField ff, Charset targetCharset, Charset systemCharset, Class<?> fieldType) {
        return decodeField(bytes, ff, targetCharset, systemCharset, fieldType, false);
    }

    /**
     * 解码字段字节。
     * <p>
     * 分隔符报文一般会从两侧去掉补齐字符，定长游标报文按字段对齐方式去掉补齐字符。
     */
    private Object decodeField(
        byte[] bytes,
        FixedField ff,
        Charset targetCharset,
        Charset systemCharset,
        Class<?> fieldType,
        boolean trimBothSides
    ) {
        int start = 0, end = bytes.length;
        byte padByte = ff.padType().getValue();
        Align align = ff.align();
        if (trimBothSides) {
            // 分隔符报文切域后已经失去左右边界信息，补齐字符统一从两端裁掉。
            byte[] fieldBytes = MessageFieldUtils.trimPadBytes(bytes, padByte, true);
            start = 0;
            end = fieldBytes.length;
            bytes = fieldBytes;
        } else if (align == Align.RIGHT) {
            while (start < end && bytes[start] == padByte) {
                start++;
            }
        } else {
            while (end > start && bytes[end - 1] == padByte) {
                end--;
            }
        }
        byte[] fieldBytes = Arrays.copyOfRange(bytes, start, end);

        if (fieldType.equals(byte[].class)) {
            return fieldBytes;
        }

        String strValue = new String(fieldBytes, targetCharset);
        if (Number.class.isAssignableFrom(fieldType) || fieldType.isPrimitive()) {
            try {
                if (fieldType.equals(int.class) || fieldType.equals(Integer.class)) {
                    return Integer.parseInt(strValue);
                }
                if (fieldType.equals(long.class) || fieldType.equals(Long.class)) {
                    return Long.parseLong(strValue);
                }
                if (fieldType.equals(double.class) || fieldType.equals(Double.class)) {
                    return Double.parseDouble(strValue);
                }
                if (fieldType.equals(float.class) || fieldType.equals(Float.class)) {
                    return Float.parseFloat(strValue);
                }
                if (fieldType.equals(short.class) || fieldType.equals(Short.class)) {
                    return Short.parseShort(strValue);
                }
                if (fieldType.equals(byte.class) || fieldType.equals(Byte.class)) {
                    return Byte.parseByte(strValue);
                }
                if (fieldType.equals(java.math.BigDecimal.class)) {
                    return AmountUtil.parseToBigDecimal(strValue);
                }
                if (fieldType.equals(java.math.BigInteger.class)) {
                    return new java.math.BigInteger(strValue.trim());
                }
            } catch (NumberFormatException e) {
                log.warn("字段解析数字失败，返回原字符串: {}", strValue);
                return strValue;
            }
        }
        return strValue;
    }

    /**
     * 计算循环明细单条记录的字节长度。
     */
    private int calcEntryLength(Class<?> clazz, MessageConfig messageConfig) {
        return MessageCodecUtils.sortedFields(clazz).stream()
            .mapToInt(f -> {
                FixedField fixedField = f.getAnnotation(FixedField.class);
                if (MessageCodecUtils.isHeaderField(f, fixedField, messageConfig)) {
                    return 0;
                }
                if (fixedField.length() < 0) {
                    throw new IllegalStateException("循环明细不支持不定长字段，实体："
                        + clazz.getName() + "，字段：" + f.getName() + "，字段顺序：" + MessageCodecUtils.resolveOrder(f));
                }
                if (MessageFieldUtils.isCharacterLength(messageConfig)) {
                    throw new IllegalStateException("字符长度模式下循环明细无法按固定字节数预切分，实体："
                        + clazz.getName() + "，字段：" + f.getName() + "，字段顺序：" + MessageCodecUtils.resolveOrder(f));
                }
                return fixedField.length() + (messageConfig.useDelimiter() ? 1 : 0);
            })
            .sum();
    }

    /**
     * 解析字段解码后处理表达式。
     */
    private String resolveDecodeSpel(Field field) {
        FieldSpel fieldSpel = field.getAnnotation(FieldSpel.class);
        return fieldSpel == null ? "" : fieldSpel.decode();
    }

    /**
     * 校验报文中的 MD5 字段。
     */
    private void verifyMd5(Field field, byte[] actualFieldBytes, Map<Integer, byte[]> priorBytesByOrder, Md5Field md5Field, MessageConfig messageConfig) {
        // 解码阶段直接按协议范围重算并比对，占位字段本身不依赖上层额外传参。
        // 参与重算的也是报文体字段原文，不包含 TCP 报头长度字段。
        byte[] md5SourceBytes = MessageCodecUtils.resolveMd5SourceBytes(priorBytesByOrder, md5Field);
        String expected = MessageMd5Utils.digest(md5SourceBytes);
        String actual = new String(MessageFieldUtils.trimPadBytes(actualFieldBytes, (byte) ' ', true), messageConfig.targetCharset().getCharset()).trim();
        if (!MessageMd5Utils.matches(md5SourceBytes, actual)) {
            throw new IllegalStateException("MD5校验失败，字段=" + field.getName() + "，order=" + MessageCodecUtils.resolveOrder(field)
                + "，expected=" + expected + "，actual=" + actual);
        }
    }

    /**
     * 从已读取的报文体中解析指定字段文本。
     * <p>
     * 主要用于根据前置长度字段判断尾部变长内容还需要补读多少字节。
     */
    private String resolveFieldText(byte[] body, List<Field> fields, int targetIndex, MessageConfig messageConfig, FixedField targetFieldAnno) {
        Charset targetCharset = messageConfig.targetCharset().getCharset();
        if (messageConfig.useDelimiter()) {
            List<byte[]> segments = MessageCodecUtils.splitByDelimiter(body, (byte) messageConfig.delimiter());
            if (segments.size() <= targetIndex) {
                return "";
            }
            byte[] fieldBytes = segments.get(targetIndex);
            return new String(MessageFieldUtils.trimPadBytes(fieldBytes, targetFieldAnno.padType().getValue(), true), targetCharset);
        }

        int pos = 0;
        for (int i = 0; i < targetIndex; i++) {
            FixedField ff = fields.get(i).getAnnotation(FixedField.class);
            if (ff.length() < 0) {
                // 无分隔符场景下，只要目标字段前面出现过不定长字段，就无法再推导出后续字段位置。
                return "";
            }
            Field field = fields.get(i);
            pos = MessageFieldUtils.readFixedFieldBytes(
                body, pos, ff.length(), field.getName(), MessageCodecUtils.resolveOrder(field), messageConfig).nextPos();
        }
        if (!MessageFieldUtils.isCharacterLength(messageConfig) && body.length < pos + targetFieldAnno.length()) {
            return "";
        }
        Field targetField = fields.get(targetIndex);
        byte[] fieldBytes = MessageFieldUtils.readFixedFieldBytes(
            body, pos, targetFieldAnno.length(), targetField.getName(), MessageCodecUtils.resolveOrder(targetField), messageConfig).bytes();
        return new String(MessageFieldUtils.trimPadBytes(fieldBytes, targetFieldAnno.padType().getValue(), true), targetCharset);
    }

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

}
