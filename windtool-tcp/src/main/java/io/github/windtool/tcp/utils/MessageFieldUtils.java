package io.github.windtool.tcp.utils;

import io.github.windtool.tcp.annotation.FixedField;
import io.github.windtool.tcp.core.MessageConfig;
import io.github.windtool.tcp.enums.Align;
import io.github.windtool.tcp.enums.LengthType;

import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * 报文字段字节/字符长度处理工具。
 * <p>
 * 该工具只处理字段内容的长度单位、补齐、截取和 trim。网络读写仍然以字节为单位，
 * 报头声明长度也使用最终落链路的实际字节数。
 *
 * @author AprilWind
 */
public final class MessageFieldUtils {

    /**
     * 工具类禁止实例化。
     */
    private MessageFieldUtils() {
    }

    /**
     * 当前报文字段长度是否按字符解释。
     *
     * @param messageConfig 报文运行期配置
     * @return true 表示 {@link FixedField#length()} 按字符数解释
     */
    public static boolean isCharacterLength(MessageConfig messageConfig) {
        return messageConfig.lengthType() == LengthType.CHARACTERS;
    }

    /**
     * 计算字段在当前长度单位下的长度。
     * <p>
     * 字符模式使用目标链路字符集先还原字符串，再按 Java 字符数计算，避免中文等多字节字符被当作多个字节长度。
     *
     * @param fieldBytes    字段原始字节
     * @param messageConfig 报文运行期配置
     * @return 当前长度单位下的字段长度
     */
    public static int fieldLength(byte[] fieldBytes, MessageConfig messageConfig) {
        if (isCharacterLength(messageConfig)) {
            return new String(fieldBytes, messageConfig.targetCharset().getCharset()).length();
        }
        return fieldBytes.length;
    }

    /**
     * 将字段编码为固定长度字节。
     * <p>
     * 字节模式按字节截断/补齐；字符模式按字符截断/补齐后再转回目标链路字节。
     * {@code length < 0} 的不定长字段直接返回原始字节。
     *
     * @param bytes         字段值原始字节
     * @param fixedField    字段长度和补齐配置
     * @param messageConfig 报文运行期配置
     * @return 符合字段长度定义的链路字节
     */
    public static byte[] encodeFixedField(byte[] bytes, FixedField fixedField, MessageConfig messageConfig) {
        if (fixedField.length() < 0) {
            return bytes;
        }
        if (isCharacterLength(messageConfig)) {
            return encodeByCharacters(bytes, fixedField, messageConfig.targetCharset().getCharset());
        }
        return encodeByBytes(bytes, fixedField);
    }

    /**
     * 按配置的长度单位从报文体中截取固定字段字节。
     * <p>
     * 字符模式会从当前位置开始按目标字符集解码剩余字节，再截取指定字符数，并用截取后
     * 字符串重新编码得到字段原始字节，同时返回下一个字节游标位置。
     *
     * @param body          报文体字节
     * @param pos           当前字节游标
     * @param length        固定字段长度，单位由 {@link io.github.windtool.tcp.annotation.MessageLength} 决定
     * @param fieldName     字段名，仅用于异常信息
     * @param fieldOrder    字段顺序，仅用于异常信息
     * @param messageConfig 报文运行期配置
     * @return 字段字节和下一个字节游标
     */
    public static FieldSlice readFixedFieldBytes(
        byte[] body,
        int pos,
        int length,
        String fieldName,
        int fieldOrder,
        MessageConfig messageConfig
    ) {
        validateReadArgs(body, pos, length, fieldName, fieldOrder);
        if (!isCharacterLength(messageConfig)) {
            validateByteLength(body, pos, length, fieldName, fieldOrder);
            return new FieldSlice(Arrays.copyOfRange(body, pos, pos + length), pos + length);
        }

        Charset targetCharset = messageConfig.targetCharset().getCharset();
        String remaining = new String(body, pos, body.length - pos, targetCharset);
        if (remaining.length() < length) {
            throw new IllegalStateException("报文长度不足，无法解析字段：" + fieldName
                + "，字段顺序：" + fieldOrder
                + "，字段字符长度：" + length
                + "，当前位置：" + pos
                + "，剩余字符数：" + remaining.length());
        }
        String fieldText = remaining.substring(0, length);
        byte[] fieldBytes = fieldText.getBytes(targetCharset);
        return new FieldSlice(fieldBytes, pos + fieldBytes.length);
    }

    /**
     * 去掉字段字节中的补齐字符。
     *
     * @param bytes         字段字节
     * @param padByte       补齐字节
     * @param trimBothSides true 表示左右都裁剪，false 表示只裁剪右侧
     * @return 裁剪补齐字节后的字段内容
     */
    public static byte[] trimPadBytes(byte[] bytes, byte padByte, boolean trimBothSides) {
        int start = 0;
        int end = bytes.length;
        if (trimBothSides) {
            while (start < end && bytes[start] == padByte) {
                start++;
            }
            while (end > start && bytes[end - 1] == padByte) {
                end--;
            }
        } else {
            while (end > start && bytes[end - 1] == padByte) {
                end--;
            }
        }
        return Arrays.copyOfRange(bytes, start, end);
    }

    /**
     * 按字节长度编码固定字段。
     * <p>
     * 字段超长时直接按字节截断；不足时按字段对齐方向补齐。
     */
    private static byte[] encodeByBytes(byte[] bytes, FixedField fixedField) {
        if (bytes.length > fixedField.length()) {
            bytes = Arrays.copyOf(bytes, fixedField.length());
        }

        int padLen = fixedField.length() - bytes.length;
        if (padLen <= 0) {
            return bytes;
        }

        return padBytes(bytes, fixedField.length(), fixedField.padType().getValue(), fixedField.align());
    }

    /**
     * 按字符长度编码固定字段。
     * <p>
     * 先按目标字符集还原文本，再按字符截断/补齐，最后重新编码成链路字节。
     */
    private static byte[] encodeByCharacters(byte[] bytes, FixedField fixedField, Charset targetCharset) {
        String text = new String(bytes, targetCharset);
        if (text.length() > fixedField.length()) {
            text = text.substring(0, fixedField.length());
        }

        int padLen = fixedField.length() - text.length();
        if (padLen <= 0) {
            return text.getBytes(targetCharset);
        }

        return padText(text, padLen, (char) fixedField.padType().getValue(), fixedField.align()).getBytes(targetCharset);
    }

    /**
     * 校验固定字段截取入参。
     */
    private static void validateReadArgs(byte[] body, int pos, int length, String fieldName, int fieldOrder) {
        if (length < 0) {
            throw new IllegalArgumentException("固定字段长度不能小于 0，字段：" + fieldName + "，字段顺序：" + fieldOrder);
        }
        if (pos < 0 || pos > body.length) {
            throw new IllegalArgumentException("字段读取位置非法，字段：" + fieldName
                + "，字段顺序：" + fieldOrder
                + "，当前位置：" + pos
                + "，报文体长度：" + body.length);
        }
    }

    /**
     * 校验按字节截取时剩余报文体长度是否足够。
     */
    private static void validateByteLength(byte[] body, int pos, int length, String fieldName, int fieldOrder) {
        if (pos + length > body.length) {
            throw new IllegalStateException("报文长度不足，无法解析字段：" + fieldName
                + "，字段顺序：" + fieldOrder
                + "，字段长度：" + length
                + "，当前位置：" + pos
                + "，报文体长度：" + body.length);
        }
    }

    /**
     * 按字节补齐字段。
     */
    private static byte[] padBytes(byte[] bytes, int targetLength, byte padByte, Align align) {
        int padLen = targetLength - bytes.length;
        byte[] result = new byte[targetLength];
        if (align == Align.RIGHT) {
            Arrays.fill(result, 0, padLen, padByte);
            System.arraycopy(bytes, 0, result, padLen, bytes.length);
        } else {
            System.arraycopy(bytes, 0, result, 0, bytes.length);
            Arrays.fill(result, bytes.length, result.length, padByte);
        }
        return result;
    }

    /**
     * 按字符补齐字段文本。
     */
    private static String padText(String text, int padLen, char padChar, Align align) {
        String padding = String.valueOf(padChar).repeat(padLen);
        if (align == Align.RIGHT) {
            return padding + text;
        }
        return text + padding;
    }

    /**
     * 固定字段截取结果。
     *
     * @param bytes   字段原始字节
     * @param nextPos 字段后的下一个字节位置
     */
    public record FieldSlice(byte[] bytes, int nextPos) {
    }

}
