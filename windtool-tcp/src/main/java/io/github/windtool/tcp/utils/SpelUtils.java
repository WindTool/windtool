package io.github.windtool.tcp.utils;

import cn.hutool.core.util.ObjectUtil;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.expression.BeanResolver;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

/**
 * SpEL 表达式工具类
 *
 * <p>
 * 支持：
 * 1. 原值计算：#value
 * 2. 静态方法调用：T(java.lang.Math).max(...)
 * 3. Spring Bean 调用：@beanName.method(...)
 * 4. 模板表达式：Hello #{#value}
 * </p>
 *
 * @author AprilWind
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SpelUtils {

    /**
     * SpEL 解析器
     */
    private static final ExpressionParser PARSER = new SpelExpressionParser();

    /**
     * Spring Bean 解析器
     */
    private static BeanResolver beanResolver() {
        if (SpringContextHolder.getBeanFactory() == null) {
            return null;
        }
        return new BeanFactoryResolver(SpringContextHolder.getBeanFactory());
    }

    /**
     * 根据 SpEL 表达式计算字段值
     *
     * @param value          当前字段值，在表达式中使用 #value 引用
     * @param spelExpression SpEL 表达式
     * @param contextObject  可选上下文对象，在表达式中可以直接引用属性或方法
     * @param returnType     返回类型
     * @param <T>            返回类型泛型
     * @return 计算后的值，如果表达式为空或计算失败则返回原始值
     */
    @SuppressWarnings("unchecked")
    public static <T> T eval(Object value, String spelExpression, Object contextObject, Class<T> returnType) {
        if (ObjectUtil.isEmpty(spelExpression)) {
            return (T) value;
        }

        try {
            StandardEvaluationContext evalContext = new StandardEvaluationContext(contextObject != null ? contextObject : value);
            evalContext.setVariable("value", value);
            BeanResolver resolver = beanResolver();
            if (resolver != null) {
                evalContext.setBeanResolver(resolver);
            }
            Expression expression = PARSER.parseExpression(spelExpression);
            return expression.getValue(evalContext, returnType);
        } catch (Exception e) {
            throw new RuntimeException("SpEL 表达式计算失败: expression=[" + spelExpression +
                "], value=[" + value + "], context=[" + contextObject + "]", e);
        }
    }

    /**
     * 简化方法，只传入值和表达式，返回原始类型
     *
     * @param value          当前字段值
     * @param spelExpression SpEL 表达式
     * @param <T>            返回类型
     * @return 计算后的值
     */
    @SuppressWarnings("unchecked")
    public static <T> T eval(Object value, String spelExpression) {
        return (T) eval(value, spelExpression, null, Object.class);
    }

}
