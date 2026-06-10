package io.github.windtool.tcp.model;

/**
 * 不计入报头长度的尾部补读计划。
 *
 * @param suffixStartIndex         不计入报头长度的第一个字段下标
 * @param fixedBytesBeforeVariable 为读取到长度字段/尾部结束前，必须先补读的固定长度字节数
 * @param variableFieldIndex       最后一个不定长字段下标；没有不定长字段时为 -1
 * @author AprilWind
 */
public record TrailingReadPlan(int suffixStartIndex, int fixedBytesBeforeVariable, int variableFieldIndex) {

    /**
     * 创建空补读计划。
     *
     * @return 空补读计划
     */
    public static TrailingReadPlan none() {
        return new TrailingReadPlan(-1, 0, -1);
    }

    /**
     * 判断是否没有任何尾部字段需要补读。
     *
     * @return true 表示不需要补读
     */
    public boolean isEmpty() {
        return suffixStartIndex < 0;
    }

    /**
     * 判断尾部是否包含最后一个不定长字段。
     *
     * @return true 表示需要根据长度字段继续补读不定长内容
     */
    public boolean hasVariableField() {
        return variableFieldIndex >= 0;
    }

}
