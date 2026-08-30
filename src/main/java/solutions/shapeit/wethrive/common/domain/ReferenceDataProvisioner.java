package solutions.shapeit.wethrive.common.domain;

import java.util.UUID;

public interface ReferenceDataProvisioner {
    void initializeForSpace(UUID spaceId, UUID actorUserId);
}
