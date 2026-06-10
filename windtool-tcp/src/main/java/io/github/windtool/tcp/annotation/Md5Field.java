package io.github.windtool.tcp.annotation;

import io.github.windtool.tcp.enums.Md5Mode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MD5 验证字段注解。
 * <p>
 * 适用于需要按指定字段范围计算签名的报文字段。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Md5Field {

    /**
     * MD5 字段处理模式。
     */
    Md5Mode mode() default Md5Mode.BOTH;

    /**
     * 参与 MD5 的起始字段顺序。
     */
    int startOrder() default -1;

    /**
     * 参与 MD5 的结束字段顺序。
     */
    int endOrder() default -1;

}
