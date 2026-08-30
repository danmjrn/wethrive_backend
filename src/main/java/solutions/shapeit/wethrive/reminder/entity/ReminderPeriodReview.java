package solutions.shapeit.wethrive.reminder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import solutions.shapeit.wethrive.common.model.BaseEntity;

/** An explicit user acknowledgement that one local calendar period was reviewed. */
@Entity
@Table(name = "reminder_period_reviews")
@Getter @Setter @NoArgsConstructor
public class ReminderPeriodReview extends BaseEntity {
    @Column(nullable = false, updatable = false) private UUID userId;
    @Column(nullable = false, updatable = false) private UUID spaceId;
    @Column(nullable = false, updatable = false) private LocalDate periodDate;
    @Column(nullable = false, updatable = false) private Instant reviewedAt;
}
