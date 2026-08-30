package solutions.shapeit.wethrive.common.domain;

import java.util.UUID;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SyncOperationType;

public interface DomainChangeRecorder {
    void record(UUID spaceId, String entityType, UUID entityId, SyncOperationType operationType,
                long entityVersion, UUID actorUserId, boolean tombstone, Object payload);
}
