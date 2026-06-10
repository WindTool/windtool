package io.github.windtool.tcp.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 固定报文实体标记注解。
 * <p>
 * 报头、字符集、分隔符、补齐等配置使用独立类级注解声明。
 *
 * @author AprilWind
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface FixedMessage {

}
