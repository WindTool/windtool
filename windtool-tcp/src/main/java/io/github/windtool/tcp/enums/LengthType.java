package io.github.windtool.tcp.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 报文体长度计算方式枚举
 *
 * @author AprilWind
 */
@Getter
@AllArgsConstructor
public enum LengthType {

    /**
     * 按字节长度计算报文体长度
     */
    BYTES,

    /**
     * 按字符长度计算报文体长度
     */
    CHARACTERS;

}
