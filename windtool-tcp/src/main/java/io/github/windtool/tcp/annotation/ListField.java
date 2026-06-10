package io.github.windtool.tcp.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 循环体字段注解。
 * <p>
 * 标注在 {@link java.util.List} 字段上，表示该字段由若干条相同结构的明细记录拼接而成。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ListField {
}
