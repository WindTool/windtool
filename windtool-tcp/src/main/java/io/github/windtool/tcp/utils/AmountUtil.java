package io.github.windtool.tcp.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额工具类
 *
 * <p>用于解析固定长度报文中的金额字段，支持：
 * <ul>
 *     <li>前置或后置空格</li>
 *     <li>正负号</li>
 *     <li>BigDecimal 精度解析</li>
 * </ul>
 * </p>
 *
 * @author AprilWind
 */
public class AmountUtil {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * 将报文金额字符串解析为 BigDecimal
     *
     * @param amountStr 报文金额字符串，例如 "+            300.19" 或 "-            300.19"
     * @return 解析后的 BigDecimal
     */
    public static BigDecimal parseToBigDecimal(String amountStr) {
        if (amountStr == null || amountStr.isEmpty()) {
            return BigDecimal.ZERO;
        }
        // 去掉所有空格
        String cleaned = amountStr.replaceAll("\\s+", "");
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("解析金额失败: " + amountStr, e);
        }
    }

    /**
     * 将报文金额字符串解析为 double
     *
     * @param amountStr 报文金额字符串
     * @return 解析后的 double
     */
    public static double parseToDouble(String amountStr) {
        return parseToBigDecimal(amountStr).doubleValue();
    }

    /**
     * 将报文金额字符串解析为 long（单位最小货币单位，例如分）
     *
     * @param amountStr 报文金额字符串
     * @return 解析后的 long
     */
    public static long parseToLong(String amountStr) {
        BigDecimal bd = parseToBigDecimal(amountStr);
        return bd.longValue();
    }

    /**
     * 判断金额是否为负数
     *
     * @param amountStr 报文金额字符串
     * @return true 如果金额小于0
     */
    public static boolean isNegative(String amountStr) {
        return parseToBigDecimal(amountStr).signum() < 0;
    }

    /**
     * 判断金额是否为正数
     *
     * @param amountStr 报文金额字符串
     * @return true 如果金额大于0
     */
    public static boolean isPositive(String amountStr) {
        return parseToBigDecimal(amountStr).signum() > 0;
    }

    /**
     * 元转分（BigDecimal）
     *
     * @param yuan 金额（单位：元）
     * @return 分
     */
    public static long yuanToFen(BigDecimal yuan) {
        if (yuan == null) {
            return 0L;
        }
        return yuan.multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    /**
     * 元转分（String）
     *
     * @param yuanStr 元金额字符串，例如 "+300.19"
     * @return 分
     */
    public static long yuanToFen(String yuanStr) {
        return yuanToFen(parseToBigDecimal(yuanStr));
    }

    /**
     * 分转元（BigDecimal）
     *
     * @param fen 金额（单位：分）
     * @return 元
     */
    public static BigDecimal fenToYuan(long fen) {
        return BigDecimal.valueOf(fen).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /**
     * 分转元（BigDecimal）
     *
     * @param fen 金额（单位：分）
     * @return 元
     */
    public static BigDecimal fenToYuan(BigDecimal fen) {
        if (fen == null) {
            return BigDecimal.ZERO;
        }
        return fen.divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /**
     * 分转元（String）
     *
     * @param fen 金额（单位：分）
     * @return 元
     */
    public static String fenToYuanStr(long fen) {
        return fenToYuan(fen).toPlainString();
    }

    /**
     * 将金额格式化为 15 位字符串（12 整 + '.' + 2 小）
     *
     * @param amount 金额
     * @return 总长度 15 的字符串
     */
    public static String formatAmount(BigDecimal amount) {
        // 1. 四舍五入保留 2 位
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);

        // 2. 拆整数部分和小数部分
        long integerPart = scaled.longValue();
        int fractionalPart = scaled.remainder(BigDecimal.ONE).movePointRight(2).intValue();

        // 3. 格式化
        return String.format("%012d.%02d", integerPart, fractionalPart);
    }

    /**
     * 将金额格式化为金融报文常用的 9(14)V9(2) 格式（不带小数点）
     *
     * @param amount 金额
     * @return 16 位字符串（14 位整数 + 2 位小数，左补零）
     */
    public static String formatAmountV9(BigDecimal amount) {
        // 四舍五入保留 2 位
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        // 转换为整数（隐含小数）
        long value = scaled.multiply(BigDecimal.valueOf(100)).longValueExact();
        // 左补零，16 位
        return String.format("%016d", value);
    }

}
