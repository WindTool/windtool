# WindTool

WindTool 是一个个人开源 Java 工具集

## 模块

- [`windtool-bom`](./windtool-bom/pom.xml)：WindTool 版本管理 BOM，统一管理各模块版本。
- [`windtool-core`](./windtool-core/pom.xml)：核心工具模块，作为公共工具、常量、基础抽象的承载模块。
- [`windtool-tcp`](./windtool-tcp/README.md)：TCP 报文编解码与 Spring Boot 自动装配模块，支持固定长度字段、分隔符字段、报文头长度、MD5 字段、SpEL 字段转换、客户端发送和服务端交易分发。

## Maven 坐标

推荐先引入 `windtool-bom` 统一管理版本：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.windtool</groupId>
            <artifactId>windtool-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

然后按需引入模块：

```xml
<dependency>
    <groupId>io.github.windtool</groupId>
    <artifactId>windtool-core</artifactId>
</dependency>
```

```xml
<dependency>
    <groupId>io.github.windtool</groupId>
    <artifactId>windtool-tcp</artifactId>
</dependency>
```

## 开源协议

WindTool 使用 Apache License 2.0 协议开源。
