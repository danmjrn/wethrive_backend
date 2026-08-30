package solutions.shapeit.wethrive.reminder.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;
import solutions.shapeit.wethrive.common.domain.DomainEnums.FrequencyType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.ReminderStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.ReminderType;

public final class ReminderDtos {
    private ReminderDtos() {}
    public record PreferenceRequest(@NotNull UUID id, UUID spaceId, @NotNull ReminderType reminderType,
                                    boolean enabled, @NotNull FrequencyType frequencyType,
                                    @Min(1) @Max(365) Integer intervalValue, @Size(max = 7) Set<DayOfWeek> selectedWeekdays,
                                    @NotNull LocalTime localTime, @NotNull @Size(max = 60) String timeZone,
                                    @Min(1) @Max(31) Integer dayOfMonth, @Min(0) @Max(31) Integer daysBeforeMonthEnd,
                                    @Min(0) @Max(31) Integer daysAfterMonthStart, boolean repeatUntilCompleted,
                                    LocalTime quietHoursStart, LocalTime quietHoursEnd, boolean pushEnabled,
                                    boolean inAppEnabled, boolean detailedContentEnabled,
                                    @JsonAlias("suppressAfterSpending") boolean suppressWhenReviewed) {}
    public record PreferenceUpdateRequest(UUID spaceId, @NotNull ReminderType reminderType, boolean enabled,
                                          @NotNull FrequencyType frequencyType, @Min(1) @Max(365) Integer intervalValue,
                                          @Size(max = 7) Set<DayOfWeek> selectedWeekdays, @NotNull LocalTime localTime,
                                          @NotNull @Size(max = 60) String timeZone, @Min(1) @Max(31) Integer dayOfMonth,
                                          @Min(0) @Max(31) Integer daysBeforeMonthEnd,
                                          @Min(0) @Max(31) Integer daysAfterMonthStart, boolean repeatUntilCompleted,
                                          LocalTime quietHoursStart, LocalTime quietHoursEnd, boolean pushEnabled,
                                          boolean inAppEnabled, boolean detailedContentEnabled,
                                          @JsonAlias("suppressAfterSpending") boolean suppressWhenReviewed,
                                          @NotNull long version) {}
    public record PreferenceResponse(UUID id, UUID spaceId, ReminderType reminderType, boolean enabled,
                                     FrequencyType frequencyType, Integer intervalValue, Set<DayOfWeek> selectedWeekdays,
                                     LocalTime localTime, String timeZone, Integer dayOfMonth, Integer daysBeforeMonthEnd,
                                     Integer daysAfterMonthStart, boolean repeatUntilCompleted, LocalTime quietHoursStart,
                                     LocalTime quietHoursEnd, boolean pushEnabled, boolean inAppEnabled,
                                     boolean detailedContentEnabled, boolean suppressWhenReviewed,
                                     Instant pauseUntil, Instant nextRunAt, long version) {}
    public record BudgetScheduleRequest(
            @NotNull UUID id, @NotNull UUID reminderPreferenceId, @NotNull UUID spaceId,
            @Min(2000) @Max(2200) int targetYear, @Min(1) @Max(12) int targetMonth,
            @NotNull LocalDate initialReminderDate, @NotNull LocalTime localTime,
            @NotBlank @Size(max = 60) String timeZone, boolean repeatUntilBudgetExists,
            @Min(1) @Max(365) int repeatIntervalDays, boolean enabled) {}
    public record BudgetScheduleUpdateRequest(
            @NotNull UUID reminderPreferenceId, @NotNull UUID spaceId,
            @Min(2000) @Max(2200) int targetYear, @Min(1) @Max(12) int targetMonth,
            @NotNull LocalDate initialReminderDate, @NotNull LocalTime localTime,
            @NotBlank @Size(max = 60) String timeZone, boolean repeatUntilBudgetExists,
            @Min(1) @Max(365) int repeatIntervalDays, boolean enabled, @NotNull long version) {}
    public record BudgetScheduleResponse(
            UUID id, UUID reminderPreferenceId, UUID userId, UUID spaceId,
            int targetYear, int targetMonth, LocalDate initialReminderDate, LocalTime localTime,
            String timeZone, boolean repeatUntilBudgetExists, int repeatIntervalDays, boolean enabled,
            Instant nextRunAt, Instant completedAt, Instant createdAt, Instant updatedAt, long version) {}
    public record BudgetScheduleDeleteRequest(@NotNull Long version) {}
    public record PeriodReviewRequest(@NotNull UUID id, @NotNull UUID spaceId,
                                      @NotNull LocalDate periodDate) {}
    public record PeriodReviewResponse(UUID id, UUID userId, UUID spaceId, LocalDate periodDate,
                                       Instant reviewedAt, Instant createdAt, Instant updatedAt,
                                       long version) {}
    public record PauseRequest(@NotNull Instant until) {}
    public record OccurrenceResponse(UUID id, UUID preferenceId, UUID budgetReminderScheduleId,
                                     UUID spaceId, String targetPeriod,
                                     Instant scheduledFor, ReminderStatus status, String title, String body,
                                     Instant acknowledgedAt, Instant skippedAt, long version) {}
}
