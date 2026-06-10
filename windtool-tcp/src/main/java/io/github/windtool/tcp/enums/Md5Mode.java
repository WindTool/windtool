package io.github.windtool.tcp.enums;

/**
 * MD5 字段处理模式。
 */
public enum Md5Mode {

    /**
     * 编码时生成 MD5。
     */
    ENCODE,

    /**
     * 解码时验证 MD5。
     */
    DECODE,

    /**
     * 编码生成，解码验证。
     */
    BOTH;

    /**
     * 判断当前模式是否需要在编码时生成 MD5 字段。
     *
     * @return true 表示编码时生成 MD5
     */
    public boolean encode() {
        return this == ENCODE || this == BOTH;
    }

    /**
     * 判断当前模式是否需要在解码时验证 MD5 字段。
     *
     * @return true 表示解码时验证 MD5
     */
    public boolean decode() {
        return this == DECODE || this == BOTH;
    }

}
