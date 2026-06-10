package io.github.windtool.tcp.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 报文字段分隔符配置注解。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MessageDelimiter {

    /**
     * 是否启用字段分隔符。
     */
    boolean enabled() default false;

    /**
     * 字段分隔符字符。
     */
    char value() default '|';

    /**
     * 报文体最后一个字段后是否继续追加分隔符。
     * <p>
     * true 表示字段之间和报文体末尾都追加分隔符，例如 {@code a|b|}；
     * false 表示只在字段之间追加分隔符，例如 {@code a|b}。
     */
    boolean appendEnd() default true;

}
