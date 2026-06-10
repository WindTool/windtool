package io.github.windtool.tcp.core;

import io.github.windtool.tcp.annotation.FixedMessage;
import io.github.windtool.tcp.annotation.MessageLength;
import io.github.windtool.tcp.annotation.MessageCharset;
import io.github.windtool.tcp.annotation.MessageDelimiter;
import io.github.windtool.tcp.annotation.MessageHeader;
import io.github.windtool.tcp.annotation.MessagePadding;
import io.github.windtool.tcp.enums.Align;
import io.github.windtool.tcp.enums.CharsetType;
import io.github.windtool.tcp.enums.LengthType;
import io.github.windtool.tcp.enums.PadType;

/**
 * 运行期报文配置。
 * <p>
 * 类级注解只负责声明配置，编码、解码和 TCP 读写统一使用该对象。
 *
 * @param messageClass        报文实体类型
 * @param targetCharset      TCP 链路传输字符集
 * @param systemCharset      系统内部字符集
 * @param lengthType         {@link io.github.windtool.tcp.annotation.FixedField#length()} 的长度单位
 * @param headerLength       报文头长度，单位字节
 * @param headerIncludeSelf  报文头长度值是否包含报文头自身
 * @param padType            报文头补齐字符类型
 * @param align              报文头长度值补齐方向
 * @param useDelimiter       是否启用字段分隔符
 * @param delimiter          字段分隔符
 * @param appendEndDelimiter 报文体末尾是否追加分隔符
 */
public record MessageConfig(
    Class<?> messageClass,
    CharsetType targetCharset,
    CharsetType systemCharset,
    LengthType lengthType,
    int headerLength,
    boolean headerIncludeSelf,
    PadType padType,
    Align align,
    boolean useDelimiter,
    char delimiter,
    boolean appendEndDelimiter
) {

    /**
     * 从报文实体类上的拆分注解解析运行期配置。
     *
     * @param messageClass 报文实体类型
     * @return 运行期报文配置
     */
    public static MessageConfig from(Class<?> messageClass) {
        if (!messageClass.isAnnotationPresent(FixedMessage.class)) {
            throw new IllegalArgumentException("实体类必须标注 @FixedMessage 注解：" + messageClass.getName());
        }

        MessageCharset charset = messageClass.getAnnotation(MessageCharset.class);
        MessageLength length = messageClass.getAnnotation(MessageLength.class);
        MessageHeader header = messageClass.getAnnotation(MessageHeader.class);
        MessagePadding padding = messageClass.getAnnotation(MessagePadding.class);
        MessageDelimiter delimiter = messageClass.getAnnotation(MessageDelimiter.class);

        return new MessageConfig(
            messageClass,
            charset == null ? CharsetType.UTF8 : charset.target(),
            charset == null ? CharsetType.UTF8 : charset.system(),
            length == null ? LengthType.BYTES : length.value(),
            header == null ? 4 : header.length(),
            header != null && header.includeSelf(),
            padding == null ? PadType.SPACE : padding.padType(),
            padding == null ? Align.LEFT : padding.align(),
            delimiter != null && delimiter.enabled(),
            delimiter == null ? '|' : delimiter.value(),
            delimiter == null || delimiter.appendEnd()
        );
    }

}
