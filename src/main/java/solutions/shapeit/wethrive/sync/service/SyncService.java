package solutions.shapeit.wethrive.sync.service;

import jakarta.transaction.Transactional;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import solutions.shapeit.wethrive.common.domain.DomainEnums.MembershipStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SyncOperationType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SyncStatus;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.device.repository.DeviceRepository;
import solutions.shapeit.wethrive.device.service.OfflineGrantService;
import solutions.shapeit.wethrive.identity.service.AuthService;
import solutions.shapeit.wethrive.identity.service.SettingsService;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetItemRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetItemUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.CategoryRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.CategoryUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeDeductionRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeDeductionResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeDeductionUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeTypeRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeTypeUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptDeductionRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptDeductionResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptDeductionUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeStateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.FundingAllocationRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.FundingAllocationUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.SpendingRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.SpendingUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.RestoreSpendingRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.VersionRequest;
import solutions.shapeit.wethrive.finance.service.BudgetService;
import solutions.shapeit.wethrive.finance.service.ReferenceFinanceService;
import solutions.shapeit.wethrive.finance.service.SpendingService;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.PreferenceRequest;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.PreferenceUpdateRequest;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.BudgetScheduleRequest;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.BudgetScheduleUpdateRequest;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.PeriodReviewRequest;
import solutions.shapeit.wethrive.reminder.service.ReminderService;
import solutions.shapeit.wethrive.identity.dto.SettingsDtos.SettingsRequest;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.CreateSpaceRequest;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.UpdateSpaceRequest;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.InvitationRequest;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.ChangeRoleRequest;
import solutions.shapeit.wethrive.space.service.InvitationService;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetCopyRequest;
import solutions.shapeit.wethrive.sync.dto.SyncDtos.BudgetStatusSyncRequest;
import solutions.shapeit.wethrive.sync.dto.SyncDtos.TransferOwnershipSyncRequest;
import solutions.shapeit.wethrive.sync.dto.SyncDtos.BudgetCopySyncRequest;
import solutions.shapeit.wethrive.sync.dto.SyncDtos.ReminderPauseSyncRequest;
import solutions.shapeit.wethrive.sync.dto.SyncDtos.ReminderReviewSyncRequest;
import solutions.shapeit.wethrive.sync.dto.SyncDtos.ReminderResumeSyncRequest;
import solutions.shapeit.wethrive.space.service.MembershipService;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;
import solutions.shapeit.wethrive.space.service.SpaceAccessService.Capability;
import solutions.shapeit.wethrive.space.service.SpaceService;
import solutions.shapeit.wethrive.space.repository.SpaceMembershipRepository;
import solutions.shapeit.wethrive.sync.dto.SyncDtos.Change;
import solutions.shapeit.wethrive.sync.dto.SyncDtos.ClientOperation;
import solutions.shapeit.wethrive.sync.dto.SyncDtos.FullResyncResponse;
import solutions.shapeit.wethrive.sync.dto.SyncDtos.OperationResult;
import solutions.shapeit.wethrive.sync.dto.SyncDtos.PullResponse;
import solutions.shapeit.wethrive.sync.dto.SyncDtos.PushResponse;
import solutions.shapeit.wethrive.sync.entity.ServerChangeLog;
import solutions.shapeit.wethrive.sync.entity.SyncOperation;
import solutions.shapeit.wethrive.sync.repository.ServerChangeLogRepository;
import solutions.shapeit.wethrive.sync.repository.SyncOperationRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class SyncService {
    private final SyncOperationRepository operations; private final ServerChangeLogRepository changes;
    private final DeviceRepository devices; private final SpaceMembershipRepository memberships; private final SpaceAccessService access;
    private final SpaceService spaces; private final MembershipService memberService; private final ReferenceFinanceService references;
    private final BudgetService budgets; private final SpendingService spending; private final ReminderService reminders;
    private final ObjectMapper mapper; private final Clock clock; private final TransactionTemplate transactions;
    private final OfflineGrantService authorizations;
    private final AuthService auth;
    private final SettingsService userSettings;
    private final InvitationService invitations;
    private final Validator validator;

    public SyncService(SyncOperationRepository operations, ServerChangeLogRepository changes, DeviceRepository devices,
                       SpaceMembershipRepository memberships, SpaceAccessService access, SpaceService spaces,
                       MembershipService memberService, ReferenceFinanceService references, BudgetService budgets,
                       SpendingService spending, ReminderService reminders, ObjectMapper mapper, Clock clock,
                       PlatformTransactionManager transactionManager, OfflineGrantService authorizations,
                       AuthService auth, SettingsService userSettings, InvitationService invitations,
                       Validator validator) {
        this.operations = operations; this.changes = changes; this.devices = devices; this.memberships = memberships;
        this.access = access; this.spaces = spaces; this.memberService = memberService; this.references = references;
        this.budgets = budgets; this.spending = spending; this.reminders = reminders; this.mapper = mapper; this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.authorizations = authorizations;
        this.auth = auth;
        this.userSettings = userSettings;
        this.invitations = invitations;
        this.validator = validator;
    }

    public PushResponse push(UUID actorId, List<ClientOperation> clientOperations) {
        List<OperationResult> results = new ArrayList<>();
        for (ClientOperation operation : clientOperations) results.add(apply(actorId, operation));
        return new PushResponse(results, Instant.now(clock));
    }

    private OperationResult apply(UUID actorId, ClientOperation operation) {
        // Authenticate the complete operation scope before an operation-id lookup can reveal any result metadata.
        validateOperation(actorId, operation);
        try {
            return transactions.execute(status -> {
                SyncOperation existing = operations.findById(operation.operationId()).orElse(null);
                if (existing != null) {
                    requireSameOperation(actorId, operation, existing);
                    if (existing.getStatus() == SyncStatus.APPLIED) return duplicate(actorId, operation, existing);
                    operations.delete(existing);
                    operations.flush();
                }
                long version = dispatch(actorId, operation);
                SyncOperation stored = store(operation, actorId);
                stored.setStatus(SyncStatus.APPLIED);
                stored.setResultVersion(version);
                operations.saveAndFlush(stored);
                return new OperationResult(operation.operationId(), operation.entityId(), SyncStatus.APPLIED,
                        version, null, null, null);
            });
        } catch (ApiException ex) {
            String normalizedType = normalizeType(operation.entityType());
            boolean terminalInvariantCreate = ex.status() == HttpStatus.CONFLICT
                    && operation.operationType() == SyncOperationType.CREATE
                    && (("income_already_received".equals(ex.code()) && "INCOME_RECEIPT".equals(normalizedType))
                    || ("refund_capacity_exceeded".equals(ex.code()) && "SPENDING_ENTRY".equals(normalizedType)));
            SyncStatus status = ex.status() == HttpStatus.CONFLICT && !terminalInvariantCreate
                    ? SyncStatus.CONFLICT : SyncStatus.REJECTED;
            Optional<ServerChangeLog> latest = status == SyncStatus.CONFLICT
                    ? latestAuthorizedChange(actorId, operation) : Optional.empty();
            return new OperationResult(operation.operationId(), operation.entityId(), status,
                    latest.map(ServerChangeLog::getEntityVersion).orElse(null),
                    latest.map(change -> parse(change.getPayload())).orElse(null),
                    ex.code(), ex.getMessage());
        } catch (DataIntegrityViolationException ex) {
            // A concurrent request with the same operation id waits for the winner and can then
            // return its committed receipt. Other database constraints are ordinary data conflicts.
            SyncOperation existing = operations.findById(operation.operationId()).orElse(null);
            if (existing != null) {
                requireSameOperation(actorId, operation, existing);
                if (existing.getStatus() == SyncStatus.APPLIED) return duplicate(actorId, operation, existing);
            }
            Optional<ServerChangeLog> latest = latestAuthorizedChange(actorId, operation);
            return new OperationResult(operation.operationId(), operation.entityId(), SyncStatus.REJECTED,
                    latest.map(ServerChangeLog::getEntityVersion).orElse(null), null,
                    "data_conflict", "The synchronized data conflicts with a server constraint");
        } catch (RuntimeException ex) {
            return new OperationResult(operation.operationId(), operation.entityId(), SyncStatus.REJECTED, null, null,
                    "server_error", "The operation was rolled back and can be retried with the same operation id");
        }
    }

    private OperationResult duplicate(UUID actorId, ClientOperation operation, SyncOperation existing) {
        JsonNode latestPayload = latestAuthorizedChange(actorId, operation)
                .map(change -> parse(change.getPayload())).orElse(null);
        return new OperationResult(operation.operationId(), operation.entityId(), SyncStatus.DUPLICATE,
                existing.getResultVersion(), latestPayload, null,
                "Operation already applied");
    }

    private void requireSameOperation(UUID actorId, ClientOperation incoming, SyncOperation stored) {
        boolean same = stored.getUserId().equals(actorId)
                && stored.getUserId().equals(incoming.userId())
                && stored.getDeviceId().equals(incoming.deviceId())
                && stored.getSpaceId().equals(incoming.spaceId())
                && stored.getModuleKey().equalsIgnoreCase(incoming.moduleKey())
                && stored.getEntityType().equals(normalizeType(incoming.entityType()))
                && stored.getEntityId().equals(incoming.entityId())
                && stored.getOperationType() == incoming.operationType()
                && java.util.Objects.equals(stored.getBaseVersion(), incoming.baseVersion())
                && stored.getClientTimestamp().equals(incoming.clientTimestamp())
                && stored.getPayload().equals(incoming.payload().toString());
        if (!same) throw ApiException.conflict("The operation id is already bound to a different operation");
    }

    private void validateOperation(UUID actorId, ClientOperation operation) {
        if (!actorId.equals(operation.userId())) throw ApiException.forbidden();
        if ("USER_SETTINGS".equals(normalizeType(operation.entityType())) && !actorId.equals(operation.entityId())) {
            throw ApiException.badRequest("USER_SETTINGS entityId must be the authenticated user id");
        }
        devices.findByIdAndUserIdAndRevokedAtIsNull(operation.deviceId(), actorId).orElseThrow(() -> ApiException.notFound("Device"));
        String type = normalizeType(operation.entityType());
        Capability capability = switch (type) {
            case "SPENDING_ENTRY" -> Capability.RECORD_SPENDING;
            case "REMINDER_PREFERENCE" -> operation.operationType() == SyncOperationType.DELETE
                    ? Capability.EDIT_FINANCE : Capability.VIEW;
            case "REMINDER_PAUSE", "REMINDER_RESUME" -> Capability.EDIT_FINANCE;
            case "REMINDER_REVIEW", "REMINDER_PERIOD_REVIEW", "USER_SETTINGS" -> Capability.VIEW;
            case "INVITATION" -> Capability.MANAGE_MEMBERS;
            case "MEMBERSHIP" -> operation.operationType() == SyncOperationType.DELETE
                    ? Capability.VIEW : Capability.MANAGE_MEMBERS;
            case "SPACE_OWNERSHIP" -> Capability.DELETE_SPACE;
            case "SPACE" -> operation.operationType() == SyncOperationType.CREATE ? null
                    : operation.operationType() == SyncOperationType.DELETE ? Capability.DELETE_SPACE : Capability.MANAGE_SPACE;
            default -> Capability.EDIT_FINANCE;
        };
        if (capability != null) access.require(operation.spaceId(), actorId, capability);
        if (!java.util.Set.of("finance", "reminder", "space", "identity", "settings")
                .contains(operation.moduleKey().toLowerCase(Locale.ROOT))) {
            throw ApiException.badRequest("Unsupported synchronization module");
        }
        if (operation.clientTimestamp().isAfter(Instant.now(clock).plusSeconds(86400))) throw ApiException.badRequest("Client timestamp is too far in the future");
        if (operation.operationType() != SyncOperationType.CREATE && operation.baseVersion() == null) throw ApiException.badRequest("baseVersion is required for updates and deletes");
    }

    private long dispatch(UUID actorId, ClientOperation op) {
        String type = normalizeType(op.entityType());
        if (op.operationType() == SyncOperationType.DELETE) return delete(actorId, op, type);
        if (op.operationType() == SyncOperationType.RESTORE) {
            if (!"SPENDING_ENTRY".equals(type)) throw ApiException.badRequest("Only spending entries support restore");
            return spending.restore(op.entityId(), actorId, requireBase(op, RestoreSpendingRequest.class)).version();
        }
        return switch (type) {
            case "BUDGET_MONTH" -> op.operationType() == SyncOperationType.CREATE
                    ? budgets.create(op.spaceId(), actorId, read(op, BudgetRequest.class)).version()
                    : budgets.update(op.entityId(), actorId, requireBase(op, BudgetUpdateRequest.class)).version();
            case "INCOME_ENTRY" -> op.operationType() == SyncOperationType.CREATE
                    ? budgets.createIncome(requireBudgetId(op.payload()), actorId, read(op, IncomeRequest.class)).version()
                    : budgets.updateIncome(op.entityId(), actorId, requireBase(op, IncomeUpdateRequest.class)).version();
            case "INCOME_DEDUCTION" -> op.operationType() == SyncOperationType.CREATE
                    ? budgets.createIncomeDeduction(requireIncomeId(op.payload()), actorId,
                            read(op, IncomeDeductionRequest.class)).version()
                    : budgets.updateIncomeDeduction(op.entityId(), actorId,
                            requireBase(op, IncomeDeductionUpdateRequest.class)).version();
            case "INCOME_RECEIPT" -> op.operationType() == SyncOperationType.CREATE
                    ? budgets.createReceipt(requireIncomeId(op.payload()), actorId, read(op, IncomeReceiptRequest.class)).version()
                    : budgets.updateReceipt(op.entityId(), actorId, requireBase(op, IncomeReceiptUpdateRequest.class)).version();
            case "INCOME_RECEIPT_DEDUCTION" -> op.operationType() == SyncOperationType.CREATE
                    ? budgets.createReceiptDeduction(requireReceiptId(op.payload()), actorId,
                            read(op, IncomeReceiptDeductionRequest.class)).version()
                    : budgets.updateReceiptDeduction(op.entityId(), actorId,
                            requireBase(op, IncomeReceiptDeductionUpdateRequest.class)).version();
            case "FUNDING_ALLOCATION" -> op.operationType() == SyncOperationType.CREATE
                    ? budgets.createFundingAllocation(requireBudgetItemId(op.payload()), actorId, read(op, FundingAllocationRequest.class)).version()
                    : budgets.updateFundingAllocation(op.entityId(), actorId, requireBase(op, FundingAllocationUpdateRequest.class)).version();
            case "INCOME_CANCEL" -> budgets.cancelIncome(op.entityId(), actorId, requireBase(op, IncomeStateRequest.class)).version();
            case "INCOME_RESTORE" -> budgets.restoreIncome(op.entityId(), actorId, requireBase(op, IncomeStateRequest.class)).version();
            case "BUDGET_ITEM" -> op.operationType() == SyncOperationType.CREATE
                    ? budgets.createItem(requireBudgetId(op.payload()), actorId, read(op, BudgetItemRequest.class)).version()
                    : budgets.updateItem(op.entityId(), actorId, requireBase(op, BudgetItemUpdateRequest.class)).version();
            case "SPENDING_ENTRY" -> op.operationType() == SyncOperationType.CREATE
                    ? spending.create(actorId, read(op, SpendingRequest.class)).version()
                    : spending.update(op.entityId(), actorId, requireBase(op, SpendingUpdateRequest.class)).version();
            case "CATEGORY" -> op.operationType() == SyncOperationType.CREATE
                    ? references.createCategory(op.spaceId(), actorId, read(op, CategoryRequest.class)).version()
                    : references.updateCategory(op.entityId(), actorId, requireBase(op, CategoryUpdateRequest.class)).version();
            case "INCOME_TYPE" -> op.operationType() == SyncOperationType.CREATE
                    ? references.createIncomeType(op.spaceId(), actorId, read(op, IncomeTypeRequest.class)).version()
                    : references.updateIncomeType(op.entityId(), actorId, requireBase(op, IncomeTypeUpdateRequest.class)).version();
            case "REMINDER_PREFERENCE" -> op.operationType() == SyncOperationType.CREATE
                    ? reminders.create(actorId, read(op, PreferenceRequest.class)).version()
                    : reminders.update(actorId, op.entityId(), requireBase(op, PreferenceUpdateRequest.class)).version();
            case "BUDGET_REMINDER_SCHEDULE" -> op.operationType() == SyncOperationType.CREATE
                    ? reminders.createBudgetSchedule(actorId, read(op, BudgetScheduleRequest.class)).version()
                    : reminders.updateBudgetSchedule(actorId, op.entityId(),
                            requireBase(op, BudgetScheduleUpdateRequest.class)).version();
            case "REMINDER_PERIOD_REVIEW" -> {
                if (op.operationType() != SyncOperationType.CREATE) {
                    throw ApiException.badRequest("Period reviews are append-only");
                }
                yield reminders.reviewPeriod(actorId, read(op, PeriodReviewRequest.class)).version();
            }
            case "SPACE" -> op.operationType() == SyncOperationType.CREATE
                    ? spaces.createHousehold(actorId, read(op, CreateSpaceRequest.class)).version()
                    : spaces.update(op.entityId(), actorId, requireBase(op, UpdateSpaceRequest.class)).version();
            case "INVITATION" -> op.operationType() == SyncOperationType.CREATE
                    ? invitations.create(op.spaceId(), actorId, read(op, InvitationRequest.class)).version()
                    : invitations.resend(op.spaceId(), op.entityId(), actorId).version();
            case "MEMBERSHIP" -> memberService.changeRole(op.spaceId(), op.entityId(), actorId,
                    requireBase(op, ChangeRoleRequest.class)).version();
            case "SPACE_OWNERSHIP" -> {
                TransferOwnershipSyncRequest request = requireBase(op, TransferOwnershipSyncRequest.class);
                memberService.transfer(op.spaceId(), request.newOwnerMembershipId(), actorId);
                yield spaces.get(op.spaceId(), actorId).version();
            }
            case "USER_SETTINGS" -> userSettings.update(actorId, requireBase(op, SettingsRequest.class)).version();
            case "BUDGET_STATUS" -> {
                BudgetStatusSyncRequest request = requireBase(op, BudgetStatusSyncRequest.class);
                if (budgets.get(op.entityId(), actorId).version() != request.version()) {
                    throw ApiException.conflict("The budget was changed on another device");
                }
                yield switch (request.status()) {
                    case CLOSED -> budgets.close(op.entityId(), actorId, new VersionRequest(request.version())).version();
                    case ACTIVE -> budgets.reopen(op.entityId(), actorId, new VersionRequest(request.version())).version();
                    case DRAFT -> throw ApiException.badRequest("A budget cannot be returned to draft status");
                };
            }
            case "BUDGET_COPY" -> {
                BudgetCopySyncRequest request = read(op, BudgetCopySyncRequest.class);
                yield budgets.copy(request.sourceBudgetId(), actorId, new BudgetCopyRequest(request.id(), request.year(),
                        request.month(), request.name(), request.recurringOnly(), request.copyPlannedAmounts(),
                        request.copyBudgetItemStructure(), request.copyRecurringIncomeDefinitions(),
                        request.adjustScheduledIncomeDates(), request.copyFundingPlans())).version();
            }
            case "REMINDER_PAUSE" -> {
                ReminderPauseSyncRequest request = requireBase(op, ReminderPauseSyncRequest.class);
                yield reminders.pause(actorId, op.entityId(), request.until(), request.version()).version();
            }
            case "REMINDER_REVIEW" -> {
                ReminderReviewSyncRequest request = requireBase(op, ReminderReviewSyncRequest.class);
                yield reminders.review(actorId, op.entityId(), request.action(), request.version()).version();
            }
            case "REMINDER_RESUME" -> {
                ReminderResumeSyncRequest request = requireBase(op, ReminderResumeSyncRequest.class);
                yield reminders.resume(actorId, op.entityId(), request.version()).version();
            }
            default -> throw ApiException.badRequest("Unsupported synchronized entity type");
        };
    }

    private long delete(UUID actorId, ClientOperation op, String type) {
        verifyVersion(op.spaceId(), type, op.entityId(), op.baseVersion());
        switch (type) {
            case "BUDGET_MONTH" -> budgets.delete(op.entityId(), actorId, new VersionRequest(op.baseVersion()));
            case "INCOME_ENTRY" -> budgets.deleteIncome(op.entityId(), actorId, new VersionRequest(op.baseVersion()));
            case "INCOME_DEDUCTION" -> budgets.deleteIncomeDeduction(op.entityId(), actorId,
                    new IncomeStateRequest(op.baseVersion()));
            case "INCOME_RECEIPT" -> budgets.deleteReceipt(op.entityId(), actorId, new IncomeStateRequest(op.baseVersion()));
            case "INCOME_RECEIPT_DEDUCTION" -> budgets.deleteReceiptDeduction(op.entityId(), actorId,
                    new IncomeStateRequest(op.baseVersion()));
            case "FUNDING_ALLOCATION" -> budgets.reverseFundingAllocation(op.entityId(), actorId, new IncomeStateRequest(op.baseVersion()));
            case "BUDGET_ITEM" -> budgets.deleteItem(op.entityId(), actorId, new VersionRequest(op.baseVersion()));
            case "SPENDING_ENTRY" -> spending.delete(op.entityId(), actorId, new VersionRequest(op.baseVersion()));
            case "CATEGORY" -> references.archiveCategory(op.entityId(), actorId, new VersionRequest(op.baseVersion()));
            case "INCOME_TYPE" -> references.archiveIncomeType(op.entityId(), actorId, new VersionRequest(op.baseVersion()));
            case "REMINDER_PREFERENCE" -> reminders.delete(actorId, op.entityId());
            case "BUDGET_REMINDER_SCHEDULE" -> reminders.deleteBudgetSchedule(actorId, op.entityId(), op.baseVersion());
            case "SPACE" -> spaces.delete(op.entityId(), actorId);
            case "INVITATION" -> invitations.revoke(op.spaceId(), op.entityId(), actorId);
            case "MEMBERSHIP" -> {
                if (op.entityId().equals(access.require(op.spaceId(), actorId, Capability.VIEW).getId())) {
                    memberService.leave(op.spaceId(), actorId);
                } else memberService.remove(op.spaceId(), op.entityId(), actorId);
            }
            default -> throw ApiException.badRequest("Unsupported synchronized entity type");
        }
        Long resultVersion = latestVersion(op.spaceId(), type, op.entityId());
        return resultVersion == null ? op.baseVersion() + 1 : resultVersion;
    }

    @Transactional
    public PullResponse pull(UUID actorId, UUID deviceId, long cursor) {
        devices.findByIdAndUserIdAndRevokedAtIsNull(deviceId, actorId).orElseThrow(() -> ApiException.notFound("Device"));
        List<solutions.shapeit.wethrive.space.entity.SpaceMembership> authorized = memberships.findAllByUserIdAndStatusAndDeletedAtIsNull(actorId, MembershipStatus.ACTIVE);
        List<UUID> spaceIds = authorized.stream().map(m -> m.getSpaceId()).sorted().toList();
        List<ServerChangeLog> rows = spaceIds.isEmpty() ? List.of() : changes.findTop500BySpaceIdInAndSequenceIdGreaterThanOrderBySequenceIdAsc(spaceIds, Math.max(0, cursor));
        List<Change> result = rows.stream().map(this::map).toList();
        long next = rows.isEmpty() ? Math.max(0, cursor) : rows.get(rows.size() - 1).getSequenceId();
        return new PullResponse(cursor, next, rows.size() == 500, spaceIds, authorizations.authorizationVersion(actorId),
                authorizations.authorizationFingerprint(actorId), result, Instant.now(clock));
    }

    @Transactional
    public FullResyncResponse fullResync(UUID actorId, UUID deviceId) {
        devices.findByIdAndUserIdAndRevokedAtIsNull(deviceId, actorId).orElseThrow(() -> ApiException.notFound("Device"));
        long cursor = currentCursor();
        List<solutions.shapeit.wethrive.space.entity.SpaceMembership> authorized = memberships.findAllByUserIdAndStatusAndDeletedAtIsNull(actorId, MembershipStatus.ACTIVE);
        List<UUID> ids = authorized.stream().map(m -> m.getSpaceId()).sorted().toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profile", auth.current(actorId));
        data.put("settings", userSettings.get(actorId));
        data.put("spaces", spaces.list(actorId));
        List<Object> memberData = new ArrayList<>(), invitationData = new ArrayList<>(), categoryData = new ArrayList<>(), typeData = new ArrayList<>(), budgetData = new ArrayList<>(), incomeData = new ArrayList<>(), itemData = new ArrayList<>(), receiptData = new ArrayList<>(), allocationData = new ArrayList<>();
        List<IncomeDeductionResponse> deductionHistoryData = new ArrayList<>();
        List<IncomeReceiptResponse> receiptHistoryData = new ArrayList<>();
        List<IncomeReceiptDeductionResponse> receiptDeductionHistoryData = new ArrayList<>();
        for (UUID spaceId : ids) {
            memberData.addAll(memberService.list(spaceId, actorId)); categoryData.addAll(references.categories(spaceId, actorId)); typeData.addAll(references.incomeTypes(spaceId, actorId));
            if (access.can(spaceId, actorId, Capability.MANAGE_MEMBERS)) invitationData.addAll(invitations.list(spaceId, actorId));
            allocationData.addAll(budgets.fundingAllocationHistory(spaceId, actorId));
            deductionHistoryData.addAll(budgets.incomeDeductionHistoryForSpace(spaceId, actorId));
            receiptHistoryData.addAll(budgets.receiptHistoryForSpace(spaceId, actorId));
            receiptDeductionHistoryData.addAll(budgets.receiptDeductionHistoryForSpace(spaceId, actorId));
            var spaceBudgets = budgets.list(spaceId, actorId); budgetData.addAll(spaceBudgets);
            for (var budget : spaceBudgets) {
                var budgetIncome = budgets.income(budget.id(), actorId); incomeData.addAll(budgetIncome);
                for (var entry : budgetIncome) receiptData.addAll(budgets.receipts(entry.id(), actorId));
                var budgetItems = budgets.items(budget.id(), actorId); itemData.addAll(budgetItems);
            }
        }
        data.put("memberships", memberData); data.put("invitations", invitationData);
        data.put("categories", categoryData); data.put("incomeTypes", typeData);
        data.put("budgets", budgetData); data.put("income", incomeData); data.put("incomeReceipts", receiptData);
        data.put("incomeDeductions", deductionHistoryData.stream()
                .filter(value -> value.deletedAt() == null).toList());
        data.put("incomeDeductionHistory", deductionHistoryData);
        data.put("incomeReceiptHistory", receiptHistoryData);
        data.put("incomeReceiptDeductions", receiptDeductionHistoryData.stream()
                .filter(value -> value.deletedAt() == null).toList());
        data.put("incomeReceiptDeductionHistory", receiptDeductionHistoryData);
        data.put("budgetItems", itemData); data.put("fundingAllocations", allocationData.stream().distinct().toList());
        data.put("fundingAllocationHistory", ids.isEmpty() ? List.of()
                : changes.findAllBySpaceIdInAndEntityTypeOrderBySequenceIdAsc(ids, "FUNDING_ALLOCATION")
                    .stream().map(this::map).toList());
        List<Object> spendingData = new ArrayList<>();
        int page = 0;
        org.springframework.data.domain.Page<?> values;
        do {
            values = spending.list(actorId, null, null, null, page++, 200);
            spendingData.addAll(values.getContent());
        } while (values.hasNext());
        data.put("spending", spendingData); data.put("reminderPreferences", reminders.list(actorId));
        data.put("budgetReminderSchedules", reminders.listBudgetSchedules(actorId));
        data.put("reminderPeriodReviews", reminders.listPeriodReviews(actorId));
        return new FullResyncResponse(cursor, authorizations.authorizationVersion(actorId),
                authorizations.authorizationFingerprint(actorId), ids, data, Instant.now(clock));
    }

    private SyncOperation store(ClientOperation operation, UUID actorId) {
        SyncOperation stored = new SyncOperation(); stored.setId(operation.operationId()); stored.setUserId(actorId);
        stored.setDeviceId(operation.deviceId()); stored.setSpaceId(operation.spaceId()); stored.setModuleKey(operation.moduleKey());
        stored.setEntityType(normalizeType(operation.entityType())); stored.setEntityId(operation.entityId()); stored.setOperationType(operation.operationType());
        stored.setPayload(operation.payload().toString()); stored.setBaseVersion(operation.baseVersion()); stored.setClientTimestamp(operation.clientTimestamp());
        stored.setStatus(SyncStatus.REJECTED); return operations.save(stored);
    }
    private <T> T read(ClientOperation op, Class<T> type) {
        try {
            T value = validatePayload(mapper.treeToValue(op.payload(), type));
            JsonNode idNode = op.payload().get("id");
            if (idNode == null || !UUID.fromString(idNode.asString()).equals(op.entityId())) {
                throw ApiException.badRequest("entityId does not match payload id");
            }
            return value;
        } catch (ApiException ex) { throw ex; }
        catch (Exception ex) { throw ApiException.badRequest("Payload does not match the synchronized entity type"); }
    }
    private <T> T requireBase(ClientOperation op, Class<T> type) {
        try {
            JsonNode version = op.payload().get("version");
            if (version == null || op.baseVersion() == null || version.asLong() != op.baseVersion()) {
                throw ApiException.conflict("The payload version does not match baseVersion");
            }
            return validatePayload(mapper.treeToValue(op.payload(), type));
        } catch (ApiException ex) { throw ex; }
        catch (Exception ex) { throw ApiException.badRequest("Payload does not match the synchronized entity type"); }
    }
    <T> T validatePayload(T value) {
        var violations = validator.validate(value);
        if (!violations.isEmpty()) {
            String fields = violations.stream().map(violation -> violation.getPropertyPath().toString())
                    .distinct().sorted().limit(5).collect(java.util.stream.Collectors.joining(", "));
            throw ApiException.badRequest("Synchronized payload has invalid fields: " + fields);
        }
        return value;
    }
    private UUID requireBudgetId(JsonNode node) { JsonNode value = node.get("budgetId"); if (value == null) value = node.get("budgetMonthId"); if (value == null) throw ApiException.badRequest("budgetId is required in the synchronized payload"); return UUID.fromString(value.asString()); }
    private UUID requireIncomeId(JsonNode node) { JsonNode value = node.get("incomeEntryId"); if (value == null) value = node.get("incomeId"); if (value == null) throw ApiException.badRequest("incomeEntryId is required in the synchronized payload"); return UUID.fromString(value.asString()); }
    private UUID requireReceiptId(JsonNode node) { JsonNode value = node.get("incomeReceiptId"); if (value == null) value = node.get("receiptId"); if (value == null) throw ApiException.badRequest("incomeReceiptId is required in the synchronized payload"); return UUID.fromString(value.asString()); }
    private UUID requireBudgetItemId(JsonNode node) { JsonNode value = node.get("budgetItemId"); if (value == null) throw ApiException.badRequest("budgetItemId is required in the synchronized payload"); return UUID.fromString(value.asString()); }
    private String normalizeType(String value) { return value.trim().toUpperCase(Locale.ROOT).replace('-', '_'); }
    private Optional<ServerChangeLog> latestChange(UUID spaceId, String type, UUID id) {
        return changes.findTopBySpaceIdAndEntityTypeAndEntityIdOrderBySequenceIdDesc(
                spaceId, canonicalChangeType(type), id);
    }
    private Optional<ServerChangeLog> latestAuthorizedChange(UUID actorId, ClientOperation operation) {
        // SPACE CREATE has no membership to validate before dispatch. If its client-generated id
        // collides with another tenant's space id, never use that foreign row as conflict context.
        if (!access.can(operation.spaceId(), actorId, Capability.VIEW)) return Optional.empty();
        return latestChange(operation.spaceId(), operation.entityType(), operation.entityId());
    }
    private Long latestVersion(UUID spaceId, String type, UUID id) {
        return latestChange(spaceId, type, id).map(ServerChangeLog::getEntityVersion).orElse(null);
    }
    private String canonicalChangeType(String type) {
        return switch (normalizeType(type)) {
            case "BUDGET_STATUS", "BUDGET_COPY" -> "BUDGET_MONTH";
            case "INCOME_CANCEL", "INCOME_RESTORE" -> "INCOME_ENTRY";
            case "SPACE_OWNERSHIP" -> "SPACE";
            case "REMINDER_PAUSE", "REMINDER_RESUME" -> "REMINDER_PREFERENCE";
            case "REMINDER_REVIEW" -> "REMINDER_OCCURRENCE";
            default -> normalizeType(type);
        };
    }
    private void verifyVersion(UUID spaceId, String type, UUID id, Long base) { Long current = latestVersion(spaceId, type, id); if (current != null && !current.equals(base)) throw ApiException.conflict("The server record changed after this offline edit"); }
    private Change map(ServerChangeLog c) { return new Change(c.getSequenceId(), c.getSpaceId(), c.getEntityType(), c.getEntityId(), c.getOperationType(), c.getEntityVersion(), c.getChangedAt(), parse(c.getPayload()), c.isTombstone()); }
    private JsonNode parse(String value) { if (value == null) return null; try { return mapper.readTree(value); } catch (Exception ex) { return null; } }
    private long currentCursor() {
        if (changes.count() == 0) return 0;
        return changes.findAll(org.springframework.data.domain.PageRequest.of(0, 1,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "sequenceId")))
                .getContent().get(0).getSequenceId();
    }
}
