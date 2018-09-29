package com.qworldr.dispatcher;

import com.google.common.collect.Maps;
import com.qworldr.annotation.Protocal;
import com.qworldr.annotation.SocketController;
import com.qworldr.annotation.SocketRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.util.Map;

@Component
public class InvokerManager implements BeanPostProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(InvokerManager.class);
    //spring初始化结束后就不会再写只读，应该没线程安全问题
    private Map<Class, InvokerDefinition> protocal2Invoker = Maps.newHashMap();

    public InvokerDefinition getInvokerDefintion(Class protocal) {
        return protocal2Invoker.get(protocal);
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        Class<?> aClass = bean.getClass();
        SocketController annotation = aClass.getAnnotation(SocketController.class);
        if (annotation == null) {
            return bean;
        }
        InvokerDefinition invokerDefinition;
        Object instance;
        try {
            instance = aClass.newInstance();
        } catch (IllegalAccessException e) {
            LOGGER.debug("类 {} 构造函数无权访问", aClass.getName());
            return bean;
        } catch (InstantiationException e) {
            LOGGER.debug("类 {} 实例化失败", aClass.getName());
            e.printStackTrace();
            return bean;
        }
        ReflectionUtils.doWithMethods(aClass, method -> {
            Class<?>[] parameterTypes = method.getParameterTypes();
            Class protoType = null;
            for (Class type : parameterTypes) {
                if (type.getAnnotation(Protocal.class) != null) {
                    protoType = type;
                    break;
                }
            }
            if (protoType == null) {
                LOGGER.debug("{}的{}方法没有协议参数,不是SocketRequest", aClass.getName(), method.getName());
                return;
            }
            protocal2Invoker.put(protoType, new InvokerDefinition(instance, method));
        }, method -> method.getAnnotation(SocketRequest.class) != null);
        return null;
    }
}
