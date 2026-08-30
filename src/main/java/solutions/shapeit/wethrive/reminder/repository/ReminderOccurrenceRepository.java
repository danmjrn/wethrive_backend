package solutions.shapeit.wethrive.reminder.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import solutions.shapeit.wethrive.common.domain.DomainEnums.ReminderStatus;
import solutions.shapeit.wethrive.reminder.entity.ReminderOccurrence;

public interface ReminderOccurrenceRepository extends JpaRepository<ReminderOccurrence, UUID> {
    boolean existsByIdempotencyKey(String key);
    Optional<ReminderOccurrence> findByIdAndUserId(UUID id, UUID userId);
    List<ReminderOccurrence> findTop100ByUserIdAndStatusInAndScheduledForAfterOrderByScheduledForAsc(
            UUID userId, List<ReminderStatus> statuses, Instant since);
}
