package solutions.shapeit.wethrive.reminder.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import solutions.shapeit.wethrive.reminder.entity.ReminderPeriodReview;

public interface ReminderPeriodReviewRepository extends JpaRepository<ReminderPeriodReview, UUID> {
    boolean existsByUserIdAndSpaceIdAndPeriodDateAndDeletedAtIsNull(
            UUID userId, UUID spaceId, LocalDate periodDate);
    boolean existsByUserIdAndPeriodDateAndDeletedAtIsNull(UUID userId, LocalDate periodDate);
    List<ReminderPeriodReview> findAllByUserIdAndDeletedAtIsNullOrderByPeriodDateDesc(UUID userId);
}
