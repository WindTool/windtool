package io.github.windtool.tcp.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段 SpEL 注解。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FieldSpel {

    /**
     * 编码前执行的 SpEL 表达式。
     * <p>
     * 表达式中可通过 {@code #value} 引用当前字段原值，返回值会继续参与字段编码。
     */
    String encode() default "";

    /**
     * 解码后执行的 SpEL 表达式。
     * <p>
     * 表达式中可通过 {@code #value} 引用字段解码值，返回值会写入目标对象。
     */
    String decode() default "";

}
