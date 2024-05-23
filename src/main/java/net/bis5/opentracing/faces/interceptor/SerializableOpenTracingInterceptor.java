package net.bis5.opentracing.faces.interceptor;

import java.io.Serializable;

import javax.annotation.Priority;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;

/**
 * @see io.opentracing.contrib.interceptors.OpenTracingInterceptor
 */
@TracedSerializable
@Interceptor
@Priority(value = Interceptor.Priority.LIBRARY_BEFORE + 1)
public class SerializableOpenTracingInterceptor extends OpenTracingInterceptorNoInstanceField implements Serializable {

    @AroundInvoke
    @Override
    public Object wrap(InvocationContext ctx) throws Exception {
        return super.wrap(ctx);
    }
}