# WindTool TCP

`windtool-tcp` 是 WindTool 的 TCP 协议模块，提供消息编解码、报文与实体互转、消息收发，以及 Spring Boot 自动装配能力。

## Maven 坐标

```xml
<dependency>
    <groupId>io.github.windtool</groupId>
    <artifactId>windtool-tcp</artifactId>
</dependency>
```

## 对外包名

模块对外包名统一为 `io.github.windtool.tcp.*`。

## 环境要求

- JDK 21+
- Spring Boot 4.0.6

## 配置

### 客户端

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

### 服务端

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

## 使用示例

### 定义 TCP 报文模型

```java
import io.github.windtool.tcp.annotation.FieldOrder;
import io.github.windtool.tcp.annotation.FixedField;
import io.github.windtool.tcp.annotation.FixedMessage;
import io.github.windtool.tcp.annotation.MessageDelimiter;
import io.github.windtool.tcp.annotation.MessageHeader;
import lombok.Data;

@Data
@FixedMessage
@MessageHeader(length = 8, includeSelf = true)
@MessageDelimiter(enabled = true, value = '|', appendEnd = true)
public class DemoRequest {

    @FieldOrder(1)
    @FixedField(length = 2)
    private String areaCode;

    @FieldOrder(2)
    @FixedField(length = 4)
    private String tradeCode;

    @FieldOrder(3)
    @FixedField(length = 30)
    private String accountNo;
}
```

### Spring Boot 客户端

```java
import io.github.windtool.tcp.core.NettyClient;
import org.springframework.stereotype.Service;

@Service
public class DemoClientService {

    private final NettyClient nettyClient;

    public DemoClientService(NettyClient nettyClient) {
        this.nettyClient = nettyClient;
    }

    public DemoResponse send(DemoRequest request) {
        return nettyClient.send(request, DemoResponse.class);
    }
}
```

### Spring Boot 服务端

```java
import io.github.windtool.tcp.handler.NettyServerHandler;
import org.springframework.stereotype.Component;

@Component
public class DemoServerHandler implements NettyServerHandler<DemoRequest> {

    @Override
    public Class<DemoRequest> requestClass() {
        return DemoRequest.class;
    }

    @Override
    public DemoResponse handle(DemoRequest request) {
        DemoResponse response = new DemoResponse();
        response.setResultCode("0000");
        return response;
    }
}
```

### 交易码分发

```java
import io.github.windtool.tcp.annotation.FieldOrder;
import io.github.windtool.tcp.annotation.FixedField;
import io.github.windtool.tcp.annotation.FixedMessage;
import io.github.windtool.tcp.annotation.MessageDelimiter;
import io.github.windtool.tcp.trade.TradeHandler;
import lombok.Data;
import org.springframework.stereotype.Component;

@Data
@FixedMessage
@MessageDelimiter(enabled = true, value = '|', appendEnd = true)
public class DemoTradeRequest {

    @FieldOrder(1)
    @FixedField(length = 2)
    private String areaCode;

    @FieldOrder(2)
    @FixedField(length = 4)
    private String tradeCode;
}

@Data
@FixedMessage
@MessageDelimiter(enabled = true, value = '|', appendEnd = true)
public class DemoTradeResponse {

    @FieldOrder(1)
    @FixedField(length = 2)
    private String areaCode;

    @FieldOrder(2)
    @FixedField(length = 4)
    private String tradeCode;

    @FieldOrder(3)
    @FixedField(length = 4)
    private String resultCode;
}

@Component
public class DemoTradeHandler implements TradeHandler<DemoTradeRequest, DemoTradeResponse> {

    @Override
    public String tradeCode() {
        return "1001";
    }

    @Override
    public Class<DemoTradeRequest> requestClass() {
        return DemoTradeRequest.class;
    }

    @Override
    public Class<DemoTradeResponse> responseClass() {
        return DemoTradeResponse.class;
    }

    @Override
    public DemoTradeResponse handle(DemoTradeRequest request) {
        DemoTradeResponse response = new DemoTradeResponse();
        response.setAreaCode(request.getAreaCode());
        response.setTradeCode("1101");
        response.setResultCode("0000");
        return response;
    }
}
```

只要 Spring 容器中存在 `TradeHandler`，`windtool-tcp` 会自动注册 `TradeDispatchServerHandler`。
示例中的请求、响应和交易码只是参考写法；真实业务报文类应放在用户自己的项目中。

通用交易分发器默认不内置业务错误码和错误响应模型。如果需要协议级错误回包，可以自定义
`NettyServerErrorHandler`，或在自己的服务端处理器中覆盖 `NettyServerHandler#errorResponse`。
