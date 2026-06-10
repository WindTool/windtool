package io.github.windtool.tcp.annotation;

import io.github.windtool.tcp.enums.LengthType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 报文字段长度单位配置注解。
 * <p>
 * 用于声明 {@link FixedField#length()} 按字节还是按字符解释，默认按字节兼容历史协议。
 *
 * @author AprilWind
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MessageLength {

    /**
     * 固定字段长度单位。
     */
    LengthType value() default LengthType.BYTES;

}
