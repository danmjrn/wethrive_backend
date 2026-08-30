package solutions.shapeit.wethrive.sync.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import java.time.Instant;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SyncOperationType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SyncStatus;
import tools.jackson.databind.JsonNode;

public final class SyncDtos {
    private SyncDtos() {}
    public record ClientOperation(@NotNull UUID operationId, @NotNull UUID userId, @NotNull UUID deviceId,
                                  @NotNull UUID spaceId, @NotBlank @Size(max = 60) String moduleKey,
                                  @NotBlank @Size(max = 60) String entityType, @NotNull UUID entityId,
                                  @NotNull SyncOperationType operationType, @NotNull JsonNode payload,
                                  Long baseVersion, @NotNull Instant clientTimestamp) {}
    public record PushRequest(@NotEmpty @Size(max = 500) List<@Valid ClientOperation> operations) {}
    public record OperationResult(UUID operationId, UUID entityId, SyncStatus status, Long serverVersion,
                                  JsonNode serverPayload, String errorCode, String message) {}
    public record PushResponse(List<OperationResult> results, Instant serverTime) {}
    public record Change(long sequenceId, UUID spaceId, String entityType, UUID entityId,
                         SyncOperationType operationType, long entityVersion, Instant changedAt,
                         JsonNode payload, boolean tombstone) {}
    public record PullResponse(long previousCursor, long nextCursor, boolean hasMore,
                               List<UUID> authorizedSpaceIds, long authorizationVersion, String authorizationFingerprint,
                               List<Change> changes, Instant serverTime) {}
    public record FullResyncRequest(@NotNull UUID deviceId) {}
    public record FullResyncResponse(long cursor, long authorizationVersion, String authorizationFingerprint,
                                     List<UUID> authorizedSpaceIds,
                                     Map<String, Object> data, Instant serverTime) {}
    public record BudgetStatusSyncRequest(@NotNull BudgetStatus status, @NotNull long version) {}
    public record TransferOwnershipSyncRequest(@NotNull UUID newOwnerMembershipId, @NotNull long version) {}
    public record BudgetCopySyncRequest(@NotNull UUID id, @NotNull UUID sourceBudgetId,
                                        @Min(2000) @Max(2200) int year, @Min(1) @Max(12) int month,
                                        @NotBlank @Size(max = 120) String name, boolean recurringOnly,
                                        boolean copyPlannedAmounts, Boolean copyBudgetItemStructure,
                                        Boolean copyRecurringIncomeDefinitions, Boolean adjustScheduledIncomeDates,
                                        Boolean copyFundingPlans) {}
    public record ReminderPauseSyncRequest(@NotNull Instant until, @NotNull long version) {}
    public record ReminderResumeSyncRequest(@NotNull long version) {}
    public record ReminderReviewSyncRequest(@Pattern(regexp = "ACKNOWLEDGE|SKIP") String action,
                                            @NotNull long version) {}
}
