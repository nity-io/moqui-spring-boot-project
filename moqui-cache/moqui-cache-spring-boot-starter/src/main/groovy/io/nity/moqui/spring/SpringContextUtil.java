package io.nity.moqui.spring;

import org.springframework.context.ApplicationContext;

public class SpringContextUtil {
    private static ApplicationContext applicationContext;

    //获取上下文
    public static ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    //设置上下文
    public static void setApplicationContext(ApplicationContext applicationContext) {
        SpringContextUtil.applicationContext = applicationContext;
    }

    /**
     * 通过name获取 Bean.
     *
     * @param name 参数传入要获取的实例的类名 首字母小写，这是默认的
     * @return
     */
    public static Object getBean(String name) {
        return getApplicationContext().getBean(name);
    }

    /**
     * 通过class获取Bean.
     *
     * @param clazz
     * @param <T>
     * @return
     */
    public static <T> T getBean(Class<T> clazz) {
        return getApplicationContext().getBean(clazz);
    }

    /**
     * 通过name,以及Clazz返回指定的Bean
     *
     * @param name
     * @param clazz
     * @param <T>
     * @return
     */
    public static <T> T getBean(String name, Class<T> clazz) {
        return getApplicationContext().getBean(name, clazz);
    }
}
