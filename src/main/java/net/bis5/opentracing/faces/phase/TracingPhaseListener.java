package net.bis5.opentracing.faces.phase;

import java.util.Map;

import javax.enterprise.inject.Instance;
import javax.enterprise.inject.spi.CDI;
import javax.faces.context.FacesContext;
import javax.faces.event.PhaseEvent;
import javax.faces.event.PhaseId;
import javax.faces.event.PhaseListener;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.contrib.tracerresolver.TracerResolver;

public class TracingPhaseListener implements PhaseListener {

    private static final long serialVersionUID = 1L;

    private final transient ThreadLocal<Span> rootSpan = new ThreadLocal<>();
    private final transient ThreadLocal<Scope> rootScope = new ThreadLocal<>();
    private final transient ThreadLocal<Span> currentSpan = new ThreadLocal<>();
    private final transient ThreadLocal<Scope> currentScope = new ThreadLocal<>();

    public void beforePhase(PhaseEvent event) {
        Tracer tracer = getTracer();
        Span activeSpan = tracer.activeSpan();
        if (activeSpan == null) {
            activeSpan = tracer.buildSpan("Faces Lifecycle").start();
            Scope root = tracer.scopeManager().activate(activeSpan);
            rootScope.set(root);
            rootSpan.set(activeSpan);
        }
        Span span = tracer.buildSpan(event.getPhaseId().getName()).start();
        Scope scope = tracer.scopeManager().activate(span);
        currentScope.set(scope);
        currentSpan.set(span);
    }

    public void afterPhase(PhaseEvent event) {
        currentScope.get().close();
        currentScope.remove();
        currentSpan.get().finish();
        currentSpan.remove();
        if (event.getPhaseId() == PhaseId.RENDER_RESPONSE && rootSpan.get() != null) {
            rootScope.get().close();
            rootScope.remove();
            rootSpan.get().finish();
            rootSpan.remove();
        }
    }

    private static final String TRACER_KEY = TracingPhaseListener.class.getName() + ".Tracer";

    private Tracer getTracer() {
        Map<String, Object> request = FacesContext.getCurrentInstance().getExternalContext().getRequestMap();
        return (Tracer) request.computeIfAbsent(TRACER_KEY, ignore -> resolveTracer());
    }

    private Tracer resolveTracer() {
        Instance<Tracer> tracerInstance = CDI.current().getBeanManager().createInstance().select(Tracer.class);
        if (!tracerInstance.isUnsatisfied()) {
            return tracerInstance.get();
        }
        return TracerResolver.resolveTracer();
    }

    public PhaseId getPhaseId() {
        return PhaseId.ANY_PHASE;
    }

}
