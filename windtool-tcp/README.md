# WindTool TCP

`windtool-tcp` 是 WindTool 的 TCP 协议模块，提供定长与分隔符报文编解码、短连接 TCP 客户端/服务端、交易码分发，以及 Spring Boot 自动装配能力。模块不绑定任何具体业务协议；用户自己的 BO/VO、交易码、错误码和业务处理器都应放在用户项目中。

## Maven 坐标

推荐配合根项目的 `windtool-bom` 使用：

```xml
<dependency>
    <groupId>io.github.windtool</groupId>
    <artifactId>windtool-tcp</artifactId>
</dependency>
```

模块对外包名统一为 `io.github.windtool.tcp.*`。

## 环境要求

- JDK 21+
- Maven 3.8+
- Spring Boot 4.0.6

## Spring Boot 配置

客户端配置：

```yaml
netty:
  client:
    enabled: true
    host: 127.0.0.1
    port: 9000
    connect-timeout-millis: 10000
    read-timeout-millis: 60000
    max-body-length: 10485760
```

服务端配置：

```yaml
netty:
  server:
    enabled: true
    host: 0.0.0.0
    port: 9001
    backlog: 128
    read-timeout-millis: 60000
    max-body-length: 10485760
```

## 报文对象

请求和响应类使用注解描述协议结构。下面示例是一个带报头、分隔符和 MD5 字段的定长报文：

```java
import io.github.windtool.tcp.annotation.FieldOrder;
import io.github.windtool.tcp.annotation.FixedField;
import io.github.windtool.tcp.annotation.FixedMessage;
import io.github.windtool.tcp.annotation.Md5Field;
import io.github.windtool.tcp.annotation.MessageDelimiter;
import io.github.windtool.tcp.annotation.MessageHeader;
import lombok.Data;

@Data
@FixedMessage
@MessageHeader(includeSelf = true)
@MessageDelimiter(enabled = true)
public class AccountOpenRequest {

    @FieldOrder(1)
    @FixedField(length = 2)
    private String areaCode;

    @FieldOrder(2)
    @FixedField(length = 4)
    private String tradeCode = "5005";

    @FieldOrder(3)
    @FixedField(length = 2)
    private String bankCode;

    @FieldOrder(4)
    @FixedField(length = 20)
    private String unitName;

    @FieldOrder(5)
    @FixedField(length = 12)
    private String accountNo;

    @FieldOrder(6)
    @FixedField(length = 12)
    private String systemSerialNo;

    @FieldOrder(7)
    @FixedField(length = 32)
    @Md5Field(startOrder = 1, endOrder = 6)
    private String md5Verify;
}
```

响应类同样只描述协议字段：

```java
@Data
@FixedMessage
@MessageHeader(includeSelf = true)
@MessageDelimiter(enabled = true)
public class AccountOpenResponse {

    @FieldOrder(1)
    @FixedField(length = 2)
    private String areaCode;

    @FieldOrder(2)
    @FixedField(length = 4)
    private String tradeCode;

    @FieldOrder(3)
    @FixedField(length = 4)
    private String resultCode;

    @FieldOrder(4)
    @FixedField(length = 20)
    private String resultMessage;

    @FieldOrder(5)
    @FixedField(length = 12)
    private String systemSerialNo;

    @FieldOrder(6)
    @FixedField(length = 32)
    @Md5Field(startOrder = 1, endOrder = 5)
    private String md5Verify;
}
```

常用注解：

- `@FixedMessage`：标记一个报文实体。
- `@MessageHeader`：声明报头长度和报头长度值是否包含报头自身。
- `@MessageDelimiter`：声明字段分隔符。
- `@MessageCharset`：声明链路字符集和系统字符集。
- `@MessageLength`：声明字段长度按字节还是字符计算。
- `@FieldOrder`：声明字段顺序。
- `@FixedField`：声明字段长度、补齐方式、是否计入报头长度。
- `@Md5Field`：声明 MD5 字段和签名范围。
- `@ListField`：声明循环明细字段。
- `@FieldSpel`：声明字段编码/解码转换表达式。

## 客户端发送

启用 `netty.client.enabled=true` 后，注入 `NettyClient` 即可发送短连接请求：

```java
import io.github.windtool.tcp.core.NettyClient;
import org.springframework.stereotype.Service;

@Service
public class AccountOpenClient {

    private final NettyClient nettyClient;

    public AccountOpenClient(NettyClient nettyClient) {
        this.nettyClient = nettyClient;
    }

    public AccountOpenResponse send(AccountOpenRequest request) {
        return nettyClient.send(request, AccountOpenResponse.class);
    }
}
```

`NettyClient` 每次调用都会新建 Socket、发送报文、读取响应并关闭连接。

## 单交易服务端

如果一个服务端处理器只对应一种请求类型，实现 `NettyServerHandler` 即可：

```java
import io.github.windtool.tcp.handler.NettyServerHandler;
import org.springframework.stereotype.Component;

@Component
public class AccountOpenServerHandler implements NettyServerHandler<AccountOpenRequest> {

    @Override
    public Class<AccountOpenRequest> requestClass() {
        return AccountOpenRequest.class;
    }

    @Override
    public AccountOpenResponse handle(AccountOpenRequest request) {
        AccountOpenResponse response = new AccountOpenResponse();
        response.setAreaCode(request.getAreaCode());
        response.setTradeCode("5105");
        response.setResultCode("0000");
        response.setResultMessage("OK");
        response.setSystemSerialNo(request.getSystemSerialNo());
        return response;
    }
}
```

## 多交易码分发

当同一个 TCP 端口承载多个交易码时，实现 `TradeHandler`。只要 Spring 容器中存在一个或多个 `TradeHandler` Bean，自动装配会创建 `TradeDispatchServerHandler`，并把这些 handler 按交易码注册进去。

```java
import io.github.windtool.tcp.trade.TradeHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@RequiredArgsConstructor
@Service
public class AccountOpenHandler implements TradeHandler<AccountOpenRequest, AccountOpenResponse> {

    @Override
    public String tradeCode() {
        return "5005";
    }

    @Override
    public Class<AccountOpenRequest> requestClass() {
        return AccountOpenRequest.class;
    }

    @Override
    public Class<AccountOpenResponse> responseClass() {
        return AccountOpenResponse.class;
    }

    @Override
    public AccountOpenResponse handle(AccountOpenRequest request) {
        log.info("收到开户推送请求：{}", request);

        AccountOpenResponse response = new AccountOpenResponse();
        response.setAreaCode(request.getAreaCode());
        response.setTradeCode("5105");
        response.setResultCode("0000");
        response.setResultMessage("OK");
        response.setSystemSerialNo(request.getSystemSerialNo());
        return response;
    }
}
```

继续增加交易时，只需要继续增加 handler：

```java
@Service
public class AccountQueryHandler implements TradeHandler<AccountQueryRequest, AccountQueryResponse> {

    @Override
    public String tradeCode() {
        return "4003";
    }

    @Override
    public Class<AccountQueryRequest> requestClass() {
        return AccountQueryRequest.class;
    }

    @Override
    public Class<AccountQueryResponse> responseClass() {
        return AccountQueryResponse.class;
    }

    @Override
    public AccountQueryResponse handle(AccountQueryRequest request) {
        AccountQueryResponse response = new AccountQueryResponse();
        response.setAreaCode(request.getAreaCode());
        response.setTradeCode("4103");
        response.setResultCode("0000");
        return response;
    }
}
```

启动时如果两个 handler 返回相同交易码，框架会直接抛出异常，避免运行期分发歧义。

## 默认分发规则

`TradeDispatchServerHandler` 的默认配置适合这类报文：

```text
报头 + 区位代码 + | + 交易码 + | + 其他字段...
```

默认规则为：

- 读取 TCP 帧：4 位长度报头，长度包含报头自身。
- 分隔符报文：第 2 个字段是交易码。
- 固定长度报文：从 body offset `2` 读取 `4` 字节交易码。
- 已解码请求对象：`@FieldOrder(2)` 且标注 `@FixedField` 的字段是交易码。

如果用户协议不同，优先声明一个自己的 `TradeDispatchOptions` Bean：

```java
import io.github.windtool.tcp.core.MessageConfig;
import io.github.windtool.tcp.enums.Align;
import io.github.windtool.tcp.enums.CharsetType;
import io.github.windtool.tcp.enums.LengthType;
import io.github.windtool.tcp.enums.PadType;
import io.github.windtool.tcp.trade.TradeDispatchOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TcpTradeConfig {

    @Bean
    public TradeDispatchOptions tradeDispatchOptions() {
        MessageConfig frameMessage = new MessageConfig(
            Object.class,
            CharsetType.UTF8,
            CharsetType.UTF8,
            LengthType.BYTES,
            8,
            false,
            PadType.SPACE,
            Align.LEFT,
            false,
            '|',
            true
        );
        return new TradeDispatchOptions(
            frameMessage,
            2,
            2,
            4,
            2,
            false
        );
    }
}
```

如果需要完全接管分发器，也可以自己声明 `TradeDispatchServerHandler` Bean；自动装配会让出默认实现。

## 错误回包

通用分发器不内置任何业务错误码或错误响应模型。推荐两种方式：

- 简单场景：业务失败时在 `handle()` 中返回失败响应对象。
- 协议级异常回包：自定义 `NettyServerErrorHandler`，或自行提供定制的 `NettyServerHandler#errorResponse` 逻辑。

这样可以避免 `windtool-tcp` 绑定某个中心平台、银行或监管协议的业务字段。

## 自动装配覆盖

`windtool-tcp` 默认会注册：

- `MessageEncoder`
- `MessageDecoder`
- `NettyServerErrorHandler`
- `TradeDispatchOptions`
- 存在 `TradeHandler` Bean 时注册 `TradeDispatchServerHandler`
- `netty.client.enabled=true` 时注册 `NettyClient`
- `netty.server.enabled=true` 时注册 `NettyServer`

如果用户自己声明同类型 Bean，自动装配会优先使用用户 Bean。

## 测试建议

建议在业务项目中至少覆盖：

- 请求对象编码后能被 `TradeDispatchServerHandler` 按交易码识别。
- 原始报文 body 能解码成正确请求对象。
- handler 能把请求公共字段复制到响应。
- 响应对象编码后包含正确应答交易码、返回码和流水号。
- 重复交易码注册会失败。

`windtool-tcp` 自身提供了 `TradeDispatchIntegrationTest`，演示完整链路：编码请求、分发、解码、调用 handler、编码响应。
