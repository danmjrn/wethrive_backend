package solutions.shapeit.wethrive.finance.event;

import java.time.Instant;
import java.util.UUID;

/** Published after a budget period starts or stops existing so completion-aware reminders converge. */
public record BudgetPeriodStateChanged(UUID spaceId, int year, int month, boolean exists, Instant changedAt) {}
