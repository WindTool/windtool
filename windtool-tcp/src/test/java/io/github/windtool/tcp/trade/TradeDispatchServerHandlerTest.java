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

class TradeDispatchServerHandlerTest {

    @Test
    void selectsRequestClassByDelimiterTradeCode() {
        TradeDispatchServerHandler dispatch = new TradeDispatchServerHandler(List.of(
            new DemoTradeHandler("1001", DemoTradeRequest.class),
            new DemoTradeHandler("2001", OtherTradeRequest.class)
        ));

        byte[] body = "00|2001|hello|".getBytes(StandardCharsets.UTF_8);

        assertThat(dispatch.supports(body, TradeDispatchOptions.defaults().frameMessage())).isTrue();
        assertThat(dispatch.requestClass(body, TradeDispatchOptions.defaults().frameMessage())).isEqualTo(OtherTradeRequest.class);
    }

    @Test
    void rejectsDuplicateTradeCode() {
        assertThatThrownBy(() -> new TradeDispatchServerHandler(List.of(
            new DemoTradeHandler("1001", DemoTradeRequest.class),
            new DemoTradeHandler("1001", OtherTradeRequest.class)
        ))).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("交易码重复注册");
    }

    @Test
    void selectsRequestClassByFixedLengthTradeCode() {
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

        byte[] body = "003001HELLO".getBytes(StandardCharsets.UTF_8);

        assertThat(dispatch.supports(body, fixedFrame)).isTrue();
        assertThat(dispatch.requestClass(body, fixedFrame)).isEqualTo(DemoTradeRequest.class);
    }

    @Test
    void invokesHandlerByDecodedRequestTradeCode() {
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
        @FieldOrder(1)
        @FixedField(length = 2)
        String areaCode;

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
            return (Class<DemoTradeRequest>) requestClass;
        }

        @Override
        public Class<DemoTradeResponse> responseClass() {
            return DemoTradeResponse.class;
        }

        @Override
        public DemoTradeResponse handle(DemoTradeRequest request) {
            this.handled = request;
            return new DemoTradeResponse();
        }
    }

}
