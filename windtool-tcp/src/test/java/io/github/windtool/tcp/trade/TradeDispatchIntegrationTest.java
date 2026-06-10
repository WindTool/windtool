package io.github.windtool.tcp.trade;

import io.github.windtool.tcp.annotation.FieldOrder;
import io.github.windtool.tcp.annotation.FixedField;
import io.github.windtool.tcp.annotation.FixedMessage;
import io.github.windtool.tcp.annotation.Md5Field;
import io.github.windtool.tcp.annotation.MessageDelimiter;
import io.github.windtool.tcp.annotation.MessageHeader;
import io.github.windtool.tcp.core.MessageConfig;
import io.github.windtool.tcp.handler.MessageDecoder;
import io.github.windtool.tcp.handler.MessageEncoder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 交易码分发的端到端风格测试。
 * <p>
 * 这个测试把编码器、分发器、解码器和业务 handler 串起来，模拟真实服务端处理一笔短连接交易：
 * 上游发送完整 TCP 包，服务端读取 body，按交易码选择请求类型，解码对象，调用 handler，再编码响应。
 */
class TradeDispatchIntegrationTest {

    private final MessageEncoder encoder = new MessageEncoder();
    private final MessageDecoder decoder = new MessageDecoder();

    @Test
    void dispatchesDecodedBusinessRequestAndEncodesResponse() throws Exception {
        // 模拟业务项目中“每个交易码一个 handler”的注册方式。
        DemoAccountOpenHandler accountOpenHandler = new DemoAccountOpenHandler();
        TradeDispatchServerHandler dispatch = new TradeDispatchServerHandler(List.of(
            accountOpenHandler,
            new DemoAccountQueryHandler()
        ));

        DemoAccountOpenRequest request = new DemoAccountOpenRequest();
        // 这里的字段形状参考真实项目中的中心平台报文，但名称和交易码都是测试用中性样例。
        request.areaCode = "00";
        request.tradeCode = "5005";
        request.bankCode = "01";
        request.unitName = "WindTool";
        request.accountNo = "ACCT00000001";
        request.systemSerialNo = "000000000001";

        // 客户端或上游系统发送的是完整 TCP 包：报头 + 报头分隔符 + 报文体。
        byte[] packet = encoder.encode(request, MessageConfig.from(DemoAccountOpenRequest.class));
        MessageConfig requestMessageConfig = MessageConfig.from(DemoAccountOpenRequest.class);
        // NettyServer.readFrame 会消费报头和报头后的分隔符，分发器收到的 body 从第一个业务字段开始。
        int frameHeaderLength = requestMessageConfig.headerLength() + (requestMessageConfig.useDelimiter() ? 1 : 0);
        byte[] body = Arrays.copyOfRange(packet, frameHeaderLength, packet.length);

        // 分发器先从原始 body 读取交易码，确定实际请求类型。
        assertThat(dispatch.supports(body, dispatch.frameMessage())).isTrue();
        Class<?> requestClass = dispatch.requestClass(body, dispatch.frameMessage());
        // 交易码 5005 必须路由到开户请求类，而不是同端口上的其他交易请求类。
        assertThat(requestClass).isEqualTo(DemoAccountOpenRequest.class);

        // NettyServer 随后使用实际请求类型解码，并把对象交给分发器转调具体 handler。
        Object decodedRequest = decoder.decode(body, requestClass, MessageConfig.from(requestClass));
        Object response = dispatch.handle(decodedRequest);
        assertThat(response).isInstanceOf(DemoAccountOpenResponse.class);
        // 确认真正被调用的是 5005 对应的 handler。
        assertThat(accountOpenHandler.handled).isInstanceOf(DemoAccountOpenRequest.class);
        assertThat(((DemoAccountOpenResponse) response).tradeCode).isEqualTo("5105");
        assertThat(((DemoAccountOpenResponse) response).systemSerialNo).isEqualTo("000000000001");

        // 响应沿用请求协议配置编码，MD5 字段由 MessageEncoder 自动生成。
        byte[] responsePacket = encoder.encode(response, dispatch.responseMessage(response, MessageConfig.from(requestClass)));
        String responseText = new String(responsePacket, StandardCharsets.UTF_8);
        // 响应应包含区位、应答交易码和成功码；报头长度与 MD5 由编码器自动生成。
        assertThat(responseText).contains("00|5105|0000|");
    }

    @FixedMessage
    @MessageHeader(includeSelf = true)
    @MessageDelimiter(enabled = true)
    public static class DemoAccountOpenRequest {
        // 默认分发规则要求 @FieldOrder(2) 字段为请求交易码。
        @FieldOrder(1)
        @FixedField(length = 2)
        String areaCode;

        @FieldOrder(2)
        @FixedField(length = 4)
        String tradeCode = "5005";

        @FieldOrder(3)
        @FixedField(length = 2)
        String bankCode;

        @FieldOrder(4)
        @FixedField(length = 20)
        // 编码器会按字段长度自动补齐；测试用较短内容验证补齐后仍能正确解码。
        String unitName;

        @FieldOrder(5)
        @FixedField(length = 12)
        String accountNo;

        @FieldOrder(6)
        @FixedField(length = 12)
        String systemSerialNo;

        @FieldOrder(7)
        @FixedField(length = 32)
        // 测试覆盖编码生成与解码校验 MD5 的完整流程。
        @Md5Field(startOrder = 1, endOrder = 6)
        String md5Verify;
    }

    @FixedMessage
    @MessageHeader(includeSelf = true)
    @MessageDelimiter(enabled = true)
    public static class DemoAccountOpenResponse {
        // 响应类不需要实现任何接口，只要用注解描述报文结构即可。
        @FieldOrder(1)
        @FixedField(length = 2)
        String areaCode;

        @FieldOrder(2)
        @FixedField(length = 4)
        String tradeCode;

        @FieldOrder(3)
        @FixedField(length = 4)
        String resultCode;

        @FieldOrder(4)
        @FixedField(length = 20)
        String resultMessage;

        @FieldOrder(5)
        @FixedField(length = 12)
        String systemSerialNo;

        @FieldOrder(6)
        @FixedField(length = 32)
        @Md5Field(startOrder = 1, endOrder = 5)
        String md5Verify;
    }

    @FixedMessage
    @MessageHeader(includeSelf = true)
    @MessageDelimiter(enabled = true)
    public static class DemoAccountQueryRequest {
        // 第二个交易请求类用于证明同一个分发器可以注册多个交易码。
        @FieldOrder(1)
        @FixedField(length = 2)
        String areaCode;

        @FieldOrder(2)
        @FixedField(length = 4)
        String tradeCode = "4003";

        @FieldOrder(3)
        @FixedField(length = 12)
        String accountNo;
    }

    static class DemoAccountOpenHandler implements TradeHandler<DemoAccountOpenRequest, DemoAccountOpenResponse> {
        // 保存请求对象，测试可以验证 handler 是否真的被分发器调用。
        private DemoAccountOpenRequest handled;

        @Override
        public String tradeCode() {
            // 请求交易码：分发器用它匹配原始报文中的交易码字段。
            return "5005";
        }

        @Override
        public Class<DemoAccountOpenRequest> requestClass() {
            return DemoAccountOpenRequest.class;
        }

        @Override
        public Class<DemoAccountOpenResponse> responseClass() {
            return DemoAccountOpenResponse.class;
        }

        @Override
        public DemoAccountOpenResponse handle(DemoAccountOpenRequest request) {
            this.handled = request;
            DemoAccountOpenResponse response = new DemoAccountOpenResponse();
            // 真实业务里通常会把区位、流水号等上下文字段从请求复制到响应。
            response.areaCode = request.areaCode;
            response.tradeCode = "5105";
            response.resultCode = "0000";
            response.resultMessage = "OK";
            response.systemSerialNo = request.systemSerialNo;
            return response;
        }
    }

    static class DemoAccountQueryHandler implements TradeHandler<DemoAccountQueryRequest, DemoAccountOpenResponse> {
        // 该 handler 在本测试中只参与注册，不会被 5005 报文命中。
        @Override
        public String tradeCode() {
            return "4003";
        }

        @Override
        public Class<DemoAccountQueryRequest> requestClass() {
            return DemoAccountQueryRequest.class;
        }

        @Override
        public Class<DemoAccountOpenResponse> responseClass() {
            return DemoAccountOpenResponse.class;
        }

        @Override
        public DemoAccountOpenResponse handle(DemoAccountQueryRequest request) {
            return new DemoAccountOpenResponse();
        }
    }
}
