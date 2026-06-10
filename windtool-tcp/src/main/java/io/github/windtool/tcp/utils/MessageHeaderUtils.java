package io.github.windtool.tcp.utils;

import lombok.extern.slf4j.Slf4j;
import io.github.windtool.tcp.core.MessageConfig;
import io.github.windtool.tcp.enums.Align;
import io.github.windtool.tcp.enums.PadType;

import java.nio.charset.Charset;

/**
 * TCP 报文头封装工具。
 *
 * @author AprilWind
 */
@Slf4j
public final class MessageHeaderUtils {

    private static final int LOG_PREVIEW_LIMIT = 120;

    /**
     * 工具类禁止实例化。
     */
    private MessageHeaderUtils() {
    }

    /**
     * 使用指定声明长度封装报头。
     * <p>
     * 适用于报文体里存在不参与报头长度统计的尾部内容时，由调用方传入声明长度。
     *
     * @param body               已编码的报文体字节
     * @param declaredBodyLength 写入报文头的报文体声明长度
     * @param messageConfig      报文运行期配置
     * @return 完整报文字节
     */
    public static byte[] wrapMessageWithHeader(byte[] body, int declaredBodyLength, MessageConfig messageConfig) {
        log.debug("报头封装开始，bodyLength={}，declaredBodyLength={}，messageConfig={{headerLength={},headerIncludeSelf={},useDelimiter={},delimiter={},appendEndDelimiter={},targetCharset={},systemCharset={},padType={},align={}}}",
            body.length, declaredBodyLength, messageConfig.headerLength(), messageConfig.headerIncludeSelf(),
            messageConfig.useDelimiter(), messageConfig.delimiter(), messageConfig.appendEndDelimiter(), messageConfig.targetCharset(), messageConfig.systemCharset(),
            messageConfig.padType(), messageConfig.align());

        int originalHeaderLength = messageConfig.headerLength();
        boolean useDelimiter = messageConfig.useDelimiter();
        PadType padType = messageConfig.padType();
        Align align = messageConfig.align();
        boolean headerIncludeSelf = messageConfig.headerIncludeSelf();

        int finalLengthInHeader;
        if (headerIncludeSelf) {
            int headerTotalBytes = originalHeaderLength + (useDelimiter ? 1 : 0);
            finalLengthInHeader = headerTotalBytes + declaredBodyLength;
        } else {
            finalLengthInHeader = declaredBodyLength;
        }

        String lengthStr = String.valueOf(finalLengthInHeader);
        if (lengthStr.length() > originalHeaderLength) {
            throw new IllegalArgumentException(
                "报文长度[" + finalLengthInHeader + "]超出报头可表示范围，报头最大支持" + originalHeaderLength + "位数字"
            );
        }

        byte padByte = padType.getValue();
        byte[] headerBytes = new byte[originalHeaderLength];
        int padCount = originalHeaderLength - lengthStr.length();
        byte[] lengthBytes = new byte[lengthStr.length()];
        for (int i = 0; i < lengthStr.length(); i++) {
            lengthBytes[i] = (byte) lengthStr.charAt(i);
        }

        if (align == Align.LEFT) {
            System.arraycopy(lengthBytes, 0, headerBytes, 0, lengthBytes.length);
            for (int i = lengthBytes.length; i < originalHeaderLength; i++) {
                headerBytes[i] = padByte;
            }
        } else {
            for (int i = 0; i < padCount; i++) {
                headerBytes[i] = padByte;
            }
            System.arraycopy(lengthBytes, 0, headerBytes, padCount, lengthBytes.length);
        }

        byte[] finalHeaderBytes;
        if (useDelimiter) {
            finalHeaderBytes = new byte[originalHeaderLength + 1];
            System.arraycopy(headerBytes, 0, finalHeaderBytes, 0, headerBytes.length);
            finalHeaderBytes[originalHeaderLength] = (byte) messageConfig.delimiter();
        } else {
            finalHeaderBytes = headerBytes;
        }

        byte[] fullMessage = NettyIoUtils.appendBytes(finalHeaderBytes, body);

        Charset targetCharset = messageConfig.targetCharset().getCharset();
        log.debug("报头封装完成，headerBytesLength={}，fullMessageLength={}，headerPreview={}，bodyPreview={}",
            finalHeaderBytes.length, fullMessage.length,
            previewText(new String(finalHeaderBytes, targetCharset)),
            previewText(new String(body, targetCharset)));

        return fullMessage;
    }

    /**
     * 截断日志预览文本。
     *
     * @param text 原始文本
     * @return 截断后的日志文本
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
