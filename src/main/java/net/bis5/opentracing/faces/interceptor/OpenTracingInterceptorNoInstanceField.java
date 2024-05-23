package net.bis5.opentracing.faces.interceptor;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Logger;

import javax.annotation.Priority;
import javax.enterprise.inject.Alternative;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.spi.CDI;
import javax.enterprise.util.TypeLiteral;
import javax.inject.Inject;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;
import javax.ws.rs.Path;

import org.eclipse.microprofile.opentracing.Traced;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.contrib.tracerresolver.TracerResolver;
import io.opentracing.tag.Tags;

/**
 * @see io.opentracing.contrib.interceptors.OpenTracingInterceptor
 */
@Traced
@Interceptor
@Alternative
@Priority(value = Interceptor.Priority.LIBRARY_BEFORE + 1)
public class OpenTracingInterceptorNoInstanceField {
	public static final String SPAN_CONTEXT = "__opentracing_span_context";
    private static final Logger log = Logger.getLogger(OpenTracingInterceptorNoInstanceField.class.getName());
    private static final String tracerSource = System.getProperty("opentracing-faces.tracer.source", "cdi"); // cdi or resolver

    @FunctionalInterface
    private static interface Operation<T, U, V> {
        V apply(T t, U u) throws Exception;
    }

    private Object execWithTracer(Instance<Tracer> tracerInstance, InvocationContext ctx, Operation<Tracer, InvocationContext, Object> op) throws Exception {
        Tracer tracer = tracerInstance.get();
        try {
            return op.apply(tracer, ctx);
        } finally {
            tracerInstance.destroy(tracer);
        }
    }

    @AroundInvoke
    public Object wrap(InvocationContext ctx) throws Exception {
        if (skipJaxRs(ctx.getMethod())) {
            return ctx.proceed();
        }

        if (!traced(ctx.getMethod())) {
            return ctx.proceed();
        }

        Instance<Tracer> tracerInstance = getTracerInstance();
        return execWithTracer(tracerInstance, ctx, this::wrapInternal);
    }

    private Object wrapInternal(Tracer tracer, InvocationContext ctx) throws Exception {
        Tracer.SpanBuilder spanBuilder = tracer.buildSpan(getOperationName(ctx.getMethod()));

        int contextParameterIndex = -1;
        for (int i = 0 ; i < ctx.getParameters().length ; i++) {
            Object parameter = ctx.getParameters()[i];
            if (parameter instanceof SpanContext) {
                log.fine("Found parameter as span context. Using it as the parent of this new span");
                spanBuilder.asChildOf((SpanContext) parameter);
                contextParameterIndex = i;
                break;
            }

            if (parameter instanceof Span) {
                log.fine("Found parameter as span. Using it as the parent of this new span");
                spanBuilder.asChildOf((Span) parameter);
                contextParameterIndex = i;
                break;
            }
        }

        if (contextParameterIndex < 0) {
            log.fine("No parent found. Trying to get span context from context data");
            Object ctxParentSpan = ctx.getContextData().get(SPAN_CONTEXT);
            if (ctxParentSpan instanceof SpanContext) {
                log.fine("Found span context from context data.");
                SpanContext parentSpan = (SpanContext) ctxParentSpan;
                spanBuilder.asChildOf(parentSpan);
            }
        }

        Span span = spanBuilder.start();
        try (Scope scope = tracer.scopeManager().activate(span, false)) {
            log.fine("Adding span context into the invocation context.");
            ctx.getContextData().put(SPAN_CONTEXT, span.context());

            if (contextParameterIndex >= 0) {
                log.fine("Overriding the original span context with our new context.");
                for (int i = 0 ; i < ctx.getParameters().length ; i++) {
                    if (ctx.getParameters()[contextParameterIndex] instanceof Span) {
                        ctx.getParameters()[contextParameterIndex] = span;
                    }

                    if (ctx.getParameters()[contextParameterIndex] instanceof SpanContext) {
                        ctx.getParameters()[contextParameterIndex] = span.context();
                    }
                }
            }

            return ctx.proceed();
        } catch (Exception e) {
            logException(span, e);
            throw e;
        } finally {
            span.finish();
        }
    }

    public Instance<Tracer> getTracerInstance() {
        if (tracerSource.equals("cdi")) {
        return CDI.current().select(Tracer.class);
        } else {
            return new Instance<Tracer>() {

                @Override
                public Iterator<Tracer> iterator() {
                    return Collections.singleton(get()).iterator();
                }

                @Override
                public Tracer get() {
                    return TracerResolver.resolveTracer();
                }

                @Override
                public Instance<Tracer> select(Annotation... qualifiers) {
                    return this;
                }

                @SuppressWarnings("unchecked")
                @Override
                public <U extends Tracer> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
                    return (Instance<U>) this;
                }

                @SuppressWarnings("unchecked")
                @Override
                public <U extends Tracer> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
                    return (Instance<U>) this;
                }

                @Override
                public boolean isUnsatisfied() {
                    return false;
                }

                @Override
                public boolean isAmbiguous() {
                    return false;
                }

                @Override
                public void destroy(Tracer instance) {
                    // no op
                }
            };
        }
    }

    private boolean traced(Method method) {
        TracedSerializable classTraced = method.getDeclaringClass().getAnnotation(TracedSerializable.class);
        TracedSerializable methodTraced = method.getAnnotation(TracedSerializable.class);
        if (methodTraced != null) {
            return methodTraced.value();
        }
        return classTraced != null && classTraced.value();
    }

    private boolean skipJaxRs(Method method) {
        return method.getAnnotation(Path.class) != null ||
                method.getDeclaringClass().getAnnotation(Path.class) != null;
    }

    private String getOperationName(Method method) {
        TracedSerializable classTraced = method.getDeclaringClass().getAnnotation(TracedSerializable.class);
        TracedSerializable methodTraced = method.getAnnotation(TracedSerializable.class);
        if (methodTraced != null && methodTraced.operationName().length() > 0) {
            return methodTraced.operationName();
        } else if (classTraced != null && classTraced.operationName().length() > 0) {
            return classTraced.operationName();
        }
        return String.format("%s.%s", method.getDeclaringClass().getName(), method.getName());
    }

    private void logException(Span span, Exception e) {
        Map<String, Object> log = new HashMap<>();
        log.put("event", Tags.ERROR.getKey());
        log.put("error.object", e);
        span.log(log);
        Tags.ERROR.set(span, true);
    }

}