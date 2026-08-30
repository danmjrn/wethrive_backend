package solutions.shapeit.wethrive.reminder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import solutions.shapeit.wethrive.common.domain.DomainEnums.FrequencyType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.ReminderType;
import solutions.shapeit.wethrive.common.model.BaseEntity;

@Entity
@Table(name = "reminder_preferences")
@Getter @Setter @NoArgsConstructor
public class ReminderPreference extends BaseEntity {
    @Column(nullable = false) private UUID userId;
    private UUID spaceId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) private ReminderType reminderType;
    @Column(nullable = false) private boolean enabled;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private FrequencyType frequencyType;
    private Integer intervalValue;
    @Column(length = 80) private String selectedWeekdays;
    @Column(nullable = false) private LocalTime localTime;
    @Column(nullable = false, length = 60) private String timeZone;
    private Integer dayOfMonth;
    private Integer daysBeforeMonthEnd;
    private Integer daysAfterMonthStart;
    @Column(nullable = false) private boolean repeatUntilCompleted;
    private LocalTime quietHoursStart;
    private LocalTime quietHoursEnd;
    @Column(nullable = false) private boolean pushEnabled;
    @Column(nullable = false) private boolean inAppEnabled = true;
    @Column(nullable = false) private boolean detailedContentEnabled;
    @Column(nullable = false) private boolean suppressAfterSpending = true;
    private Instant pauseUntil;
    @Column(nullable = false) private Instant nextRunAt;

    /** Canonical 1.1 semantic name; the legacy column/accessor remains wire compatible. */
    public boolean isSuppressWhenReviewed() { return suppressAfterSpending; }
    public void setSuppressWhenReviewed(boolean value) { suppressAfterSpending = value; }
}
