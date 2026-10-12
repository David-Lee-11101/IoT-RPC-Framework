package spring.service;

import protocol.ProtocolRequest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Mediator {
    public static Map<String,BeanMethod> beanMethodMap=new ConcurrentHashMap<>();

    private volatile static Mediator instance=null;

    private Mediator(){
    }

    // Use double-checked locking for the singleton mediator.
    public static Mediator getInstance(){
        if(instance==null){
            synchronized (Mediator.class){
                if(instance==null){
                    instance=new Mediator();
                }
            }
        }
        return instance;
    }
    // The mediator stores explicitly registered provider methods.
    public Object processor(ProtocolRequest rpcRequest){
        String key=rpcRequest.getClassName()+"."+rpcRequest.getMethodName();
        BeanMethod beanMethod=beanMethodMap.get(key);
        if(beanMethod==null){
            throw new IllegalArgumentException("No service method registered for " + key);
        }
        Object bean=beanMethod.getBean();
        Method method=beanMethod.getMethod();
        try {
            return method.invoke(bean,rpcRequest.getParams());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access service method " + key, e);
        } catch (InvocationTargetException e) {
            Throwable target = e.getTargetException();
            throw new IllegalStateException("Service method failed: " + key, target);
        }
    }
}
