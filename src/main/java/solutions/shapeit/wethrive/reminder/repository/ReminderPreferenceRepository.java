package solutions.shapeit.wethrive.reminder.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import solutions.shapeit.wethrive.reminder.entity.ReminderPreference;

public interface ReminderPreferenceRepository extends JpaRepository<ReminderPreference, UUID> {
    List<ReminderPreference> findAllByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID userId);
    Optional<ReminderPreference> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    @Query(value = "select * from reminder_preferences where deleted_at is null and enabled = true " +
            "and (pause_until is null or pause_until <= :now) and next_run_at <= :now " +
            "order by next_run_at for update skip locked limit :batchSize", nativeQuery = true)
    List<ReminderPreference> lockDue(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
