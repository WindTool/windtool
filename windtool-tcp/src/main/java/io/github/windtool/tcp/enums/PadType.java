package io.github.windtool.tcp.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 补齐类型
 *
 * @author AprilWind
 */
@Getter
@AllArgsConstructor
public enum PadType {

    /**
     * 补空格（默认）
     */
    SPACE((byte) 0x20),

    /**
     * 补零
     */
    ZERO((byte) 0x30);

    /**
     * 对应的字节值
     */
    private final byte value;

}
