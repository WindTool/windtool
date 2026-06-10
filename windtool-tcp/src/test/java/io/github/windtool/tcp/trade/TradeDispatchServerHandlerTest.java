package io.github.windtool.tcp.trade;

import io.github.windtool.tcp.annotation.FieldOrder;
import io.github.windtool.tcp.annotation.FixedField;
import io.github.windtool.tcp.annotation.FixedMessage;
import io.github.windtool.tcp.annotation.MessageDelimiter;
import io.github.windtool.tcp.core.MessageConfig;
import io.github.windtool.tcp.enums.Align;
import io.github.windtool.tcp.enums.CharsetType;
import io.github.windtool.tcp.enums.LengthType;
import io.github.windtool.tcp.enums.PadType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TradeDispatchServerHandler} 的分发规则单元测试。
 * <p>
 * 这里不走完整 Socket 读写，只验证交易码如何从原始 body 或已解码请求对象中提取，
 * 以及提取后是否能路由到正确的 {@link TradeHandler}。
 */
class TradeDispatchServerHandlerTest {

    @Test
    void selectsRequestClassByDelimiterTradeCode() {
        // 注册两个交易处理器，模拟同一 TCP 端口承载多个交易码。
        TradeDispatchServerHandler dispatch = new TradeDispatchServerHandler(List.of(
            new DemoTradeHandler("1001", DemoTradeRequest.class),
            new DemoTradeHandler("2001", OtherTradeRequest.class)
        ));

        // 默认分隔符规则：第 2 个字段是交易码，因此 body 应命中 2001。
        byte[] body = "00|2001|hello|".getBytes(StandardCharsets.UTF_8);

        assertThat(dispatch.supports(body, TradeDispatchOptions.defaults().frameMessage())).isTrue();
        assertThat(dispatch.requestClass(body, TradeDispatchOptions.defaults().frameMessage())).isEqualTo(OtherTradeRequest.class);
    }

    @Test
    void rejectsDuplicateTradeCode() {
        // 同一个交易码只能有一个处理器，否则运行期无法确定应该调用哪一个。
        assertThatThrownBy(() -> new TradeDispatchServerHandler(List.of(
            new DemoTradeHandler("1001", DemoTradeRequest.class),
            new DemoTradeHandler("1001", OtherTradeRequest.class)
        ))).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("交易码重复注册");
    }

    @Test
    void selectsRequestClassByFixedLengthTradeCode() {
        // 自定义无分隔符帧配置，用来覆盖固定长度报文的交易码读取规则。
        MessageConfig fixedFrame = new MessageConfig(
            Object.class,
            CharsetType.UTF8,
            CharsetType.UTF8,
            LengthType.BYTES,
            4,
            true,
            PadType.SPACE,
            Align.LEFT,
            false,
            '|',
            true
        );
        TradeDispatchOptions options = new TradeDispatchOptions(fixedFrame, 2, 2, 4, 2, true);
        TradeDispatchServerHandler dispatch = new TradeDispatchServerHandler(List.of(
            new DemoTradeHandler("3001", DemoTradeRequest.class)
        ), options);

        // 固定长度规则：跳过前 2 字节区位代码，从 offset=2 读取 4 字节交易码。
        byte[] body = "003001HELLO".getBytes(StandardCharsets.UTF_8);

        assertThat(dispatch.supports(body, fixedFrame)).isTrue();
        assertThat(dispatch.requestClass(body, fixedFrame)).isEqualTo(DemoTradeRequest.class);
    }

    @Test
    void invokesHandlerByDecodedRequestTradeCode() {
        // handle 阶段收到的是已解码请求对象，需要从 @FieldOrder(2) 字段再次定位交易码。
        DemoTradeHandler tradeHandler = new DemoTradeHandler("1001", DemoTradeRequest.class);
        TradeDispatchServerHandler dispatch = new TradeDispatchServerHandler(List.of(tradeHandler));
        DemoTradeRequest request = new DemoTradeRequest();
        request.areaCode = "00";
        request.tradeCode = "1001";

        Object response = dispatch.handle(request);

        assertThat(response).isInstanceOf(DemoTradeResponse.class);
        assertThat(tradeHandler.handled).isSameAs(request);
    }

    @FixedMessage
    @MessageDelimiter(enabled = true, value = '|')
    static class DemoTradeRequest {
        // 默认约定下，第 1 字段通常是区位/机构等公共字段。
        @FieldOrder(1)
        @FixedField(length = 2)
        String areaCode;

        // 默认约定下，第 2 字段是交易码，分发器会用它找到处理器。
        @FieldOrder(2)
        @FixedField(length = 4)
        String tradeCode;
    }

    @FixedMessage
    static class OtherTradeRequest extends DemoTradeRequest {
    }

    static class DemoTradeResponse {
    }

    static class DemoTradeHandler implements TradeHandler<DemoTradeRequest, DemoTradeResponse> {
        // 测试用 handler 支持动态指定交易码和请求类型，避免为每个用例创建一堆样板类。
        private final String tradeCode;
        private final Class<? extends DemoTradeRequest> requestClass;
        private DemoTradeRequest handled;

        DemoTradeHandler(String tradeCode, Class<? extends DemoTradeRequest> requestClass) {
            this.tradeCode = tradeCode;
            this.requestClass = requestClass;
        }

        @Override
        public String tradeCode() {
            return tradeCode;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Class<DemoTradeRequest> requestClass() {
            // 测试场景下允许返回 DemoTradeRequest 子类，生产代码通常直接返回精确请求类型。
            return (Class<DemoTradeRequest>) requestClass;
        }

        @Override
        public Class<DemoTradeResponse> responseClass() {
            return DemoTradeResponse.class;
        }

        @Override
        public DemoTradeResponse handle(DemoTradeRequest request) {
            // 记录被处理的请求对象，便于断言分发器确实调用了当前 handler。
            this.handled = request;
            return new DemoTradeResponse();
        }
    }

}
