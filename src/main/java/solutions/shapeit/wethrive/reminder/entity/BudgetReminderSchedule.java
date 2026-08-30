package solutions.shapeit.wethrive.reminder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import solutions.shapeit.wethrive.common.model.SpaceOwnedEntity;

/** A versioned override for one unambiguous target budget period. */
@Entity
@Table(name = "budget_reminder_schedules")
@Getter @Setter @NoArgsConstructor
public class BudgetReminderSchedule extends SpaceOwnedEntity {
    @Column(nullable = false, updatable = false) private UUID reminderPreferenceId;
    @Column(nullable = false, updatable = false) private UUID userId;
    @Column(nullable = false) private int targetYear;
    @Column(nullable = false) private int targetMonth;
    @Column(nullable = false) private LocalDate initialReminderDate;
    @Column(nullable = false) private LocalTime localTime;
    @Column(nullable = false, length = 60) private String timeZone;
    @Column(nullable = false) private boolean repeatUntilBudgetExists;
    @Column(nullable = false) private int repeatIntervalDays;
    @Column(nullable = false) private boolean enabled = true;
    @Column(nullable = false) private Instant nextRunAt;
    private Instant completedAt;
}
