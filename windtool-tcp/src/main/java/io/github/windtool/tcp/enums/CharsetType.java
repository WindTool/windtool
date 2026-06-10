package io.github.windtool.tcp.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.nio.charset.Charset;

/**
 * 报文编码类型枚举
 *
 * @author AprilWind
 */
@Getter
@AllArgsConstructor
public enum CharsetType {

    /**
     * GBK 编码，常用于中文字符的报文
     */
    GBK("GBK"),

    /**
     * UTF-8 编码，通用的 Unicode 编码格式，兼容多语言字符
     */
    UTF8("UTF-8"),

    /**
     * ISO-8859-1 编码，单字节编码，常用于英文及西欧字符
     */
    ISO8859_1("ISO-8859-1");

    /**
     * 编码名称
     */
    private final String name;

    /**
     * 获取对应的 Charset 对象
     *
     * @return Charset 实例
     */
    public Charset getCharset() {
        return Charset.forName(name);
    }

}
