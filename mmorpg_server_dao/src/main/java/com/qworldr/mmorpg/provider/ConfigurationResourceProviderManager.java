package com.qworldr.mmorpg.provider;

import com.qworldr.mmorpg.enu.ReaderType;
import com.qworldr.mmorpg.meta.ResourceFormat;
import com.qworldr.mmorpg.meta.ResourceMetaData;
import com.qworldr.mmorpg.reader.ReaderManager;
import com.qworldr.mmorpg.utils.ReflectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StopWatch;
import org.springframework.util.StringUtils;

import javax.persistence.Id;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @author wujizhen
 * 资源提供者管理类。随Spring容器启动，为所有不存在ResourceProvider的Resource类(@Resource注解标注的类就是资源类)创建对应的ResourceProvider并注册进
 * Spring容器。ResourceProvider可以通过@Autowire进行注入使用，通过参数泛型进行区分。
 */
public class ConfigurationResourceProviderManager implements InitializingBean, InstantiationAwareBeanPostProcessor, PriorityOrdered, BeanFactoryAware {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationResourceProviderManager.class);
    public static final String CLASSPATH="classpath*:";
    public static final String CLASS = "/*.class";
    public static final Class provideClass = ConfigurationResourceProvider.class;
    public static final String RESOURCE_META_DATA = "resourceMetaData";
    private String suffix;
    private String path;
    private ConfigurableListableBeanFactory beanFactory;
    private String scanPackage;
    private ReaderType readerType;

    /**
     * 为了让afterPropertiesSet在Spring autowire之前执行
     *
     * @return
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 3;
    }

    /**
     *  读取资源类并加载资源，生成资源提供者对象代理，注册进Spring。可以通过@Autowire注入其他地方使用。
     * @throws Exception
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        //先初始化Readermanager;
        ReaderManager bean = beanFactory.getBean(ReaderManager.class);
        bean.init();
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        ResourceFormat resourceFormat = new ResourceFormat(suffix, path, readerType);
        //反射注入resourceMeta 和reader
        Field resourceMetaDataField = ReflectionUtils.findField(provideClass,RESOURCE_META_DATA);
        resourceMetaDataField.setAccessible(true);
        Map<String, ResourceProvider> beansOfType = beanFactory.getBeansOfType(ResourceProvider.class);
        Set<Class> classes = new HashSet<>();
        ExecutorService executorService = Executors.newFixedThreadPool(4 * Runtime.getRuntime().availableProcessors());
        for (ResourceProvider resourceProvider : beansOfType.values()) {
            Class<?> genericType = ReflectUtils.getGenericType(resourceProvider.getClass());
            if (genericType != null) {
                classes.add(genericType);
                injectResourceMetaData(resourceFormat, resourceMetaDataField, genericType, resourceProvider);
                executorService.submit(resourceProvider::reload);
            }
        }
        if (StringUtils.isEmpty(scanPackage)) {
            throw new IllegalArgumentException("缺少scanPackage参数");
        }
        //扫包资源类，加载资源文件
        ResourcePatternResolver patternResolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory(patternResolver);
        Resource[] resources = patternResolver.getResources(CLASSPATH+scanPackage.replaceAll("\\.", "/")+ CLASS);
        MetadataReader metadataReader;
        Class resourceClass;
        Class keyClass=null;
        ResourceProvider resourceProviderProxy;
        for (Resource resource : resources) {
            metadataReader = metadataReaderFactory.getMetadataReader(resource);
            if (!metadataReader.getAnnotationMetadata().hasAnnotation(com.qworldr.mmorpg.anno.Resource.class.getName())) {
                continue;
            }
            resourceClass=Class.forName(metadataReader.getClassMetadata().getClassName());
            //已有实现类不需要代理。
            if(classes.contains(resourceClass)){
                continue;
            }
            Field[] declaredFields = resourceClass.getDeclaredFields();
            for (Field declaredField : declaredFields) {
                if(declaredField.getAnnotation(Id.class)!=null){
                    keyClass=declaredField.getType();
                    break;
                }
            }
            if(keyClass==null){
                throw new IllegalArgumentException(String.format("%s资源类没有id字段，请通过@Id标识id字段",resourceClass.getName()));
            }
            resourceProviderProxy = (ResourceProvider) beanFactory.createBean(ProviderProxyFactory.getInstance().defineGenericClass(provideClass.getName(), resourceClass,ReflectUtils.wrapType(keyClass)), AutowireCapableBeanFactory.AUTOWIRE_BY_TYPE, false);
            //给provider注入ResourceMetaData
            injectResourceMetaData(resourceFormat, resourceMetaDataField, resourceClass, resourceProviderProxy);
            //注册进Spring容器
            this.beanFactory.registerSingleton(resourceProviderProxy.getClass().getName(),resourceProviderProxy);
            executorService.submit(resourceProviderProxy::reload);
        }
        // 等待任务执行完毕后关闭线程池。
        executorService.shutdown();
        boolean flag;
        do {
            flag = executorService.awaitTermination(2000, TimeUnit.MILLISECONDS);
        }while(!flag);

        stopWatch.stop();
        LOGGER.debug("资源加载完毕，耗时{}s", stopWatch.getTotalTimeSeconds());
        //TODO 热更
    }

    private void injectResourceMetaData(ResourceFormat resourceFormat, Field resourceMetaDataField, Class resourceClass, ResourceProvider resourceProviderProxy) {
        ResourceMetaData resourceMetaData = ResourceMetaData.valueOf(resourceClass, resourceFormat);
        try {
            resourceMetaDataField.set(resourceProviderProxy, resourceMetaData);
        } catch (IllegalAccessException e) {
            LOGGER.error("resourceProvider注入失败", e);
        }
    }

    public void setReaderType(ReaderType readerType) {
        this.readerType = readerType;
    }

    public String getSuffix() {
        return suffix;
    }

    public void setSuffix(String suffix) {
        this.suffix = suffix;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getScanPackage() {
        return scanPackage;
    }

    public void setScanPackage(String scanPackage) {
        this.scanPackage = scanPackage;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
    }
}
