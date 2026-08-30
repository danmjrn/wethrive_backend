package solutions.shapeit.wethrive.reminder.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import solutions.shapeit.wethrive.reminder.entity.BudgetReminderSchedule;

public interface BudgetReminderScheduleRepository extends JpaRepository<BudgetReminderSchedule, UUID> {
    List<BudgetReminderSchedule> findAllByUserIdAndDeletedAtIsNullOrderByTargetYearAscTargetMonthAsc(UUID userId);
    Optional<BudgetReminderSchedule> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);
    boolean existsByUserIdAndSpaceIdAndTargetYearAndTargetMonthAndDeletedAtIsNull(
            UUID userId, UUID spaceId, int targetYear, int targetMonth);
    List<BudgetReminderSchedule> findAllBySpaceIdAndTargetYearAndTargetMonthAndDeletedAtIsNull(
            UUID spaceId, int targetYear, int targetMonth);

    @Query(value = "select * from budget_reminder_schedules where deleted_at is null and enabled = true " +
            "and next_run_at <= :now order by next_run_at for update skip locked limit :batchSize", nativeQuery = true)
    List<BudgetReminderSchedule> lockDue(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
