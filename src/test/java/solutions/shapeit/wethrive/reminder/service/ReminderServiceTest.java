package solutions.shapeit.wethrive.reminder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import solutions.shapeit.wethrive.audit.service.AuditService;
import solutions.shapeit.wethrive.common.config.ApplicationProperties;
import solutions.shapeit.wethrive.common.domain.DomainChangeRecorder;
import solutions.shapeit.wethrive.common.domain.DomainEnums.FrequencyType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.ReminderStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.ReminderType;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.finance.repository.BudgetMonthRepository;
import solutions.shapeit.wethrive.finance.event.BudgetPeriodStateChanged;
import solutions.shapeit.wethrive.finance.repository.SpendingEntryRepository;
import solutions.shapeit.wethrive.identity.entity.AppUser;
import solutions.shapeit.wethrive.identity.repository.AppUserRepository;
import solutions.shapeit.wethrive.reminder.entity.BudgetReminderSchedule;
import solutions.shapeit.wethrive.reminder.entity.ReminderOccurrence;
import solutions.shapeit.wethrive.reminder.entity.ReminderPreference;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.PeriodReviewRequest;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.PreferenceRequest;
import solutions.shapeit.wethrive.reminder.repository.BudgetReminderScheduleRepository;
import solutions.shapeit.wethrive.reminder.repository.ReminderOccurrenceRepository;
import solutions.shapeit.wethrive.reminder.repository.ReminderPeriodReviewRepository;
import solutions.shapeit.wethrive.reminder.repository.ReminderPreferenceRepository;
import solutions.shapeit.wethrive.space.entity.Space;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;
import solutions.shapeit.wethrive.space.service.SpaceAccessService.Capability;
import solutions.shapeit.wethrive.space.service.SpaceService;

class ReminderServiceTest {
    private final ReminderPreferenceRepository preferences = mock(ReminderPreferenceRepository.class);
    private final ReminderOccurrenceRepository occurrences = mock(ReminderOccurrenceRepository.class);
    private final BudgetMonthRepository budgets = mock(BudgetMonthRepository.class);
    private final SpaceAccessService access = mock(SpaceAccessService.class);
    private final SpaceService spaces = mock(SpaceService.class);
    private final SpendingEntryRepository spending = mock(SpendingEntryRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final BudgetReminderScheduleRepository schedules = mock(BudgetReminderScheduleRepository.class);
    private final ReminderPeriodReviewRepository periodReviews = mock(ReminderPeriodReviewRepository.class);
    private final AuditService audit = mock(AuditService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DomainChangeRecorder> changes = mock(ObjectProvider.class);
    private final Instant now = Instant.parse("2026-08-20T06:31:00Z");
    private ReminderService service;

    @BeforeEach
    void setUp() {
        service = newService(Clock.fixed(now, ZoneId.of("UTC")));
    }

    private ReminderService newService(Clock clock) {
        ApplicationProperties properties = new ApplicationProperties(null, null, null, null,
                new ApplicationProperties.Reminders(true, Duration.ofMinutes(1), 20));
        when(changes.orderedStream()).thenAnswer(invocation -> Stream.empty());
        return new ReminderService(preferences, occurrences, budgets, access, spaces, properties,
                clock, spending, events, users, schedules, periodReviews,
                audit, changes);
    }

    @Test
    void dailyPreferenceUsesTheSelected0645Time() {
        ReminderPreference preference = preference(FrequencyType.DAILY, LocalTime.of(6, 45), "Africa/Johannesburg");
        Instant after = Instant.parse("2026-09-01T03:00:00Z");

        Instant result = service.nextRun(preference, after);

        assertThat(result).isEqualTo(Instant.parse("2026-09-01T04:45:00Z"));
    }

    @Test
    void dailyPreferenceResolvesDaylightSavingGapsWithTheIanaZone() {
        ReminderPreference preference = preference(FrequencyType.DAILY, LocalTime.of(2, 30), "Europe/Berlin");

        Instant result = service.nextRun(preference, Instant.parse("2026-03-28T23:00:00Z"));

        assertThat(result.atZone(ZoneId.of("Europe/Berlin")).toLocalTime()).isEqualTo(LocalTime.of(3, 30));
        assertThat(result).isEqualTo(Instant.parse("2026-03-29T01:30:00Z"));
    }

    @Test
    void monthlyDay31ClampsToTheLastValidDay() {
        ReminderPreference preference = preference(FrequencyType.MONTHLY, LocalTime.of(9, 0), "Africa/Johannesburg");
        preference.setDayOfMonth(31);

        assertThat(service.scheduleDate(preference, YearMonth.of(2026, 2)))
                .isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void periodOverrideKeepsItsExplicitPreviousMonthDateAndTime() {
        BudgetReminderSchedule schedule = schedule();

        Instant result = service.scheduleNextRun(schedule, Instant.parse("2026-08-19T00:00:00Z"));

        assertThat(result).isEqualTo(Instant.parse("2026-08-20T06:30:00Z"));
    }

    @Test
    void schedulerCreatesOneOccurrenceForTheExplicitTargetAndAdvancesRepeat() {
        UUID userId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID preferenceId = UUID.randomUUID();
        BudgetReminderSchedule schedule = schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setUserId(userId);
        schedule.setSpaceId(spaceId);
        schedule.setReminderPreferenceId(preferenceId);
        schedule.setNextRunAt(Instant.parse("2026-08-20T06:30:00Z"));

        ReminderPreference preference = preference(FrequencyType.MONTHLY, LocalTime.of(9, 0), "Africa/Johannesburg");
        preference.setId(preferenceId);
        preference.setUserId(userId);
        preference.setSpaceId(spaceId);
        preference.setReminderType(ReminderType.MONTHLY_BUDGET_SETUP);
        preference.setEnabled(true);
        preference.setInAppEnabled(true);

        AppUser user = new AppUser();
        user.setId(userId);
        user.setEnabled(true);
        Space space = new Space();
        space.setId(spaceId);
        space.setName("Home");

        when(preferences.lockDue(now, 20)).thenReturn(List.of());
        when(schedules.lockDue(now, 20)).thenReturn(List.of(schedule));
        when(preferences.findByIdAndUserIdAndDeletedAtIsNull(preferenceId, userId)).thenReturn(Optional.of(preference));
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(access.can(spaceId, userId, SpaceAccessService.Capability.EDIT_FINANCE)).thenReturn(true);
        when(budgets.existsBySpaceIdAndYearAndMonthAndDeletedAtIsNull(spaceId, 2026, 9)).thenReturn(false);
        when(occurrences.existsByIdempotencyKey(any())).thenReturn(false);
        when(spaces.requireSpace(spaceId)).thenReturn(space);
        when(occurrences.saveAndFlush(any())).thenAnswer(invocation -> {
            ReminderOccurrence value = invocation.getArgument(0);
            value.setId(UUID.randomUUID());
            return value;
        });

        service.generateDueOccurrences();

        ArgumentCaptor<ReminderOccurrence> captor = ArgumentCaptor.forClass(ReminderOccurrence.class);
        verify(occurrences).saveAndFlush(captor.capture());
        ReminderOccurrence created = captor.getValue();
        assertThat(created.getBudgetReminderScheduleId()).isEqualTo(schedule.getId());
        assertThat(created.getTargetPeriod()).isEqualTo("2026-09");
        assertThat(created.getScheduledFor()).isEqualTo(Instant.parse("2026-08-20T06:30:00Z"));
        assertThat(created.getStatus()).isEqualTo(ReminderStatus.DELIVERED);
        assertThat(schedule.getNextRunAt()).isEqualTo(Instant.parse("2026-08-22T06:30:00Z"));
    }

    @Test
    void budgetCreationSuppressesAndDeletionReactivatesTheSamePeriodSchedule() {
        UUID spaceId = UUID.randomUUID();
        BudgetReminderSchedule schedule = schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setUserId(UUID.randomUUID());
        schedule.setSpaceId(spaceId);
        schedule.setNextRunAt(Instant.parse("2026-08-20T06:30:00Z"));
        when(schedules.findAllBySpaceIdAndTargetYearAndTargetMonthAndDeletedAtIsNull(spaceId, 2026, 9))
                .thenReturn(List.of(schedule));
        when(schedules.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.recalculateBudgetPeriod(new BudgetPeriodStateChanged(spaceId, 2026, 9, true, now));

        assertThat(schedule.isEnabled()).isTrue();
        assertThat(schedule.getCompletedAt()).isEqualTo(now);
        assertThat(schedule.getNextRunAt()).isAfter(now.plus(Duration.ofDays(365)));

        service.recalculateBudgetPeriod(new BudgetPeriodStateChanged(spaceId, 2026, 9, false, now.plusSeconds(1)));

        assertThat(schedule.isEnabled()).isTrue();
        assertThat(schedule.getCompletedAt()).isNull();
        assertThat(schedule.getNextRunAt()).isEqualTo(Instant.parse("2026-08-22T06:30:00Z"));
    }

    @Test
    void persisted0645PreferenceDrivesTheGeneratedOccurrence() {
        UUID userId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID preferenceId = UUID.randomUUID();
        MutableClock mutableClock = new MutableClock(Instant.parse("2026-08-20T03:00:00Z"));
        service = newService(mutableClock);
        AtomicReference<ReminderPreference> stored = new AtomicReference<>();
        when(preferences.saveAndFlush(any())).thenAnswer(invocation -> {
            ReminderPreference value = invocation.getArgument(0);
            stored.set(value);
            return value;
        });

        var response = service.create(userId, new PreferenceRequest(preferenceId, spaceId,
                ReminderType.SPENDING_CHECK_IN, true, FrequencyType.DAILY, 1, Set.of(),
                LocalTime.of(6, 45), "Africa/Johannesburg", null, null, null, false,
                null, null, false, true, false, true));

        assertThat(response.localTime()).isEqualTo(LocalTime.of(6, 45));
        assertThat(response.nextRunAt()).isEqualTo(Instant.parse("2026-08-20T04:45:00Z"));

        mutableClock.set(Instant.parse("2026-08-20T04:46:00Z"));
        ReminderPreference preference = stored.get();
        when(preferences.lockDue(mutableClock.instant(), 20)).thenReturn(List.of(preference));
        when(schedules.lockDue(mutableClock.instant(), 20)).thenReturn(List.of());
        activeUserAndSpace(userId, spaceId, Capability.VIEW);
        when(periodReviews.existsByUserIdAndSpaceIdAndPeriodDateAndDeletedAtIsNull(
                userId, spaceId, LocalDate.of(2026, 8, 20))).thenReturn(false);
        when(occurrences.existsByIdempotencyKey(any())).thenReturn(false);
        when(occurrences.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.generateDueOccurrences();

        ArgumentCaptor<ReminderOccurrence> occurrence = ArgumentCaptor.forClass(ReminderOccurrence.class);
        verify(occurrences).saveAndFlush(occurrence.capture());
        assertThat(occurrence.getValue().getScheduledFor()).isEqualTo(Instant.parse("2026-08-20T04:45:00Z"));
        assertThat(occurrence.getValue().getTargetPeriod()).isEqualTo("2026-08-20");
    }

    @Test
    void reviewedSuppressionUsesExplicitAcknowledgementAndHonoursTheToggle() {
        UUID userId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        ReminderPreference suppressing = dueSpendingPreference(userId, spaceId, UUID.randomUUID(), true);
        ReminderPreference repeating = dueSpendingPreference(userId, spaceId, UUID.randomUUID(), false);
        when(preferences.lockDue(now, 20)).thenReturn(List.of(suppressing, repeating));
        when(schedules.lockDue(now, 20)).thenReturn(List.of());
        activeUserAndSpace(userId, spaceId, Capability.VIEW);
        when(periodReviews.existsByUserIdAndSpaceIdAndPeriodDateAndDeletedAtIsNull(
                userId, spaceId, LocalDate.of(2026, 8, 20))).thenReturn(true);
        when(occurrences.existsByIdempotencyKey(any())).thenReturn(false);
        when(occurrences.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.generateDueOccurrences();

        ArgumentCaptor<ReminderOccurrence> occurrence = ArgumentCaptor.forClass(ReminderOccurrence.class);
        verify(occurrences).saveAndFlush(occurrence.capture());
        assertThat(occurrence.getValue().getReminderPreferenceId()).isEqualTo(repeating.getId());
        verify(spending, never()).existsBySpaceIdAndSpentAtBetweenAndDeletedAtIsNull(any(), any(), any());
        verify(spending, never()).existsByCreatedByUserIdAndSpentAtBetweenAndDeletedAtIsNull(any(), any(), any());
    }

    @Test
    void explicitScheduleRetryAfterOccurrenceFlushUsesTheSameIntendedRun() {
        UUID userId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID preferenceId = UUID.randomUUID();
        BudgetReminderSchedule schedule = schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setUserId(userId);
        schedule.setSpaceId(spaceId);
        schedule.setReminderPreferenceId(preferenceId);
        Instant intendedRun = Instant.parse("2026-08-20T06:30:00Z");
        schedule.setNextRunAt(intendedRun);
        ReminderPreference preference = preference(FrequencyType.MONTHLY, LocalTime.of(9, 0), "Africa/Johannesburg");
        preference.setId(preferenceId);
        preference.setUserId(userId);
        preference.setSpaceId(spaceId);
        preference.setReminderType(ReminderType.MONTHLY_BUDGET_SETUP);
        preference.setEnabled(true);
        preference.setInAppEnabled(true);
        AtomicReference<String> persistedKey = new AtomicReference<>();
        when(preferences.lockDue(now, 20)).thenReturn(List.of());
        when(schedules.lockDue(now, 20)).thenReturn(List.of(schedule));
        when(preferences.findByIdAndUserIdAndDeletedAtIsNull(preferenceId, userId)).thenReturn(Optional.of(preference));
        activeUserAndSpace(userId, spaceId, Capability.EDIT_FINANCE);
        when(budgets.existsBySpaceIdAndYearAndMonthAndDeletedAtIsNull(spaceId, 2026, 9)).thenReturn(false);
        when(occurrences.existsByIdempotencyKey(any())).thenAnswer(invocation ->
                invocation.getArgument(0).equals(persistedKey.get()));
        when(occurrences.saveAndFlush(any())).thenAnswer(invocation -> {
            ReminderOccurrence value = invocation.getArgument(0);
            value.setId(UUID.randomUUID());
            persistedKey.set(value.getIdempotencyKey());
            return value;
        });
        when(changes.orderedStream()).thenThrow(new IllegalStateException("failure after occurrence flush"))
                .thenAnswer(invocation -> Stream.empty());

        service.generateDueOccurrences();
        assertThat(schedule.getNextRunAt()).isEqualTo(intendedRun);

        service.generateDueOccurrences();

        verify(occurrences, times(1)).saveAndFlush(any());
        assertThat(persistedKey.get()).endsWith(":" + intendedRun);
        assertThat(schedule.getNextRunAt()).isEqualTo(Instant.parse("2026-08-22T06:30:00Z"));
    }

    @Test
    void preferenceRetryAfterOccurrenceFlushUsesTheSameIntendedRun() {
        UUID userId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        ReminderPreference preference = dueSpendingPreference(userId, spaceId, UUID.randomUUID(), false);
        Instant intendedRun = preference.getNextRunAt();
        AtomicReference<String> persistedKey = new AtomicReference<>();
        when(preferences.lockDue(now, 20)).thenReturn(List.of(preference));
        when(schedules.lockDue(now, 20)).thenReturn(List.of());
        activeUserAndSpace(userId, spaceId, Capability.VIEW);
        when(occurrences.existsByIdempotencyKey(any())).thenAnswer(invocation ->
                invocation.getArgument(0).equals(persistedKey.get()));
        when(occurrences.saveAndFlush(any())).thenAnswer(invocation -> {
            ReminderOccurrence value = invocation.getArgument(0);
            value.setId(UUID.randomUUID());
            persistedKey.set(value.getIdempotencyKey());
            return value;
        });
        when(changes.orderedStream()).thenThrow(new IllegalStateException("failure after occurrence flush"))
                .thenAnswer(invocation -> Stream.empty());

        service.generateDueOccurrences();
        assertThat(preference.getNextRunAt()).isEqualTo(intendedRun);

        service.generateDueOccurrences();

        verify(occurrences, times(1)).saveAndFlush(any());
        assertThat(persistedKey.get()).endsWith(":" + intendedRun.atZone(ZoneId.of("Africa/Johannesburg")).toLocalDate());
        assertThat(preference.getNextRunAt()).isAfter(now);
    }

    @Test
    void delayedMonthlyRunKeepsItsAugustIntendedTargetWhenRetryOccursInSeptember() {
        Instant septemberNow = Instant.parse("2026-09-01T00:01:00Z");
        service = newService(Clock.fixed(septemberNow, ZoneOffset.UTC));
        UUID userId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        ReminderPreference preference = preference(FrequencyType.MONTHLY, LocalTime.of(23, 0), "Africa/Johannesburg");
        preference.setId(UUID.randomUUID());
        preference.setUserId(userId);
        preference.setSpaceId(spaceId);
        preference.setReminderType(ReminderType.MONTHLY_BUDGET_SETUP);
        preference.setEnabled(true);
        preference.setInAppEnabled(true);
        preference.setDayOfMonth(31);
        preference.setNextRunAt(Instant.parse("2026-08-31T21:00:00Z"));
        when(preferences.lockDue(septemberNow, 20)).thenReturn(List.of(preference));
        when(schedules.lockDue(septemberNow, 20)).thenReturn(List.of());
        activeUserAndSpace(userId, spaceId, Capability.VIEW);
        when(budgets.existsBySpaceIdAndYearAndMonthAndDeletedAtIsNull(spaceId, 2026, 9)).thenReturn(false);
        when(occurrences.existsByIdempotencyKey(any())).thenReturn(false);
        when(occurrences.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.generateDueOccurrences();

        ArgumentCaptor<ReminderOccurrence> occurrence = ArgumentCaptor.forClass(ReminderOccurrence.class);
        verify(occurrences).saveAndFlush(occurrence.capture());
        assertThat(occurrence.getValue().getTargetPeriod()).isEqualTo("2026-09");
        assertThat(occurrence.getValue().getScheduledFor()).isEqualTo(Instant.parse("2026-08-31T21:00:00Z"));
    }

    @Test
    void reviewedPeriodIsPersistedAndFinanceMutationsRequireEditCapability() {
        UUID userId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        when(periodReviews.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var review = service.reviewPeriod(userId,
                new PeriodReviewRequest(reviewId, spaceId, LocalDate.of(2026, 8, 20)));

        assertThat(review.id()).isEqualTo(reviewId);
        assertThat(review.periodDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        verify(access).require(spaceId, userId, Capability.VIEW);

        ReminderPreference preference = dueSpendingPreference(userId, spaceId, UUID.randomUUID(), true);
        BudgetReminderSchedule schedule = schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setUserId(userId);
        schedule.setSpaceId(spaceId);
        when(preferences.findByIdAndUserIdAndDeletedAtIsNull(preference.getId(), userId))
                .thenReturn(Optional.of(preference));
        when(schedules.findByIdAndUserIdAndDeletedAtIsNull(schedule.getId(), userId))
                .thenReturn(Optional.of(schedule));
        doThrow(ApiException.forbidden()).when(access).require(spaceId, userId, Capability.EDIT_FINANCE);

        assertThatThrownBy(() -> service.pause(userId, preference.getId(), now.plusSeconds(60)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.resume(userId, preference.getId()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.delete(userId, preference.getId()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.deleteBudgetSchedule(userId, schedule.getId(), schedule.getVersion()))
                .isInstanceOf(ApiException.class);
        verify(preferences, never()).saveAndFlush(preference);
        verify(schedules, never()).saveAndFlush(schedule);
    }

    private ReminderPreference dueSpendingPreference(UUID userId, UUID spaceId, UUID id, boolean suppress) {
        ReminderPreference preference = preference(FrequencyType.DAILY, LocalTime.of(8, 30), "Africa/Johannesburg");
        preference.setId(id);
        preference.setUserId(userId);
        preference.setSpaceId(spaceId);
        preference.setReminderType(ReminderType.SPENDING_CHECK_IN);
        preference.setEnabled(true);
        preference.setInAppEnabled(true);
        preference.setSuppressAfterSpending(suppress);
        preference.setNextRunAt(Instant.parse("2026-08-20T06:30:00Z"));
        return preference;
    }

    private void activeUserAndSpace(UUID userId, UUID spaceId, Capability capability) {
        AppUser user = new AppUser();
        user.setId(userId);
        user.setEnabled(true);
        Space space = new Space();
        space.setId(spaceId);
        space.setName("Home");
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(access.can(spaceId, userId, capability)).thenReturn(true);
        when(spaces.requireSpace(spaceId)).thenReturn(space);
    }

    private static final class MutableClock extends Clock {
        private Instant value;

        private MutableClock(Instant value) { this.value = value; }
        private void set(Instant value) { this.value = value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }

    private ReminderPreference preference(FrequencyType frequency, LocalTime time, String zone) {
        ReminderPreference preference = new ReminderPreference();
        preference.setFrequencyType(frequency);
        preference.setLocalTime(time);
        preference.setTimeZone(zone);
        preference.setIntervalValue(1);
        return preference;
    }

    private BudgetReminderSchedule schedule() {
        BudgetReminderSchedule schedule = new BudgetReminderSchedule();
        schedule.setTargetYear(2026);
        schedule.setTargetMonth(9);
        schedule.setInitialReminderDate(LocalDate.of(2026, 8, 20));
        schedule.setLocalTime(LocalTime.of(8, 30));
        schedule.setTimeZone("Africa/Johannesburg");
        schedule.setRepeatUntilBudgetExists(true);
        schedule.setRepeatIntervalDays(2);
        schedule.setEnabled(true);
        return schedule;
    }
}
