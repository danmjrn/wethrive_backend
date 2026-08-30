package solutions.shapeit.wethrive.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.validation.Validation;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SyncOperationType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SyncStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.DeductionType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.TransactionType;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.device.entity.Device;
import solutions.shapeit.wethrive.device.repository.DeviceRepository;
import solutions.shapeit.wethrive.device.service.OfflineGrantService;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeDeductionRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeDeductionResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.SpendingRequest;
import solutions.shapeit.wethrive.finance.service.BudgetService;
import solutions.shapeit.wethrive.finance.service.ReferenceFinanceService;
import solutions.shapeit.wethrive.finance.service.SpendingService;
import solutions.shapeit.wethrive.identity.service.AuthService;
import solutions.shapeit.wethrive.identity.service.SettingsService;
import solutions.shapeit.wethrive.reminder.service.ReminderService;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.CreateSpaceRequest;
import solutions.shapeit.wethrive.space.entity.SpaceMembership;
import solutions.shapeit.wethrive.space.repository.SpaceMembershipRepository;
import solutions.shapeit.wethrive.space.service.InvitationService;
import solutions.shapeit.wethrive.space.service.MembershipService;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;
import solutions.shapeit.wethrive.space.service.SpaceAccessService.Capability;
import solutions.shapeit.wethrive.space.service.SpaceService;
import solutions.shapeit.wethrive.sync.dto.SyncDtos.ClientOperation;
import solutions.shapeit.wethrive.sync.entity.ServerChangeLog;
import solutions.shapeit.wethrive.sync.entity.SyncOperation;
import solutions.shapeit.wethrive.sync.repository.ServerChangeLogRepository;
import solutions.shapeit.wethrive.sync.repository.SyncOperationRepository;
import tools.jackson.databind.ObjectMapper;

class SyncServiceConflictIsolationTest {
    private static final UUID ACTOR = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID DEVICE = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID SPACE = UUID.fromString("20000000-0000-0000-0000-000000000003");
    private static final UUID FOREIGN_SPACE = UUID.fromString("20000000-0000-0000-0000-000000000004");
    private static final UUID ENTITY = UUID.fromString("20000000-0000-0000-0000-000000000005");
    private static final Instant NOW = Instant.parse("2026-07-21T10:00:00Z");

    private final SyncOperationRepository operations = mock(SyncOperationRepository.class);
    private final ServerChangeLogRepository changes = mock(ServerChangeLogRepository.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final SpaceMembershipRepository memberships = mock(SpaceMembershipRepository.class);
    private final SpaceAccessService access = mock(SpaceAccessService.class);
    private final SpaceService spaces = mock(SpaceService.class);
    private final MembershipService memberService = mock(MembershipService.class);
    private final ReferenceFinanceService references = mock(ReferenceFinanceService.class);
    private final BudgetService budgets = mock(BudgetService.class);
    private final SpendingService spending = mock(SpendingService.class);
    private final ReminderService reminders = mock(ReminderService.class);
    private final OfflineGrantService authorizations = mock(OfflineGrantService.class);
    private final AuthService auth = mock(AuthService.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final InvitationService invitations = mock(InvitationService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private SyncService service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        service = new SyncService(operations, changes, devices, memberships, access, spaces, memberService,
                references, budgets, spending, reminders, mapper, Clock.fixed(NOW, ZoneOffset.UTC),
                transactionManager, authorizations, auth, settings, invitations,
                Validation.buildDefaultValidatorFactory().getValidator());
        Device device = new Device();
        device.setId(DEVICE);
        device.setUserId(ACTOR);
        when(devices.findByIdAndUserIdAndRevokedAtIsNull(DEVICE, ACTOR)).thenReturn(Optional.of(device));
    }

    @Test
    void conflictLookupCannotCrossTheAuthorizedOperationSpace() {
        authorize(SPACE, Capability.EDIT_FINANCE);
        when(access.can(SPACE, ACTOR, Capability.VIEW)).thenReturn(true);
        when(budgets.update(eq(ENTITY), eq(ACTOR), any(BudgetUpdateRequest.class)))
                .thenThrow(ApiException.conflict("The budget changed elsewhere"));
        ServerChangeLog foreign = change(FOREIGN_SPACE, "BUDGET_MONTH", ENTITY, 9,
                "{\"name\":\"Foreign household budget\",\"notes\":\"private\"}");
        when(changes.findTopBySpaceIdAndEntityTypeAndEntityIdOrderBySequenceIdDesc(
                SPACE, "BUDGET_MONTH", ENTITY)).thenReturn(Optional.empty());
        when(changes.findTopBySpaceIdAndEntityTypeAndEntityIdOrderBySequenceIdDesc(
                FOREIGN_SPACE, "BUDGET_MONTH", ENTITY)).thenReturn(Optional.of(foreign));

        var result = service.push(ACTOR, List.of(budgetUpdate())).results().getFirst();

        assertThat(result.status()).isEqualTo(SyncStatus.CONFLICT);
        assertThat(result.serverVersion()).isNull();
        assertThat(result.serverPayload()).isNull();
        verify(changes).findTopBySpaceIdAndEntityTypeAndEntityIdOrderBySequenceIdDesc(
                SPACE, "BUDGET_MONTH", ENTITY);
        verify(changes, never()).findTopBySpaceIdAndEntityTypeAndEntityIdOrderBySequenceIdDesc(
                FOREIGN_SPACE, "BUDGET_MONTH", ENTITY);
    }

    @Test
    void createSpaceIdCollisionDoesNotExposeAnUnauthorizedExistingSpace() {
        when(spaces.createHousehold(eq(ACTOR), any(CreateSpaceRequest.class)))
                .thenThrow(ApiException.conflict("A space with this identifier already exists"));
        ServerChangeLog foreign = change(FOREIGN_SPACE, "SPACE", FOREIGN_SPACE, 4,
                "{\"name\":\"Private household\"}");
        when(changes.findTopBySpaceIdAndEntityTypeAndEntityIdOrderBySequenceIdDesc(
                FOREIGN_SPACE, "SPACE", FOREIGN_SPACE)).thenReturn(Optional.of(foreign));

        var payload = mapper.createObjectNode();
        payload.put("id", FOREIGN_SPACE.toString());
        payload.put("name", "Collision attempt");
        payload.put("currencyCode", "ZAR");
        payload.put("locale", "en-ZA");
        payload.put("timeZone", "Africa/Johannesburg");
        ClientOperation operation = new ClientOperation(UUID.randomUUID(), ACTOR, DEVICE, FOREIGN_SPACE,
                "space", "SPACE", FOREIGN_SPACE, SyncOperationType.CREATE, payload, null, NOW);

        var result = service.push(ACTOR, List.of(operation)).results().getFirst();

        assertThat(result.status()).isEqualTo(SyncStatus.CONFLICT);
        assertThat(result.serverVersion()).isNull();
        assertThat(result.serverPayload()).isNull();
        verify(access).can(FOREIGN_SPACE, ACTOR, Capability.VIEW);
        verify(changes, never()).findTopBySpaceIdAndEntityTypeAndEntityIdOrderBySequenceIdDesc(
                FOREIGN_SPACE, "SPACE", FOREIGN_SPACE);
    }

    @Test
    void sameSpaceConflictReturnsContextAfterAuthorization() {
        authorize(SPACE, Capability.EDIT_FINANCE);
        when(access.can(SPACE, ACTOR, Capability.VIEW)).thenReturn(true);
        when(budgets.update(eq(ENTITY), eq(ACTOR), any(BudgetUpdateRequest.class)))
                .thenThrow(ApiException.conflict("The budget changed elsewhere"));
        ServerChangeLog local = change(SPACE, "BUDGET_MONTH", ENTITY, 7,
                "{\"name\":\"Authorized budget\"}");
        when(changes.findTopBySpaceIdAndEntityTypeAndEntityIdOrderBySequenceIdDesc(
                SPACE, "BUDGET_MONTH", ENTITY)).thenReturn(Optional.of(local));

        var result = service.push(ACTOR, List.of(budgetUpdate())).results().getFirst();

        assertThat(result.status()).isEqualTo(SyncStatus.CONFLICT);
        assertThat(result.serverVersion()).isEqualTo(7);
        assertThat(result.serverPayload().get("name").asString()).isEqualTo("Authorized budget");
    }

    @Test
    void fullyReceivedIncomeCreateIsTerminalInsteadOfAnEndlesslyRetryableConflict() {
        authorize(SPACE, Capability.EDIT_FINANCE);
        UUID incomeId = UUID.fromString("20000000-0000-0000-0000-000000000006");
        var request = new IncomeReceiptRequest(ENTITY, incomeId, new java.math.BigDecimal("1.00"), NOW,
                "Africa/Johannesburg", "Stale device", List.of());
        when(budgets.createReceipt(incomeId, ACTOR, request)).thenThrow(ApiException.conflict(
                "income_already_received", "This income is already fully received"));
        var operation = new ClientOperation(UUID.randomUUID(), ACTOR, DEVICE, SPACE, "finance",
                "INCOME_RECEIPT", ENTITY, SyncOperationType.CREATE, mapper.valueToTree(request), null, NOW);

        var result = service.push(ACTOR, List.of(operation)).results().getFirst();

        assertThat(result.status()).isEqualTo(SyncStatus.REJECTED);
        assertThat(result.errorCode()).isEqualTo("income_already_received");
        assertThat(result.serverVersion()).isNull();
        assertThat(result.serverPayload()).isNull();
        verify(changes, never()).findTopBySpaceIdAndEntityTypeAndEntityIdOrderBySequenceIdDesc(
                SPACE, "INCOME_RECEIPT", ENTITY);
    }

    @Test
    void overRefundCreateIsTerminalInsteadOfAnEndlesslyRetryableConflict() {
        authorize(SPACE, Capability.EDIT_FINANCE);
        UUID itemId = UUID.fromString("20000000-0000-0000-0000-000000000007");
        UUID originalId = UUID.fromString("20000000-0000-0000-0000-000000000008");
        var request = new SpendingRequest(ENTITY, itemId, TransactionType.REFUND, "Stale refund",
                new java.math.BigDecimal("25.00"), java.time.LocalDate.of(2026, 7, 21),
                java.time.LocalTime.NOON, "Africa/Johannesburg", "Card", "Merchant", "Stale device",
                ACTOR, originalId);
        when(spending.create(ACTOR, request)).thenThrow(ApiException.conflict(
                "refund_capacity_exceeded", "Total active refunds cannot exceed the original expense amount"));
        var operation = new ClientOperation(UUID.randomUUID(), ACTOR, DEVICE, SPACE, "finance",
                "SPENDING_ENTRY", ENTITY, SyncOperationType.CREATE, mapper.valueToTree(request), null, NOW);

        var result = service.push(ACTOR, List.of(operation)).results().getFirst();

        assertThat(result.status()).isEqualTo(SyncStatus.REJECTED);
        assertThat(result.errorCode()).isEqualTo("refund_capacity_exceeded");
        verify(changes, never()).findTopBySpaceIdAndEntityTypeAndEntityIdOrderBySequenceIdDesc(
                SPACE, "SPENDING_ENTRY", ENTITY);
    }

    @Test
    void repeatedIncomeDeductionOperationIdIsAppliedExactlyOnce() {
        authorize(SPACE, Capability.EDIT_FINANCE);
        when(access.can(SPACE, ACTOR, Capability.VIEW)).thenReturn(true);
        UUID deductionId = UUID.fromString("20000000-0000-0000-0000-000000000006");
        UUID incomeId = UUID.fromString("20000000-0000-0000-0000-000000000007");
        var request = new IncomeDeductionRequest(deductionId, incomeId, "Medical aid",
                DeductionType.FIXED, null, new java.math.BigDecimal("1000.00"), null, 1);
        var response = new IncomeDeductionResponse(deductionId, incomeId, SPACE, "Medical aid",
                DeductionType.FIXED, null, new java.math.BigDecimal("1000.00"),
                new java.math.BigDecimal("1000.00"), null, 1, false, ACTOR, ACTOR,
                NOW, NOW, null, 0L);
        when(budgets.createIncomeDeduction(incomeId, ACTOR, request)).thenReturn(response);
        UUID operationId = UUID.fromString("20000000-0000-0000-0000-000000000008");
        var operation = new ClientOperation(operationId, ACTOR, DEVICE, SPACE, "finance",
                "INCOME_DEDUCTION", deductionId, SyncOperationType.CREATE,
                mapper.valueToTree(request), null, NOW);
        AtomicReference<SyncOperation> stored = new AtomicReference<>();
        when(operations.findById(operationId)).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(operations.save(any(SyncOperation.class))).thenAnswer(invocation -> {
            SyncOperation value = invocation.getArgument(0); stored.set(value); return value;
        });
        when(operations.saveAndFlush(any(SyncOperation.class))).thenAnswer(invocation -> {
            SyncOperation value = invocation.getArgument(0); stored.set(value); return value;
        });

        var first = service.push(ACTOR, List.of(operation)).results().getFirst();
        var second = service.push(ACTOR, List.of(operation)).results().getFirst();

        assertThat(first.status()).isEqualTo(SyncStatus.APPLIED);
        assertThat(second.status()).isEqualTo(SyncStatus.DUPLICATE);
        assertThat(second.serverVersion()).isZero();
        verify(budgets, times(1)).createIncomeDeduction(incomeId, ACTOR, request);
    }

    @Test
    void repeatedIncomeReceiptOperationIdCreatesOneLogicalReceipt() {
        authorize(SPACE, Capability.EDIT_FINANCE);
        when(access.can(SPACE, ACTOR, Capability.VIEW)).thenReturn(true);
        UUID incomeId = UUID.fromString("20000000-0000-0000-0000-000000000009");
        var request = new IncomeReceiptRequest(ENTITY, incomeId, new java.math.BigDecimal("5000.00"),
                NOW, "Africa/Johannesburg", "Salary", List.of());
        var response = new IncomeReceiptResponse(ENTITY, incomeId, SPACE,
                new java.math.BigDecimal("5000.00"), NOW, "Africa/Johannesburg", "Salary",
                ACTOR, ACTOR, ACTOR, NOW, NOW, null, 0L);
        when(budgets.createReceipt(incomeId, ACTOR, request)).thenReturn(response);
        UUID operationId = UUID.fromString("20000000-0000-0000-0000-00000000000a");
        var operation = new ClientOperation(operationId, ACTOR, DEVICE, SPACE, "finance",
                "INCOME_RECEIPT", ENTITY, SyncOperationType.CREATE,
                mapper.valueToTree(request), null, NOW);
        AtomicReference<SyncOperation> stored = new AtomicReference<>();
        when(operations.findById(operationId)).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(operations.save(any(SyncOperation.class))).thenAnswer(invocation -> {
            SyncOperation value = invocation.getArgument(0); stored.set(value); return value;
        });
        when(operations.saveAndFlush(any(SyncOperation.class))).thenAnswer(invocation -> {
            SyncOperation value = invocation.getArgument(0); stored.set(value); return value;
        });

        var first = service.push(ACTOR, List.of(operation)).results().getFirst();
        var replay = service.push(ACTOR, List.of(operation)).results().getFirst();

        assertThat(first.status()).isEqualTo(SyncStatus.APPLIED);
        assertThat(replay.status()).isEqualTo(SyncStatus.DUPLICATE);
        assertThat(first.entityId()).isEqualTo(ENTITY);
        assertThat(replay.entityId()).isEqualTo(ENTITY);
        assertThat(replay.serverVersion()).isZero();
        verify(budgets, times(1)).createReceipt(incomeId, ACTOR, request);
    }

    @Test
    void reminderPauseAndDeletesAreRejectedWithoutCurrentEditFinanceCapability() {
        doThrow(ApiException.forbidden()).when(access).require(SPACE, ACTOR, Capability.EDIT_FINANCE);
        var pausePayload = mapper.createObjectNode();
        pausePayload.put("until", NOW.plusSeconds(3600).toString());
        pausePayload.put("version", 1);
        ClientOperation pause = new ClientOperation(UUID.randomUUID(), ACTOR, DEVICE, SPACE, "reminder",
                "REMINDER_PAUSE", ENTITY, SyncOperationType.UPDATE, pausePayload, 1L, NOW);
        var deletePayload = mapper.createObjectNode();
        deletePayload.put("id", ENTITY.toString());
        ClientOperation deletePreference = new ClientOperation(UUID.randomUUID(), ACTOR, DEVICE, SPACE,
                "reminder", "REMINDER_PREFERENCE", ENTITY, SyncOperationType.DELETE,
                deletePayload, 1L, NOW);
        ClientOperation deleteSchedule = new ClientOperation(UUID.randomUUID(), ACTOR, DEVICE, SPACE,
                "reminder", "BUDGET_REMINDER_SCHEDULE", ENTITY, SyncOperationType.DELETE,
                deletePayload, 1L, NOW);

        assertThatThrownBy(() -> service.push(ACTOR, List.of(pause))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.push(ACTOR, List.of(deletePreference))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.push(ACTOR, List.of(deleteSchedule))).isInstanceOf(ApiException.class);

        verify(access, times(3)).require(SPACE, ACTOR, Capability.EDIT_FINANCE);
        verify(reminders, never()).pause(any(), any(), any(), anyLong());
        verify(reminders, never()).delete(any(), any());
        verify(reminders, never()).deleteBudgetSchedule(any(), any(), anyLong());
    }

    private ClientOperation budgetUpdate() {
        var payload = mapper.createObjectNode();
        payload.put("name", "Local budget");
        payload.put("status", "ACTIVE");
        payload.put("notes", "Local notes");
        payload.put("version", 4);
        return new ClientOperation(UUID.randomUUID(), ACTOR, DEVICE, SPACE, "finance", "BUDGET_MONTH",
                ENTITY, SyncOperationType.UPDATE, payload, 4L, NOW);
    }

    private void authorize(UUID spaceId, Capability capability) {
        SpaceMembership membership = new SpaceMembership();
        membership.setId(UUID.randomUUID());
        membership.setSpaceId(spaceId);
        membership.setUserId(ACTOR);
        when(access.require(spaceId, ACTOR, capability)).thenReturn(membership);
    }

    private ServerChangeLog change(UUID spaceId, String type, UUID entityId, long version, String payload) {
        ServerChangeLog value = new ServerChangeLog();
        value.setSequenceId(version);
        value.setSpaceId(spaceId);
        value.setEntityType(type);
        value.setEntityId(entityId);
        value.setOperationType(SyncOperationType.UPDATE);
        value.setEntityVersion(version);
        value.setChangedByUserId(UUID.randomUUID());
        value.setChangedAt(NOW);
        value.setPayload(payload);
        return value;
    }
}
