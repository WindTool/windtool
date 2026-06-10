package io.github.windtool.tcp.annotation;

import io.github.windtool.tcp.enums.Align;
import io.github.windtool.tcp.enums.PadType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 报文字段结构注解。
 * <p>
 * {@link #length()} 大于等于 0 表示定长字段，长度单位由类级 {@link MessageLength} 决定，默认单位为字节；
 * 小于 0 表示不定长字段，字段边界由分隔符、报文剩余字节或前置长度字段配合决定。
 * 字段顺序、MD5、循环体、SpEL 等扩展能力使用独立注解。
 *
 * @author AprilWind
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FixedField {

    /**
     * 字段长度；单位由类级 {@link MessageLength} 决定，未声明时单位为字节；小于 0 表示不定长字段。
     */
    int length() default 0;

    /**
     * 补齐类型
     */
    PadType padType() default PadType.SPACE;

    /**
     * 补齐方向
     */
    Align align() default Align.LEFT;

    /**
     * 是否计入报头长度。
     * <p>
     * 有些协议报头只统计固定前缀长度，尾部变长文件内容通过前置长度域单独读取，
     * 这类尾部内容字段应设置为 false。
     */
    boolean includeInHeaderLength() default true;

}
