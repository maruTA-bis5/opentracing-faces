package net.bis5.opentracing.faces.phase;

import javax.enterprise.inject.Instance;
import javax.faces.event.PhaseEvent;
import javax.faces.event.PhaseId;
import javax.faces.event.PhaseListener;
import javax.inject.Inject;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.contrib.tracerresolver.TracerResolver;

public class TracingPhaseListener implements PhaseListener {

    private static final long serialVersionUID = 1L;

    @Inject
    Instance<Tracer> tracerInstance;

    private final transient ThreadLocal<Span> rootSpan = new ThreadLocal<>();
    private final transient ThreadLocal<Scope> rootScope = new ThreadLocal<>();
    private final transient ThreadLocal<Span> currentSpan = new ThreadLocal<>();
    private final transient ThreadLocal<Scope> currentScope = new ThreadLocal<>();

    public void beforePhase(PhaseEvent event) {
        Tracer tracer = getTracer();
        Span activeSpan = tracer.activeSpan();
        if (activeSpan == null) {
            activeSpan = tracer.buildSpan("Faces Lifecycle").start();
            Scope root = tracer.scopeManager().activate(activeSpan, false);
            rootScope.set(root);
            rootSpan.set(activeSpan);
        }
        Span span = tracer.buildSpan(event.getPhaseId().getName()).start();
        Scope scope = tracer.scopeManager().activate(span, false);
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

    private Tracer getTracer() {
        if (!tracerInstance.isUnsatisfied()) {
            return this.tracerInstance.get();
        }
        return TracerResolver.resolveTracer();
    }

    public PhaseId getPhaseId() {
        return PhaseId.ANY_PHASE;
    }

}
