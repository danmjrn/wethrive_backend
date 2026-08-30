package solutions.shapeit.wethrive.audit.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import solutions.shapeit.wethrive.audit.service.AuditService;
import solutions.shapeit.wethrive.common.domain.DomainEnums.AuditResult;
import solutions.shapeit.wethrive.identity.service.UserPrincipal;

@Component
public class AuditInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(AuditInterceptor.class);
    private final AuditService audit;
    public AuditInterceptor(AuditService audit) { this.audit = audit; }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!request.getRequestURI().startsWith("/api/v1") || "GET".equals(request.getMethod())) return;
        UUID actor = SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof UserPrincipal p ? p.id() : null;
        String path = request.getRequestURI().replaceAll("(/api/v1/invitations/)[^/]+(/(?:accept|decline))", "$1[token]$2")
                .replaceAll("/[0-9a-fA-F-]{36}", "/{id}");
        AuditResult result = response.getStatus() < 400 ? AuditResult.SUCCESS : response.getStatus() < 500 ? AuditResult.DENIED : AuditResult.FAILURE;
        try { audit.record(actor, request.getMethod() + " " + path, result, "status=" + response.getStatus()); }
        catch (RuntimeException auditFailure) { log.warn("audit_persistence_failed action={} result={}", request.getMethod(), result); }
    }
}
