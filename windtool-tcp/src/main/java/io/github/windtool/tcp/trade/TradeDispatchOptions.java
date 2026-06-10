package io.github.windtool.tcp.trade;

import io.github.windtool.tcp.core.MessageConfig;
import io.github.windtool.tcp.enums.Align;
import io.github.windtool.tcp.enums.CharsetType;
import io.github.windtool.tcp.enums.LengthType;
import io.github.windtool.tcp.enums.PadType;

/**
 * 交易分发配置。
 *
 * @param frameMessage               读取 TCP 帧时使用的报文配置
 * @param delimiterTradeCodeField    分隔符报文中交易码字段位置，从 1 开始
 * @param fixedTradeCodeOffset       固定长度报文中交易码起始偏移，单位字节
 * @param fixedTradeCodeLength       固定长度报文中交易码长度，单位字节
 * @param requestTradeCodeFieldOrder 已解码请求对象中交易码字段的 {@code @FieldOrder} 顺序
 * @param scanRegisteredTradeCodes   分隔符报文标准位置未命中时，是否扫描已注册交易码
 * @author AprilWind
 */
public record TradeDispatchOptions(
    MessageConfig frameMessage,
    int delimiterTradeCodeField,
    int fixedTradeCodeOffset,
    int fixedTradeCodeLength,
    int requestTradeCodeFieldOrder,
    boolean scanRegisteredTradeCodes
) {

    public TradeDispatchOptions {
        if (frameMessage == null) {
            throw new IllegalArgumentException("frameMessage 不能为空");
        }
        if (delimiterTradeCodeField < 1) {
            throw new IllegalArgumentException("delimiterTradeCodeField 必须从 1 开始");
        }
        if (fixedTradeCodeOffset < 0) {
            throw new IllegalArgumentException("fixedTradeCodeOffset 不能小于 0");
        }
        if (fixedTradeCodeLength < 1) {
            throw new IllegalArgumentException("fixedTradeCodeLength 必须大于 0");
        }
        if (requestTradeCodeFieldOrder < 1) {
            throw new IllegalArgumentException("requestTradeCodeFieldOrder 必须从 1 开始");
        }
    }

    /**
     * 默认交易分发配置，兼容常见“4 位总长报头 + 分隔符字段 + 第 2 字段为交易码”的短连接报文。
     *
     * @return 默认交易分发配置
     */
    public static TradeDispatchOptions defaults() {
        return new TradeDispatchOptions(
            new MessageConfig(
                Object.class,
                CharsetType.UTF8,
                CharsetType.UTF8,
                LengthType.BYTES,
                4,
                true,
                PadType.SPACE,
                Align.LEFT,
                true,
                '|',
                true
            ),
            2,
            2,
            4,
            2,
            true
        );
    }

}
