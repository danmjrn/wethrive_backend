package solutions.shapeit.wethrive.reminder.service;

import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;
import solutions.shapeit.wethrive.audit.service.AuditService;
import solutions.shapeit.wethrive.common.config.ApplicationProperties;
import solutions.shapeit.wethrive.common.domain.DomainChangeRecorder;
import solutions.shapeit.wethrive.common.domain.DomainEnums.AuditResult;
import solutions.shapeit.wethrive.common.domain.DomainEnums.FrequencyType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.ReminderStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.ReminderType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SyncOperationType;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.finance.repository.BudgetMonthRepository;
import solutions.shapeit.wethrive.finance.event.BudgetPeriodStateChanged;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.OccurrenceResponse;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.BudgetScheduleRequest;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.BudgetScheduleResponse;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.BudgetScheduleUpdateRequest;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.PreferenceRequest;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.PreferenceResponse;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.PreferenceUpdateRequest;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.PeriodReviewRequest;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.PeriodReviewResponse;
import solutions.shapeit.wethrive.reminder.entity.ReminderOccurrence;
import solutions.shapeit.wethrive.reminder.entity.ReminderPeriodReview;
import solutions.shapeit.wethrive.reminder.entity.ReminderPreference;
import solutions.shapeit.wethrive.reminder.entity.BudgetReminderSchedule;
import solutions.shapeit.wethrive.reminder.repository.BudgetReminderScheduleRepository;
import solutions.shapeit.wethrive.reminder.repository.ReminderOccurrenceRepository;
import solutions.shapeit.wethrive.reminder.repository.ReminderPeriodReviewRepository;
import solutions.shapeit.wethrive.reminder.repository.ReminderPreferenceRepository;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;
import solutions.shapeit.wethrive.space.service.SpaceAccessService.Capability;
import solutions.shapeit.wethrive.space.service.SpaceService;
import solutions.shapeit.wethrive.finance.repository.SpendingEntryRepository;
import solutions.shapeit.wethrive.notification.service.ReminderPushRequested;
import solutions.shapeit.wethrive.identity.entity.AppUser;
import solutions.shapeit.wethrive.identity.repository.AppUserRepository;

@Service
public class ReminderService {
    private static final Duration COMPLETION_RECHECK_DELAY = Duration.ofDays(366);
    private final ReminderPreferenceRepository preferences;
    private final ReminderOccurrenceRepository occurrences;
    private final BudgetMonthRepository budgets;
    private final SpaceAccessService access;
    private final SpaceService spaces;
    private final ApplicationProperties properties;
    private final Clock clock;
    private final SpendingEntryRepository spending;
    private final ApplicationEventPublisher events;
    private final AppUserRepository users;
    private final BudgetReminderScheduleRepository budgetSchedules;
    private final ReminderPeriodReviewRepository periodReviews;
    private final AuditService audit;
    private final ObjectProvider<DomainChangeRecorder> changes;

    public ReminderService(ReminderPreferenceRepository preferences, ReminderOccurrenceRepository occurrences,
                           BudgetMonthRepository budgets, SpaceAccessService access, SpaceService spaces,
                           ApplicationProperties properties, Clock clock, SpendingEntryRepository spending,
                           ApplicationEventPublisher events, AppUserRepository users,
                           BudgetReminderScheduleRepository budgetSchedules,
                           ReminderPeriodReviewRepository periodReviews, AuditService audit,
                           ObjectProvider<DomainChangeRecorder> changes) {
        this.preferences = preferences; this.occurrences = occurrences; this.budgets = budgets;
        this.access = access; this.spaces = spaces; this.properties = properties; this.clock = clock;
        this.spending = spending; this.events = events;
        this.users = users;
        this.budgetSchedules = budgetSchedules;
        this.periodReviews = periodReviews;
        this.audit = audit;
        this.changes = changes;
    }

    @Transactional
    public List<PreferenceResponse> list(UUID userId) { return preferences.findAllByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId).stream().map(this::map).toList(); }

    @Transactional
    public List<BudgetScheduleResponse> listBudgetSchedules(UUID userId) {
        return budgetSchedules.findAllByUserIdAndDeletedAtIsNullOrderByTargetYearAscTargetMonthAsc(userId)
                .stream().map(this::map).toList();
    }

    @Transactional
    public List<PeriodReviewResponse> listPeriodReviews(UUID userId) {
        return periodReviews.findAllByUserIdAndDeletedAtIsNullOrderByPeriodDateDesc(userId).stream()
                .filter(review -> access.can(review.getSpaceId(), userId, Capability.VIEW))
                .map(this::map).toList();
    }

    @Transactional
    public PeriodReviewResponse reviewPeriod(UUID userId, PeriodReviewRequest request) {
        ReminderPeriodReview existing = periodReviews.findById(request.id()).orElse(null);
        if (existing != null) {
            if (!existing.getUserId().equals(userId)
                    || !existing.getSpaceId().equals(request.spaceId())
                    || !existing.getPeriodDate().equals(request.periodDate())) {
                throw ApiException.conflict("The period-review identifier is already in use");
            }
            access.require(existing.getSpaceId(), userId, Capability.VIEW);
            return map(existing);
        }
        access.require(request.spaceId(), userId, Capability.VIEW);
        ReminderPeriodReview review = new ReminderPeriodReview();
        review.setId(request.id());
        review.setUserId(userId);
        review.setSpaceId(request.spaceId());
        review.setPeriodDate(request.periodDate());
        review.setReviewedAt(Instant.now(clock));
        ReminderPeriodReview saved = periodReviews.saveAndFlush(review);
        PeriodReviewResponse response = map(saved);
        recordPeriodReview(saved, userId, response);
        audit.record(userId, saved.getSpaceId(), "REMINDER_PERIOD_REVIEWED",
                "REMINDER_PERIOD_REVIEW", saved.getId(), AuditResult.SUCCESS,
                "Acknowledged a reminder review period");
        return response;
    }

    @Transactional
    public BudgetScheduleResponse createBudgetSchedule(UUID userId, BudgetScheduleRequest request) {
        if (budgetSchedules.existsById(request.id())) {
            throw ApiException.conflict("A budget reminder schedule with this identifier already exists");
        }
        if (budgetSchedules.existsByUserIdAndSpaceIdAndTargetYearAndTargetMonthAndDeletedAtIsNull(
                userId, request.spaceId(), request.targetYear(), request.targetMonth())) {
            throw ApiException.conflict("A reminder override already exists for this target budget period");
        }
        ReminderPreference preference = requireMonthlyPreference(userId, request.reminderPreferenceId(), request.spaceId());
        BudgetReminderSchedule schedule = new BudgetReminderSchedule();
        schedule.setId(request.id());
        schedule.setReminderPreferenceId(preference.getId());
        schedule.setUserId(userId);
        schedule.setSpaceId(request.spaceId());
        schedule.setCreatedByUserId(userId);
        schedule.setUpdatedByUserId(userId);
        apply(schedule, request.targetYear(), request.targetMonth(), request.initialReminderDate(), request.localTime(),
                request.timeZone(), request.repeatUntilBudgetExists(), request.repeatIntervalDays(), request.enabled());
        applyBudgetCompletionState(schedule, Instant.now(clock));
        BudgetScheduleResponse response = map(budgetSchedules.saveAndFlush(schedule));
        recordSchedule(schedule, SyncOperationType.CREATE, userId, false, response);
        audit.record(userId, schedule.getSpaceId(), "BUDGET_REMINDER_SCHEDULE_CREATED",
                "BUDGET_REMINDER_SCHEDULE", schedule.getId(), AuditResult.SUCCESS,
                "Created a period-specific budget reminder schedule");
        return response;
    }

    @Transactional
    public BudgetScheduleResponse updateBudgetSchedule(UUID userId, UUID id, BudgetScheduleUpdateRequest request) {
        BudgetReminderSchedule schedule = requireBudgetSchedule(userId, id);
        if (schedule.getVersion() != request.version()) {
            throw ApiException.conflict("The budget reminder schedule changed on another device");
        }
        if (!schedule.getSpaceId().equals(request.spaceId())
                || !schedule.getReminderPreferenceId().equals(request.reminderPreferenceId())) {
            throw ApiException.badRequest("A budget reminder schedule cannot be moved to another space or preference");
        }
        requireMonthlyPreference(userId, request.reminderPreferenceId(), request.spaceId());
        apply(schedule, request.targetYear(), request.targetMonth(), request.initialReminderDate(), request.localTime(),
                request.timeZone(), request.repeatUntilBudgetExists(), request.repeatIntervalDays(), request.enabled());
        schedule.setUpdatedByUserId(userId);
        applyBudgetCompletionState(schedule, Instant.now(clock));
        BudgetScheduleResponse response = map(budgetSchedules.saveAndFlush(schedule));
        recordSchedule(schedule, SyncOperationType.UPDATE, userId, false, response);
        audit.record(userId, schedule.getSpaceId(), "BUDGET_REMINDER_SCHEDULE_UPDATED",
                "BUDGET_REMINDER_SCHEDULE", schedule.getId(), AuditResult.SUCCESS,
                "Updated a period-specific budget reminder schedule");
        return response;
    }

    @Transactional
    public void deleteBudgetSchedule(UUID userId, UUID id, long version) {
        BudgetReminderSchedule schedule = requireBudgetSchedule(userId, id);
        access.require(schedule.getSpaceId(), userId, Capability.EDIT_FINANCE);
        if (schedule.getVersion() != version) {
            throw ApiException.conflict("The budget reminder schedule changed on another device");
        }
        schedule.setDeletedAt(Instant.now(clock));
        schedule.setEnabled(false);
        schedule.setUpdatedByUserId(userId);
        budgetSchedules.saveAndFlush(schedule);
        recordSchedule(schedule, SyncOperationType.DELETE, userId, true, null);
        audit.record(userId, schedule.getSpaceId(), "BUDGET_REMINDER_SCHEDULE_DELETED",
                "BUDGET_REMINDER_SCHEDULE", schedule.getId(), AuditResult.SUCCESS,
                "Removed a period-specific budget reminder schedule");
    }

    @Transactional
    public PreferenceResponse create(UUID userId, PreferenceRequest request) {
        if (preferences.existsById(request.id())) throw ApiException.conflict("A reminder preference with this identifier already exists");
        validateSpace(userId, request.spaceId(), request.reminderType());
        ReminderPreference preference = new ReminderPreference(); preference.setId(request.id()); preference.setUserId(userId);
        apply(preference, request.spaceId(), request.reminderType(), request.enabled(), request.frequencyType(), request.intervalValue(),
                request.selectedWeekdays(), request.localTime(), request.timeZone(), request.dayOfMonth(), request.daysBeforeMonthEnd(),
                request.daysAfterMonthStart(), request.repeatUntilCompleted(), request.quietHoursStart(), request.quietHoursEnd(),
                request.pushEnabled(), request.inAppEnabled(), request.detailedContentEnabled(), request.suppressWhenReviewed());
        preference.setNextRunAt(nextRun(preference, Instant.now(clock)));
        PreferenceResponse response = map(preferences.saveAndFlush(preference));
        recordPreference(preference, SyncOperationType.CREATE, userId, false, response);
        auditPreference(userId, preference, "REMINDER_PREFERENCE_CREATED");
        return response;
    }

    @Transactional
    public PreferenceResponse update(UUID userId, UUID id, PreferenceUpdateRequest request) {
        ReminderPreference preference = requirePreference(userId, id);
        if (preference.getVersion() != request.version()) throw ApiException.conflict("The reminder was changed on another device");
        validateSpace(userId, request.spaceId(), request.reminderType());
        apply(preference, request.spaceId(), request.reminderType(), request.enabled(), request.frequencyType(), request.intervalValue(),
                request.selectedWeekdays(), request.localTime(), request.timeZone(), request.dayOfMonth(), request.daysBeforeMonthEnd(),
                request.daysAfterMonthStart(), request.repeatUntilCompleted(), request.quietHoursStart(), request.quietHoursEnd(),
                request.pushEnabled(), request.inAppEnabled(), request.detailedContentEnabled(), request.suppressWhenReviewed());
        preference.setNextRunAt(nextRun(preference, Instant.now(clock)));
        PreferenceResponse response = map(preferences.saveAndFlush(preference));
        recordPreference(preference, SyncOperationType.UPDATE, userId, false, response);
        auditPreference(userId, preference, "REMINDER_PREFERENCE_UPDATED");
        return response;
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        ReminderPreference preference = requirePreference(userId, id);
        requireEditForMutation(userId, preference);
        preference.setDeletedAt(Instant.now(clock));
        preferences.saveAndFlush(preference);
        recordPreference(preference, SyncOperationType.DELETE, userId, true, null);
        auditPreference(userId, preference, "REMINDER_PREFERENCE_DELETED");
    }
    @Transactional
    public PreferenceResponse pause(UUID userId, UUID id, Instant until) { if (!until.isAfter(Instant.now(clock))) throw ApiException.badRequest("Pause-until must be in the future"); ReminderPreference p = requirePreference(userId, id); requireEditForMutation(userId, p); p.setPauseUntil(until); p.setNextRunAt(until); return map(preferences.saveAndFlush(p)); }
    @Transactional
    public PreferenceResponse pause(UUID userId, UUID id, Instant until, long version) {
        ReminderPreference preference = requirePreference(userId, id);
        requireEditForMutation(userId, preference);
        if (preference.getVersion() != version) throw ApiException.conflict("The reminder was changed on another device");
        if (!until.isAfter(Instant.now(clock))) throw ApiException.badRequest("Pause-until must be in the future");
        preference.setPauseUntil(until); preference.setNextRunAt(until);
        return map(preferences.saveAndFlush(preference));
    }
    @Transactional
    public PreferenceResponse resume(UUID userId, UUID id) { ReminderPreference p = requirePreference(userId, id); requireEditForMutation(userId, p); p.setPauseUntil(null); p.setNextRunAt(nextRun(p, Instant.now(clock))); return map(preferences.saveAndFlush(p)); }
    @Transactional
    public PreferenceResponse resume(UUID userId, UUID id, long version) {
        ReminderPreference preference = requirePreference(userId, id);
        requireEditForMutation(userId, preference);
        if (preference.getVersion() != version) throw ApiException.conflict("The reminder was changed on another device");
        preference.setPauseUntil(null); preference.setNextRunAt(nextRun(preference, Instant.now(clock)));
        return map(preferences.saveAndFlush(preference));
    }

    @Transactional
    public List<OccurrenceResponse> upcoming(UUID userId) {
        return occurrences.findTop100ByUserIdAndStatusInAndScheduledForAfterOrderByScheduledForAsc(userId,
                List.of(ReminderStatus.PENDING, ReminderStatus.DELIVERED), Instant.now(clock).minusSeconds(2_592_000)).stream().map(this::map).toList();
    }
    @Transactional
    public void acknowledge(UUID userId, UUID id) { ReminderOccurrence o = requireOccurrence(userId, id); o.setAcknowledgedAt(Instant.now(clock)); o.setStatus(ReminderStatus.ACKNOWLEDGED); occurrences.save(o); acknowledgePeriod(o); }
    @Transactional
    public void skip(UUID userId, UUID id) { ReminderOccurrence o = requireOccurrence(userId, id); o.setSkippedAt(Instant.now(clock)); o.setStatus(ReminderStatus.SKIPPED); occurrences.save(o); acknowledgePeriod(o); }
    @Transactional
    public OccurrenceResponse review(UUID userId, UUID id, String action, long version) {
        ReminderOccurrence occurrence = requireOccurrence(userId, id);
        if (occurrence.getVersion() != version) throw ApiException.conflict("The reminder occurrence changed on another device");
        if ("ACKNOWLEDGE".equals(action)) {
            occurrence.setAcknowledgedAt(Instant.now(clock)); occurrence.setStatus(ReminderStatus.ACKNOWLEDGED);
        } else if ("SKIP".equals(action)) {
            occurrence.setSkippedAt(Instant.now(clock)); occurrence.setStatus(ReminderStatus.SKIPPED);
        } else throw ApiException.badRequest("Unsupported reminder review action");
        OccurrenceResponse response = map(occurrences.saveAndFlush(occurrence));
        acknowledgePeriod(occurrence);
        return response;
    }

    @Scheduled(fixedDelayString = "${wethrive.reminders.scheduler-delay:PT1M}")
    @Transactional
    public void generateDueOccurrences() {
        if (!properties.reminders().schedulerEnabled()) return;
        Instant now = Instant.now(clock);
        for (ReminderPreference preference : preferences.lockDue(now, properties.reminders().batchSize())) {
            try {
                generate(preference, now);
            } catch (RuntimeException ex) {
                // Keep the claimed intended run unchanged. A retry therefore probes the same
                // idempotency key whether the failure happened before or after occurrence flush.
            }
        }
        for (BudgetReminderSchedule schedule : budgetSchedules.lockDue(now, properties.reminders().batchSize())) {
            try {
                generate(schedule, now);
            } catch (RuntimeException ex) {
                // See preference retry handling above: the persisted intended run is the token.
            }
        }
    }

    @Transactional
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void recalculateBudgetPeriod(BudgetPeriodStateChanged event) {
        for (BudgetReminderSchedule schedule : budgetSchedules
                .findAllBySpaceIdAndTargetYearAndTargetMonthAndDeletedAtIsNull(
                        event.spaceId(), event.year(), event.month())) {
            if (event.exists()) {
                schedule.setCompletedAt(event.changedAt());
                if (schedule.isEnabled()) schedule.setNextRunAt(completionRecheckAt(event.changedAt()));
            } else if (schedule.getCompletedAt() != null) {
                schedule.setCompletedAt(null);
                if (schedule.isEnabled()) schedule.setNextRunAt(scheduleNextRun(schedule, event.changedAt()));
            } else {
                continue;
            }
            BudgetReminderSchedule saved = budgetSchedules.saveAndFlush(schedule);
            recordSchedule(saved, SyncOperationType.UPDATE, saved.getUserId(), false, map(saved));
        }
    }

    private void generate(ReminderPreference preference, Instant now) {
        Instant intendedRun = preference.getNextRunAt();
        boolean activeUser = users.findById(preference.getUserId())
                .filter(AppUser::isEnabled).filter(user -> user.getDeletedAt() == null).isPresent();
        boolean activeSpace = preference.getSpaceId() == null
                || access.can(preference.getSpaceId(), preference.getUserId(), Capability.VIEW);
        if (!activeUser || !activeSpace) {
            preference.setEnabled(false);
            preference.setNextRunAt(now.plusSeconds(3_155_760_000L));
            preferences.save(preference);
            return;
        }
        ZoneId zone = ZoneId.of(preference.getTimeZone());
        ZonedDateTime localNow = now.atZone(zone);
        ZonedDateTime intendedLocal = intendedRun.atZone(zone);
        if (inQuietHours(preference, localNow.toLocalTime())) {
            LocalDate endDate = localNow.toLocalDate();
            if (!preference.getQuietHoursEnd().isAfter(localNow.toLocalTime())) endDate = endDate.plusDays(1);
            preference.setNextRunAt(ZonedDateTime.of(endDate, preference.getQuietHoursEnd(), zone).toInstant());
            preferences.save(preference);
            return;
        }
        String targetPeriod = preference.getReminderType() == ReminderType.MONTHLY_BUDGET_SETUP
                ? targetBudgetPeriod(preference, intendedLocal).toString() : intendedLocal.toLocalDate().toString();
        if (preference.getReminderType() == ReminderType.MONTHLY_BUDGET_SETUP
                && preference.getSpaceId() != null) {
            YearMonth period = YearMonth.parse(targetPeriod);
            if (budgetSchedules.existsByUserIdAndSpaceIdAndTargetYearAndTargetMonthAndDeletedAtIsNull(
                    preference.getUserId(), preference.getSpaceId(), period.getYear(), period.getMonthValue())) {
                preference.setNextRunAt(nextRun(preference, now));
                preferences.save(preference);
                return;
            }
        }
        if (preference.getReminderType() == ReminderType.SPENDING_CHECK_IN && preference.isSuppressWhenReviewed()) {
            LocalDate reviewDate = intendedLocal.toLocalDate();
            boolean reviewed = preference.getSpaceId() == null
                    ? periodReviews.existsByUserIdAndPeriodDateAndDeletedAtIsNull(preference.getUserId(), reviewDate)
                    : periodReviews.existsByUserIdAndSpaceIdAndPeriodDateAndDeletedAtIsNull(
                            preference.getUserId(), preference.getSpaceId(), reviewDate);
            if (reviewed) { preference.setNextRunAt(nextRun(preference, now)); preferences.save(preference); return; }
        }
        if (preference.getReminderType() == ReminderType.MONTHLY_BUDGET_SETUP && preference.getSpaceId() != null) {
            YearMonth period = YearMonth.parse(targetPeriod);
            if (budgets.existsBySpaceIdAndYearAndMonthAndDeletedAtIsNull(preference.getSpaceId(), period.getYear(), period.getMonthValue())) {
                preference.setNextRunAt(nextRun(preference, now)); preferences.save(preference); return;
            }
        }
        String idempotency = preference.getId() + ":" + preference.getReminderType() + ":" + targetPeriod
                + (preference.isRepeatUntilCompleted() ? ":" + intendedLocal.toLocalDate() : "");
        if (!occurrences.existsByIdempotencyKey(idempotency)) {
            ReminderOccurrence occurrence = new ReminderOccurrence(); occurrence.setReminderPreferenceId(preference.getId());
            occurrence.setUserId(preference.getUserId()); occurrence.setSpaceId(preference.getSpaceId());
            occurrence.setTargetPeriod(targetPeriod); occurrence.setScheduledFor(intendedRun); occurrence.setIdempotencyKey(idempotency);
            if (preference.isInAppEnabled()) {
                occurrence.setStatus(ReminderStatus.DELIVERED);
                occurrence.setDeliveredAt(now);
            } else {
                occurrence.setStatus(ReminderStatus.PENDING);
            }
            String spaceName = preference.getSpaceId() == null ? "your space" : spaces.requireSpace(preference.getSpaceId()).getName();
            if (preference.getReminderType() == ReminderType.MONTHLY_BUDGET_SETUP) {
                occurrence.setTitle("Monthly budget reminder"); occurrence.setBody("Your budget for " + spaceName + " is still waiting to be created.");
            } else {
                occurrence.setTitle("Spending check-in"); occurrence.setBody("Take a moment to review recent spending in " + spaceName + ".");
            }
            ReminderOccurrence saved = occurrences.saveAndFlush(occurrence);
            recordOccurrence(saved, preference.getUserId());
            if (preference.isPushEnabled()) events.publishEvent(new ReminderPushRequested(saved.getId()));
        }
        preference.setNextRunAt(preference.isRepeatUntilCompleted() && preference.getReminderType() == ReminderType.MONTHLY_BUDGET_SETUP
                ? intendedLocal.plusDays(Math.max(1, preference.getIntervalValue() == null ? 1 : preference.getIntervalValue())).with(preference.getLocalTime()).toInstant()
                : nextRun(preference, now));
        preferences.save(preference);
    }

    private void generate(BudgetReminderSchedule schedule, Instant now) {
        ReminderPreference preference;
        try {
            preference = requireMonthlyPreference(schedule.getUserId(), schedule.getReminderPreferenceId(), schedule.getSpaceId());
        } catch (ApiException ex) {
            schedule.setEnabled(false);
            budgetSchedules.save(schedule);
            return;
        }
        boolean activeUser = users.findById(schedule.getUserId())
                .filter(AppUser::isEnabled).filter(user -> user.getDeletedAt() == null).isPresent();
        boolean activeSpace = access.can(schedule.getSpaceId(), schedule.getUserId(), Capability.EDIT_FINANCE);
        if (!activeUser || !activeSpace) {
            schedule.setEnabled(false);
            budgetSchedules.save(schedule);
            return;
        }
        if (!preference.isEnabled() || (preference.getPauseUntil() != null && preference.getPauseUntil().isAfter(now))) {
            schedule.setNextRunAt(preference.getPauseUntil() != null && preference.getPauseUntil().isAfter(now)
                    ? preference.getPauseUntil() : now.plusSeconds(300));
            budgetSchedules.save(schedule);
            return;
        }

        YearMonth target = YearMonth.of(schedule.getTargetYear(), schedule.getTargetMonth());
        Instant intendedRun = schedule.getNextRunAt();
        boolean complete = budgets.existsBySpaceIdAndYearAndMonthAndDeletedAtIsNull(
                schedule.getSpaceId(), target.getYear(), target.getMonthValue());
        if (complete) {
            schedule.setCompletedAt(now);
            // Keep the user's enabled choice intact. A deletion event can then reactivate a
            // schedule that was suppressed before it ever emitted its one-shot occurrence.
            schedule.setNextRunAt(completionRecheckAt(now));
            budgetSchedules.save(schedule);
            return;
        }

        schedule.setCompletedAt(null);
        String idempotency = preference.getId() + ":" + schedule.getId() + ":" + target + ":" + intendedRun;
        if (!occurrences.existsByIdempotencyKey(idempotency)) {
            ReminderOccurrence occurrence = new ReminderOccurrence();
            occurrence.setReminderPreferenceId(preference.getId());
            occurrence.setBudgetReminderScheduleId(schedule.getId());
            occurrence.setUserId(schedule.getUserId());
            occurrence.setSpaceId(schedule.getSpaceId());
            occurrence.setTargetPeriod(target.toString());
            occurrence.setScheduledFor(intendedRun);
            occurrence.setIdempotencyKey(idempotency);
            occurrence.setStatus(preference.isInAppEnabled() ? ReminderStatus.DELIVERED : ReminderStatus.PENDING);
            if (preference.isInAppEnabled()) occurrence.setDeliveredAt(now);
            String spaceName = spaces.requireSpace(schedule.getSpaceId()).getName();
            occurrence.setTitle("Budget reminder for " + target);
            occurrence.setBody("Your " + target + " budget for " + spaceName + " is still waiting to be created.");
            ReminderOccurrence saved = occurrences.saveAndFlush(occurrence);
            recordOccurrence(saved, schedule.getUserId());
            if (preference.isPushEnabled()) events.publishEvent(new ReminderPushRequested(saved.getId()));
        }
        if (schedule.isRepeatUntilBudgetExists()) {
            schedule.setNextRunAt(advanceSchedule(schedule, intendedRun));
        } else {
            schedule.setEnabled(false);
        }
        budgetSchedules.save(schedule);
    }

    Instant nextRun(ReminderPreference p, Instant after) {
        ZoneId zone = ZoneId.of(p.getTimeZone()); ZonedDateTime local = after.atZone(zone);
        LocalDate date = local.toLocalDate(); LocalTime time = p.getLocalTime();
        ZonedDateTime candidate = ZonedDateTime.of(date, time, zone);
        if (!candidate.toInstant().isAfter(after)) candidate = candidate.plusDays(1);
        switch (p.getFrequencyType()) {
            case DAILY -> { }
            case INTERVAL_DAYS -> candidate = candidate.plusDays(Math.max(1, p.getIntervalValue() == null ? 1 : p.getIntervalValue()) - 1L);
            case WEEKLY, SELECTED_DAYS -> {
                Set<DayOfWeek> days = parseDays(p.getSelectedWeekdays());
                if (days.isEmpty()) days = EnumSet.of(local.getDayOfWeek());
                for (int i = 0; i < 7 && !days.contains(candidate.getDayOfWeek()); i++) candidate = candidate.plusDays(1);
            }
            case MONTHLY, CONDITIONAL -> {
                YearMonth period = YearMonth.from(local);
                LocalDate selected = scheduleDate(p, period);
                candidate = ZonedDateTime.of(selected, time, zone);
                if (!candidate.toInstant().isAfter(after)) {
                    period = period.plusMonths(1); selected = scheduleDate(p, period);
                    candidate = ZonedDateTime.of(selected, time, zone);
                }
            }
        }
        return candidate.toInstant();
    }

    private void validateSpace(UUID userId, UUID spaceId, ReminderType type) {
        if (spaceId == null) return;
        access.require(spaceId, userId, type == ReminderType.MONTHLY_BUDGET_SETUP ? Capability.EDIT_FINANCE : Capability.VIEW);
    }
    private boolean inQuietHours(ReminderPreference p, LocalTime now) {
        LocalTime start = p.getQuietHoursStart(), end = p.getQuietHoursEnd();
        if (start == null || end == null || start.equals(end)) return false;
        return start.isBefore(end) ? !now.isBefore(start) && now.isBefore(end) : !now.isBefore(start) || now.isBefore(end);
    }
    private void apply(ReminderPreference p, UUID spaceId, ReminderType type, boolean enabled, FrequencyType frequency,
                       Integer interval, Set<DayOfWeek> days, LocalTime localTime, String zone, Integer dayOfMonth,
                       Integer before, Integer after, boolean repeat, LocalTime quietStart, LocalTime quietEnd,
                       boolean push, boolean inApp, boolean detailed, boolean suppressWhenReviewed) {
        try { ZoneId.of(zone); } catch (Exception ex) { throw ApiException.badRequest("Select a valid IANA time zone"); }
        if (!push && !inApp) throw ApiException.badRequest("Select at least one reminder channel");
        p.setSpaceId(spaceId); p.setReminderType(type); p.setEnabled(enabled); p.setFrequencyType(frequency);
        p.setIntervalValue(interval); p.setSelectedWeekdays(days == null ? "" : days.stream().sorted().map(DayOfWeek::name).collect(Collectors.joining(",")));
        p.setLocalTime(localTime); p.setTimeZone(zone); p.setDayOfMonth(dayOfMonth); p.setDaysBeforeMonthEnd(before);
        p.setDaysAfterMonthStart(after); p.setRepeatUntilCompleted(repeat); p.setQuietHoursStart(quietStart); p.setQuietHoursEnd(quietEnd);
        p.setPushEnabled(push); p.setInAppEnabled(inApp); p.setDetailedContentEnabled(detailed);
        p.setSuppressWhenReviewed(suppressWhenReviewed);
    }

    private void apply(BudgetReminderSchedule schedule, int targetYear, int targetMonth,
                       LocalDate initialReminderDate, LocalTime localTime, String timeZone,
                       boolean repeatUntilBudgetExists, int repeatIntervalDays, boolean enabled) {
        try { ZoneId.of(timeZone); } catch (Exception ex) {
            throw ApiException.badRequest("Select a valid IANA time zone");
        }
        schedule.setTargetYear(targetYear);
        schedule.setTargetMonth(targetMonth);
        schedule.setInitialReminderDate(initialReminderDate);
        schedule.setLocalTime(localTime);
        schedule.setTimeZone(timeZone);
        schedule.setRepeatUntilBudgetExists(repeatUntilBudgetExists);
        schedule.setRepeatIntervalDays(Math.max(1, repeatIntervalDays));
        schedule.setEnabled(enabled);
    }

    Instant scheduleNextRun(BudgetReminderSchedule schedule, Instant after) {
        ZoneId zone = ZoneId.of(schedule.getTimeZone());
        ZonedDateTime candidate = ZonedDateTime.of(
                schedule.getInitialReminderDate(), schedule.getLocalTime(), zone);
        if (!schedule.isEnabled() || candidate.toInstant().isAfter(after)) return candidate.toInstant();
        if (!schedule.isRepeatUntilBudgetExists()) return after;
        long days = java.time.temporal.ChronoUnit.DAYS.between(
                schedule.getInitialReminderDate(), after.atZone(zone).toLocalDate());
        long intervals = Math.max(0, days / schedule.getRepeatIntervalDays());
        candidate = ZonedDateTime.of(
                schedule.getInitialReminderDate().plusDays(intervals * schedule.getRepeatIntervalDays()),
                schedule.getLocalTime(), zone);
        if (!candidate.toInstant().isAfter(after)) {
            candidate = ZonedDateTime.of(candidate.toLocalDate().plusDays(schedule.getRepeatIntervalDays()),
                    schedule.getLocalTime(), zone);
        }
        return candidate.toInstant();
    }

    Instant advanceSchedule(BudgetReminderSchedule schedule, Instant intendedRun) {
        ZoneId zone = ZoneId.of(schedule.getTimeZone());
        LocalDate nextDate = intendedRun.atZone(zone).toLocalDate().plusDays(schedule.getRepeatIntervalDays());
        return ZonedDateTime.of(nextDate, schedule.getLocalTime(), zone).toInstant();
    }
    private Instant completionRecheckAt(Instant now) { return now.plus(COMPLETION_RECHECK_DELAY); }
    private void applyBudgetCompletionState(BudgetReminderSchedule schedule, Instant now) {
        boolean complete = budgets.existsBySpaceIdAndYearAndMonthAndDeletedAtIsNull(
                schedule.getSpaceId(), schedule.getTargetYear(), schedule.getTargetMonth());
        schedule.setCompletedAt(complete ? now : null);
        schedule.setNextRunAt(complete && schedule.isEnabled()
                ? completionRecheckAt(now)
                : scheduleNextRun(schedule, now));
    }
    private Set<DayOfWeek> parseDays(String value) { if (value == null || value.isBlank()) return EnumSet.noneOf(DayOfWeek.class); return java.util.Arrays.stream(value.split(",")).map(DayOfWeek::valueOf).collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class))); }
    private ReminderPreference requirePreference(UUID userId, UUID id) { return preferences.findByIdAndUserIdAndDeletedAtIsNull(id, userId).orElseThrow(() -> ApiException.notFound("Reminder preference")); }
    private void requireEditForMutation(UUID userId, ReminderPreference preference) {
        if (preference.getSpaceId() != null) {
            access.require(preference.getSpaceId(), userId, Capability.EDIT_FINANCE);
        }
    }
    private ReminderPreference requireMonthlyPreference(UUID userId, UUID id, UUID spaceId) {
        ReminderPreference preference = requirePreference(userId, id);
        if (preference.getReminderType() != ReminderType.MONTHLY_BUDGET_SETUP
                || preference.getSpaceId() == null || !preference.getSpaceId().equals(spaceId)) {
            throw ApiException.badRequest("Select a monthly budget reminder preference for this space");
        }
        access.require(spaceId, userId, Capability.EDIT_FINANCE);
        return preference;
    }
    private BudgetReminderSchedule requireBudgetSchedule(UUID userId, UUID id) {
        return budgetSchedules.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> ApiException.notFound("Budget reminder schedule"));
    }
    private ReminderOccurrence requireOccurrence(UUID userId, UUID id) { return occurrences.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Reminder")); }
    private PreferenceResponse map(ReminderPreference p) { return new PreferenceResponse(p.getId(), p.getSpaceId(), p.getReminderType(), p.isEnabled(), p.getFrequencyType(), p.getIntervalValue(), parseDays(p.getSelectedWeekdays()), p.getLocalTime(), p.getTimeZone(), p.getDayOfMonth(), p.getDaysBeforeMonthEnd(), p.getDaysAfterMonthStart(), p.isRepeatUntilCompleted(), p.getQuietHoursStart(), p.getQuietHoursEnd(), p.isPushEnabled(), p.isInAppEnabled(), p.isDetailedContentEnabled(), p.isSuppressWhenReviewed(), p.getPauseUntil(), p.getNextRunAt(), p.getVersion()); }
    private PeriodReviewResponse map(ReminderPeriodReview review) {
        return new PeriodReviewResponse(review.getId(), review.getUserId(), review.getSpaceId(),
                review.getPeriodDate(), review.getReviewedAt(), review.getCreatedAt(),
                review.getUpdatedAt(), review.getVersion());
    }
    private BudgetScheduleResponse map(BudgetReminderSchedule schedule) {
        return new BudgetScheduleResponse(schedule.getId(), schedule.getReminderPreferenceId(), schedule.getUserId(),
                schedule.getSpaceId(), schedule.getTargetYear(), schedule.getTargetMonth(),
                schedule.getInitialReminderDate(), schedule.getLocalTime(), schedule.getTimeZone(),
                schedule.isRepeatUntilBudgetExists(), schedule.getRepeatIntervalDays(), schedule.isEnabled(),
                schedule.getNextRunAt(), schedule.getCompletedAt(), schedule.getCreatedAt(),
                schedule.getUpdatedAt(), schedule.getVersion());
    }
    LocalDate scheduleDate(ReminderPreference p, YearMonth period) {
        if (p.getDaysBeforeMonthEnd() != null) return period.atEndOfMonth().minusDays(Math.min(p.getDaysBeforeMonthEnd(), period.lengthOfMonth() - 1));
        if (p.getDaysAfterMonthStart() != null) return period.atDay(Math.min(period.lengthOfMonth(), p.getDaysAfterMonthStart() + 1));
        int requested = p.getDayOfMonth() == null ? 1 : p.getDayOfMonth();
        return period.atDay(Math.min(requested, period.lengthOfMonth()));
    }
    private YearMonth targetBudgetPeriod(ReminderPreference p, ZonedDateTime local) {
        YearMonth current = YearMonth.from(local);
        if (p.getDaysBeforeMonthEnd() != null || (p.getDayOfMonth() != null && p.getDayOfMonth() >= 20)) return current.plusMonths(1);
        return current;
    }
    private OccurrenceResponse map(ReminderOccurrence o) { return new OccurrenceResponse(o.getId(), o.getReminderPreferenceId(), o.getBudgetReminderScheduleId(), o.getSpaceId(), o.getTargetPeriod(), o.getScheduledFor(), o.getStatus(), o.getTitle(), o.getBody(), o.getAcknowledgedAt(), o.getSkippedAt(), o.getVersion()); }

    private void recordPreference(ReminderPreference preference, SyncOperationType operation, UUID actor,
                                  boolean tombstone, Object payload) {
        if (preference.getSpaceId() == null) return;
        changes.orderedStream().forEach(recorder -> recorder.record(preference.getSpaceId(), "REMINDER_PREFERENCE",
                preference.getId(), operation, preference.getVersion(), actor, tombstone, payload));
    }

    private void recordSchedule(BudgetReminderSchedule schedule, SyncOperationType operation, UUID actor,
                                boolean tombstone, Object payload) {
        changes.orderedStream().forEach(recorder -> recorder.record(schedule.getSpaceId(),
                "BUDGET_REMINDER_SCHEDULE", schedule.getId(), operation, schedule.getVersion(), actor,
                tombstone, payload));
    }

    private void recordOccurrence(ReminderOccurrence occurrence, UUID actor) {
        if (occurrence.getSpaceId() == null) return;
        OccurrenceResponse response = map(occurrence);
        changes.orderedStream().forEach(recorder -> recorder.record(occurrence.getSpaceId(),
                "REMINDER_OCCURRENCE", occurrence.getId(), SyncOperationType.CREATE,
                occurrence.getVersion(), actor, false, response));
    }

    private void recordPeriodReview(ReminderPeriodReview review, UUID actor, PeriodReviewResponse response) {
        changes.orderedStream().forEach(recorder -> recorder.record(review.getSpaceId(),
                "REMINDER_PERIOD_REVIEW", review.getId(), SyncOperationType.CREATE,
                review.getVersion(), actor, false, response));
    }

    private void acknowledgePeriod(ReminderOccurrence occurrence) {
        if (occurrence.getSpaceId() == null || occurrence.getTargetPeriod() == null
                || !occurrence.getTargetPeriod().matches("\\d{4}-\\d{2}-\\d{2}")) return;
        ReminderPreference preference = preferences.findByIdAndUserIdAndDeletedAtIsNull(
                occurrence.getReminderPreferenceId(), occurrence.getUserId()).orElse(null);
        if (preference == null || preference.getReminderType() != ReminderType.SPENDING_CHECK_IN) return;
        LocalDate date = LocalDate.parse(occurrence.getTargetPeriod());
        if (periodReviews.existsByUserIdAndSpaceIdAndPeriodDateAndDeletedAtIsNull(
                occurrence.getUserId(), occurrence.getSpaceId(), date)) return;
        ReminderPeriodReview review = new ReminderPeriodReview();
        review.setId(UUID.randomUUID());
        review.setUserId(occurrence.getUserId());
        review.setSpaceId(occurrence.getSpaceId());
        review.setPeriodDate(date);
        review.setReviewedAt(Instant.now(clock));
        ReminderPeriodReview saved = periodReviews.saveAndFlush(review);
        recordPeriodReview(saved, saved.getUserId(), map(saved));
    }

    private void auditPreference(UUID actor, ReminderPreference preference, String action) {
        if (preference.getSpaceId() == null) {
            audit.record(actor, action, AuditResult.SUCCESS, "Reminder preference changed");
        } else {
            audit.record(actor, preference.getSpaceId(), action, "REMINDER_PREFERENCE", preference.getId(),
                    AuditResult.SUCCESS, "Reminder preference changed");
        }
    }
}
