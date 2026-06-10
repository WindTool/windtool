package io.github.windtool.tcp.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 报文头配置注解。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MessageHeader {

    /**
     * 报文头长度，单位为字节。
     */
    int length() default 4;

    /**
     * 报文头中的长度值是否包含报文头自身和报头后的分隔符。
     */
    boolean includeSelf() default false;

}
