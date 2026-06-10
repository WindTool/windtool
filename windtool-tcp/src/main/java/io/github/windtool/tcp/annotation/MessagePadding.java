package io.github.windtool.tcp.annotation;

import io.github.windtool.tcp.enums.Align;
import io.github.windtool.tcp.enums.PadType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 报文头补齐配置注解。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MessagePadding {

    /**
     * 报文头补齐字符类型。
     */
    PadType padType() default PadType.SPACE;

    /**
     * 报文头长度值的补齐方向。
     */
    Align align() default Align.LEFT;

}
