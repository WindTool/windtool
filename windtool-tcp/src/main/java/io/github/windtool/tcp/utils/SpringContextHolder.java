package io.github.windtool.tcp.utils;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * Minimal Spring context bridge for optional static helper APIs.
 *
 * @author AprilWind
 */
public class SpringContextHolder implements ApplicationContextAware, BeanFactoryAware {

    private static volatile ApplicationContext applicationContext;
    private static volatile BeanFactory beanFactory;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        SpringContextHolder.applicationContext = applicationContext;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        SpringContextHolder.beanFactory = beanFactory;
    }

    public static BeanFactory getBeanFactory() {
        return beanFactory;
    }

    public static <T> T getBean(Class<T> type) {
        if (applicationContext == null) {
            throw new IllegalStateException("Spring ApplicationContext is not ready");
        }
        return applicationContext.getBean(type);
    }
}

