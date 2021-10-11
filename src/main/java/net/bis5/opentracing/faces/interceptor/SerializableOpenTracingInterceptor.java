package net.bis5.opentracing.faces.interceptor;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import javax.annotation.Priority;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;
import javax.ws.rs.Path;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.contrib.tracerresolver.TracerResolver;
import io.opentracing.tag.Tags;

/**
 * @see io.opentracing.contrib.interceptors.OpenTracingInterceptor
 */
@TracedSerializable
@Interceptor
@Priority(value = Interceptor.Priority.LIBRARY_BEFORE + 1)
public class SerializableOpenTracingInterceptor implements Serializable {
	private static final long serialVersionUID = 1L;
	public static final String SPAN_CONTEXT = "__opentracing_span_context";
    private static final Logger log = Logger.getLogger(SerializableOpenTracingInterceptor.class.getName());

    @Inject
    Instance<Tracer> tracerInstance;

    @AroundInvoke
    public Object wrap(InvocationContext ctx) throws Exception {
        if (skipJaxRs(ctx.getMethod())) {
            return ctx.proceed();
        }

        if (!traced(ctx.getMethod())) {
            return ctx.proceed();
        }

        Tracer tracer = getTracer();
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
        try (Scope scope = tracer.scopeManager().activate(span)) {
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

    public Tracer getTracer() {
        if (null != tracerInstance && !tracerInstance.isUnsatisfied()) {
            return this.tracerInstance.get();
        }

        return TracerResolver.resolveTracer();
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