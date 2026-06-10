package io.github.windtool.tcp.utils;

import io.github.windtool.tcp.annotation.FieldOrder;
import io.github.windtool.tcp.annotation.FixedField;
import io.github.windtool.tcp.annotation.ListField;
import io.github.windtool.tcp.annotation.Md5Field;
import io.github.windtool.tcp.core.MessageConfig;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 报文编解码通用协议工具。
 * <p>
 * 这里放置编码器和解码器都依赖的“协议骨架”逻辑，例如字段顺序、分隔符和 MD5 原文字节收集。
 * 字段内容本身的字节/字符长度处理放在 {@link MessageFieldUtils}，避免两个工具职责混在一起。
 *
 * @author AprilWind
 */
public final class MessageCodecUtils {

    /**
     * 工具类禁止实例化。
     */
    private MessageCodecUtils() {
    }

    /**
     * 获取并按 {@link FieldOrder} 排序报文字段。
     * <p>
     * 编码和解码必须使用同一排序规则，否则 MD5 范围、循环明细和定长游标都会出现错位。
     *
     * @param clazz 报文实体类型
     * @return 按 {@link FieldOrder#value()} 升序排列的固定字段
     */
    public static List<Field> sortedFields(Class<?> clazz) {
        return Arrays.stream(clazz.getDeclaredFields())
            .filter(f -> f.isAnnotationPresent(FixedField.class))
            .sorted(Comparator.comparingInt(MessageCodecUtils::resolveOrder))
            .toList();
    }

    /**
     * 解析字段顺序。
     * <p>
     * 固定报文字段必须显式声明顺序，避免 JVM 反射字段顺序差异影响协议。
     *
     * @param field 报文字段
     * @return 字段顺序
     */
    public static int resolveOrder(Field field) {
        FieldOrder fieldOrder = field.getAnnotation(FieldOrder.class);
        if (fieldOrder == null) {
            throw new IllegalStateException("报文字段缺少 @FieldOrder 注解：" + field.getDeclaringClass().getName() + "." + field.getName());
        }
        return fieldOrder.value();
    }

    /**
     * 判断字段是否为对象里的总包长字段。
     * <p>
     * 约定 order=0 且长度等于报头长度的字段，只作为对象视图中的报头镜像，
     * 真正 TCP 报头由编码器单独生成，解码读取阶段也会先消费。
     *
     * @param field         报文字段
     * @param fixedField    字段长度配置
     * @param messageConfig 报文运行期配置
     * @return true 表示该字段是报头镜像字段
     */
    public static boolean isHeaderField(Field field, FixedField fixedField, MessageConfig messageConfig) {
        return resolveOrder(field) == 0 && fixedField.length() == messageConfig.headerLength();
    }

    /**
     * 判断报文类是否把 TCP 报头总包长建模为第 0 个字段。
     *
     * @param clazz        报文实体类型
     * @param headerLength 报头长度
     * @return true 表示对象字段里包含报头镜像字段
     */
    public static boolean hasHeaderField(Class<?> clazz, int headerLength) {
        for (Field field : clazz.getDeclaredFields()) {
            FixedField fixedField = field.getAnnotation(FixedField.class);
            FieldOrder fieldOrder = field.getAnnotation(FieldOrder.class);
            if (fixedField != null && fieldOrder != null
                && fieldOrder.value() == 0 && fixedField.length() == headerLength) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断字段是否为循环明细字段。
     *
     * @param field 报文字段
     * @return true 表示字段标注了 {@link ListField}
     */
    public static boolean isListField(Field field) {
        return field.isAnnotationPresent(ListField.class);
    }

    /**
     * 按配置给单个字段追加分隔符。
     * <p>
     * 返回的新字节用于落链路和 MD5 计算；无分隔符协议直接复用原数组，避免额外拷贝。
     *
     * @param fieldBytes    字段原始链路字节
     * @param messageConfig 报文运行期配置
     * @return 必要时追加分隔符后的字段字节
     */
    public static byte[] appendDelimiterIfNeeded(byte[] fieldBytes, MessageConfig messageConfig) {
        if (!messageConfig.useDelimiter()) {
            return fieldBytes;
        }
        byte[] bytes = Arrays.copyOf(fieldBytes, fieldBytes.length + 1);
        bytes[fieldBytes.length] = (byte) messageConfig.delimiter();
        return bytes;
    }

    /**
     * 按单字节分隔符切分报文体。
     * <p>
     * 如果报文以分隔符结尾，末尾空字段会被忽略；当前协议把该分隔符视为结束标记，
     * 不是额外的空字段。
     *
     * @param body      报文体字节
     * @param delimiter 单字节字段分隔符
     * @return 切分后的字段原始字节列表
     */
    public static List<byte[]> splitByDelimiter(byte[] body, byte delimiter) {
        List<byte[]> segments = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < body.length; i++) {
            if (body[i] == delimiter) {
                segments.add(Arrays.copyOfRange(body, start, i));
                start = i + 1;
            }
        }
        if (start < body.length) {
            segments.add(Arrays.copyOfRange(body, start, body.length));
        }
        return segments;
    }

    /**
     * 从指定位置查找目标字节。
     *
     * @param body   待搜索字节数组
     * @param target 目标字节
     * @param start  起始搜索下标
     * @return 找到时返回字节下标，未找到返回 -1
     */
    public static int indexOf(byte[] body, byte target, int start) {
        for (int i = start; i < body.length; i++) {
            if (body[i] == target) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 解析 MD5 参与计算的字段范围。
     *
     * @param md5Field MD5 字段注解
     * @return MD5 起止字段顺序
     */
    public static Md5Range md5Range(Md5Field md5Field) {
        return new Md5Range(md5Field.startOrder(), md5Field.endOrder());
    }

    /**
     * 按 MD5 起止字段顺序收集签名源字节。
     * <p>
     * 未配置完整范围时使用 fallbackBytes，通常表示“当前已经编码完成的整段报文体”。
     *
     * @param bytesByOrder 已落链路的字段字节，key 为字段顺序
     * @param fallbackBytes 未配置范围时使用的默认签名源
     * @param md5Range MD5 起止字段顺序
     * @return MD5 签名源字节
     */
    public static byte[] resolveMd5SourceBytes(Map<Integer, byte[]> bytesByOrder, byte[] fallbackBytes, Md5Range md5Range) {
        if (md5Range.startOrder() < 0 || md5Range.endOrder() < 0) {
            return fallbackBytes;
        }
        validateMd5Range(md5Range.startOrder(), md5Range.endOrder());
        return collectBytes(bytesByOrder, true, md5Range.startOrder(), md5Range.endOrder());
    }

    /**
     * 按 MD5 注解配置从已解析字段里收集签名源字节。
     * <p>
     * 解码阶段只能使用已经解析过的字段原文字节，因此默认范围是 prior bytes。
     *
     * @param bytesByOrder 已解析字段原文字节，key 为字段顺序
     * @param md5Field MD5 字段注解
     * @return MD5 签名源字节
     */
    public static byte[] resolveMd5SourceBytes(Map<Integer, byte[]> bytesByOrder, Md5Field md5Field) {
        boolean useRange = md5Field.startOrder() >= 0 && md5Field.endOrder() >= 0;
        if (useRange) {
            validateMd5Range(md5Field.startOrder(), md5Field.endOrder());
        }

        return collectBytes(bytesByOrder, useRange, md5Field.startOrder(), md5Field.endOrder());
    }

    /**
     * 按字段顺序范围收集字节。
     * <p>
     * useRange=false 时收集全部已提供字段；useRange=true 时只收集闭区间内字段。
     */
    private static byte[] collectBytes(Map<Integer, byte[]> bytesByOrder, boolean useRange, int startOrder, int endOrder) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (bytesByOrder != null) {
            for (Map.Entry<Integer, byte[]> entry : bytesByOrder.entrySet()) {
                int order = entry.getKey();
                if (!useRange || (order >= startOrder && order <= endOrder)) {
                    out.write(entry.getValue(), 0, entry.getValue().length);
                }
            }
        }
        return out.toByteArray();
    }

    /**
     * 校验 MD5 字段范围配置。
     */
    private static void validateMd5Range(int startOrder, int endOrder) {
        if (startOrder > endOrder) {
            throw new IllegalArgumentException("MD5计算范围配置错误，起始字段顺序不能大于结束字段顺序："
                + startOrder + ">" + endOrder);
        }
    }

    /**
     * MD5 参与计算的字段顺序范围。
     *
     * @param startOrder 起始字段顺序
     * @param endOrder   结束字段顺序
     */
    public record Md5Range(int startOrder, int endOrder) {
    }

}
