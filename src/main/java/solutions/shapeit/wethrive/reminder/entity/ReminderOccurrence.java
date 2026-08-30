package solutions.shapeit.wethrive.reminder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import solutions.shapeit.wethrive.common.domain.DomainEnums.ReminderStatus;
import solutions.shapeit.wethrive.common.model.BaseEntity;

@Entity
@Table(name = "reminder_occurrences")
@Getter @Setter @NoArgsConstructor
public class ReminderOccurrence extends BaseEntity {
    @Column(nullable = false) private UUID reminderPreferenceId;
    private UUID budgetReminderScheduleId;
    @Column(nullable = false) private UUID userId;
    private UUID spaceId;
    @Column(nullable = false, length = 30) private String targetPeriod;
    @Column(nullable = false) private Instant scheduledFor;
    private Instant deliveredAt;
    private Instant acknowledgedAt;
    private Instant skippedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ReminderStatus status;
    @Column(nullable = false, unique = true, length = 180) private String idempotencyKey;
    @Column(length = 300) private String failureReason;
    @Column(nullable = false, length = 160) private String title;
    @Column(nullable = false, length = 300) private String body;
}
