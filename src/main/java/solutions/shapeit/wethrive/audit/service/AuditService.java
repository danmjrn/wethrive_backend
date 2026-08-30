package solutions.shapeit.wethrive.audit.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import solutions.shapeit.wethrive.audit.entity.AuditEvent;
import solutions.shapeit.wethrive.audit.repository.AuditEventRepository;
import solutions.shapeit.wethrive.common.domain.DomainEnums.AuditResult;

@Service
public class AuditService {
    private final AuditEventRepository events; private final Clock clock;
    public AuditService(AuditEventRepository events, Clock clock) { this.events = events; this.clock = clock; }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID actor, String action, AuditResult result, String metadata) {
        AuditEvent event = new AuditEvent(); event.setActorUserId(actor); event.setAction(action); event.setResult(result);
        event.setMetadata(metadata == null ? null : metadata.substring(0, Math.min(500, metadata.length())));
        event.setCreatedAt(Instant.now(clock)); events.save(event);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID actor, UUID spaceId, String action, String entityType, UUID entityId,
                       AuditResult result, String metadata) {
        AuditEvent event = new AuditEvent(); event.setActorUserId(actor); event.setSpaceId(spaceId);
        event.setAction(action); event.setEntityType(entityType); event.setEntityId(entityId); event.setResult(result);
        event.setMetadata(metadata == null ? null : metadata.substring(0, Math.min(500, metadata.length())));
        event.setCreatedAt(Instant.now(clock)); events.save(event);
    }
}
