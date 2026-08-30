package solutions.shapeit.wethrive.sync.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import solutions.shapeit.wethrive.common.domain.DomainChangeRecorder;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SyncOperationType;
import solutions.shapeit.wethrive.sync.entity.ServerChangeLog;
import solutions.shapeit.wethrive.sync.repository.ServerChangeLogRepository;
import tools.jackson.databind.ObjectMapper;

@Service
public class ServerChangeService implements DomainChangeRecorder {
    private final ServerChangeLogRepository changes;
    private final ObjectMapper mapper;
    private final Clock clock;
    public ServerChangeService(ServerChangeLogRepository changes, ObjectMapper mapper, Clock clock) { this.changes = changes; this.mapper = mapper; this.clock = clock; }
    @Override
    public void record(UUID spaceId, String entityType, UUID entityId, SyncOperationType operationType,
                       long entityVersion, UUID actorUserId, boolean tombstone, Object payload) {
        ServerChangeLog change = new ServerChangeLog(); change.setSpaceId(spaceId); change.setEntityType(entityType);
        change.setEntityId(entityId); change.setOperationType(operationType); change.setEntityVersion(entityVersion);
        change.setChangedByUserId(actorUserId); change.setChangedAt(Instant.now(clock)); change.setTombstone(tombstone);
        try { change.setPayload(payload == null ? null : mapper.writeValueAsString(payload)); }
        catch (Exception ex) { throw new IllegalStateException("Unable to serialize a synchronized change", ex); }
        changes.save(change);
    }
}
