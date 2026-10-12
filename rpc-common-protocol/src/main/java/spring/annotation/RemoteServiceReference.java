package spring.annotation;


import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE) // Applies to classes and interfaces.
@Retention(RetentionPolicy.RUNTIME) // Retained for runtime service discovery.
@Component
public @interface RemoteServiceReference {
}
