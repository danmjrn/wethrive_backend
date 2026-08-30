package solutions.shapeit.wethrive.sync.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import solutions.shapeit.wethrive.sync.entity.ServerChangeLog;

public interface ServerChangeLogRepository extends JpaRepository<ServerChangeLog, Long> {
    List<ServerChangeLog> findTop500BySpaceIdInAndSequenceIdGreaterThanOrderBySequenceIdAsc(Collection<UUID> spaces, long cursor);
    List<ServerChangeLog> findAllBySpaceIdInAndEntityTypeOrderBySequenceIdAsc(Collection<UUID> spaces, String entityType);
    Optional<ServerChangeLog> findTopBySpaceIdAndEntityTypeAndEntityIdOrderBySequenceIdDesc(
            UUID spaceId, String entityType, UUID entityId);
}
