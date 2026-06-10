package io.github.windtool.tcp.annotation;

import io.github.windtool.tcp.enums.CharsetType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 报文字符集配置注解。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MessageCharset {

    /**
     * 报文在 TCP 链路上传输时使用的字符集。
     */
    CharsetType target() default CharsetType.UTF8;

    /**
     * 系统内部字符串转换为报文字节前使用的字符集。
     */
    CharsetType system() default CharsetType.UTF8;

}
