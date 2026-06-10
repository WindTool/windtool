package io.github.windtool.tcp.config;

import io.github.windtool.tcp.core.MessageConfig;
import io.github.windtool.tcp.enums.Align;
import io.github.windtool.tcp.enums.CharsetType;
import io.github.windtool.tcp.enums.LengthType;
import io.github.windtool.tcp.enums.PadType;
import io.github.windtool.tcp.handler.MessageDecoder;
import io.github.windtool.tcp.handler.MessageEncoder;
import io.github.windtool.tcp.trade.TradeDispatchOptions;
import io.github.windtool.tcp.trade.TradeDispatchServerHandler;
import io.github.windtool.tcp.trade.TradeHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NettyConfig} 的 Spring Boot 自动装配测试。
 * <p>
 * 使用 {@link ApplicationContextRunner} 验证用户只引入依赖时默认 Bean 是否能创建，
 * 以及用户自定义 Bean 时自动装配是否会正确让出。
 */
class NettyConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NettyConfig.class));

    @Test
    void createsDefaultInfrastructureBeans() {
        // 没有业务 TradeHandler 时，只创建基础设施和默认分发配置，不创建交易分发器。
        contextRunner.run(context -> assertThat(context)
            .hasSingleBean(MessageEncoder.class)
            .hasSingleBean(MessageDecoder.class)
            .hasSingleBean(TradeDispatchOptions.class)
            .doesNotHaveBean(TradeDispatchServerHandler.class));
    }

    @Test
    void createsTradeDispatchServerHandlerWhenTradeHandlerExists() {
        // 用户项目只要声明 TradeHandler Bean，自动装配就会聚合生成 TradeDispatchServerHandler。
        contextRunner
            .withUserConfiguration(DemoTradeHandlerConfiguration.class)
            .run(context -> assertThat(context)
                .hasSingleBean(TradeDispatchServerHandler.class));
    }

    @Test
    void usesCustomTradeDispatchOptions() {
        // 用户自定义 TradeDispatchOptions 后，默认分发器必须使用用户提供的帧规则。
        contextRunner
            .withUserConfiguration(DemoTradeHandlerConfiguration.class, CustomTradeDispatchOptionsConfiguration.class)
            .run(context -> {
                TradeDispatchServerHandler handler = context.getBean(TradeDispatchServerHandler.class);
                MessageConfig frameMessage = handler.frameMessage();
                assertThat(frameMessage.headerLength()).isEqualTo(8);
                assertThat(frameMessage.useDelimiter()).isFalse();
            });
    }

    @Test
    void backsOffWhenUserDefinesInfrastructureBeans() {
        // 公共库不能强行覆盖用户 Bean；编码器、解码器、分发器都应允许用户完全接管。
        contextRunner
            .withUserConfiguration(CustomInfrastructureConfiguration.class, DemoTradeHandlerConfiguration.class)
            .run(context -> {
                assertThat(context.getBean(MessageEncoder.class)).isSameAs(CustomInfrastructureConfiguration.ENCODER);
                assertThat(context.getBean(MessageDecoder.class)).isSameAs(CustomInfrastructureConfiguration.DECODER);
                assertThat(context.getBean(TradeDispatchServerHandler.class)).isSameAs(CustomInfrastructureConfiguration.DISPATCHER);
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class DemoTradeHandlerConfiguration {
        @Bean
        TradeHandler<DemoRequest, DemoResponse> demoTradeHandler() {
            // 测试用最小 TradeHandler，只用于触发自动装配条件。
            return new TradeHandler<>() {
                @Override
                public String tradeCode() {
                    return "1001";
                }

                @Override
                public Class<DemoRequest> requestClass() {
                    return DemoRequest.class;
                }

                @Override
                public Class<DemoResponse> responseClass() {
                    return DemoResponse.class;
                }

                @Override
                public DemoResponse handle(DemoRequest request) {
                    return new DemoResponse();
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomTradeDispatchOptionsConfiguration {
        @Bean
        TradeDispatchOptions tradeDispatchOptions() {
            // 使用明显不同于默认值的 8 位无分隔符报头，便于断言自定义配置已生效。
            return new TradeDispatchOptions(
                new MessageConfig(
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
                ),
                2,
                2,
                4,
                2,
                false
            );
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomInfrastructureConfiguration {
        // 使用静态实例便于断言容器中的 Bean 就是用户提供的同一个对象。
        static final MessageEncoder ENCODER = new MessageEncoder();
        static final MessageDecoder DECODER = new MessageDecoder();
        static final TradeDispatchServerHandler DISPATCHER = new TradeDispatchServerHandler(List.of());

        @Bean
        MessageEncoder messageEncoder() {
            return ENCODER;
        }

        @Bean
        MessageDecoder messageDecoder() {
            return DECODER;
        }

        @Bean
        TradeDispatchServerHandler tradeDispatchServerHandler() {
            return DISPATCHER;
        }
    }

    static class DemoRequest {
    }

    static class DemoResponse {
    }
}
