package io.github.windtool.tcp.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段顺序注解。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FieldOrder {

    /**
     * 字段在报文中的顺序。
     * <p>
     * 从 0 开始时通常表示总包长字段；业务字段一般按协议字段序号递增。
     */
    int value();

}
