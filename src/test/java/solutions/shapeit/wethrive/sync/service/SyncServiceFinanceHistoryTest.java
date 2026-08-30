package solutions.shapeit.wethrive.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.transaction.PlatformTransactionManager;
import solutions.shapeit.wethrive.common.domain.DomainEnums.FundingSourceType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.MembershipStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SyncOperationType;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.device.entity.Device;
import solutions.shapeit.wethrive.device.repository.DeviceRepository;
import solutions.shapeit.wethrive.device.service.OfflineGrantService;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.FundingAllocationResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptRequest;
import solutions.shapeit.wethrive.finance.service.BudgetService;
import solutions.shapeit.wethrive.finance.service.ReferenceFinanceService;
import solutions.shapeit.wethrive.finance.service.SpendingService;
import solutions.shapeit.wethrive.identity.service.AuthService;
import solutions.shapeit.wethrive.identity.service.SettingsService;
import solutions.shapeit.wethrive.reminder.service.ReminderService;
import solutions.shapeit.wethrive.space.entity.SpaceMembership;
import solutions.shapeit.wethrive.space.repository.SpaceMembershipRepository;
import solutions.shapeit.wethrive.space.service.InvitationService;
import solutions.shapeit.wethrive.space.service.MembershipService;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;
import solutions.shapeit.wethrive.space.service.SpaceService;
import solutions.shapeit.wethrive.sync.dto.SyncDtos.Change;
import solutions.shapeit.wethrive.sync.entity.ServerChangeLog;
import solutions.shapeit.wethrive.sync.repository.ServerChangeLogRepository;
import solutions.shapeit.wethrive.sync.repository.SyncOperationRepository;
import tools.jackson.databind.ObjectMapper;

class SyncServiceFinanceHistoryTest {
    private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID DEVICE = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID SPACE = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID ALLOCATION = UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final Instant NOW = Instant.parse("2026-07-15T10:00:00Z");

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
    private SyncService service;

    @BeforeEach
    void setUp() {
        service = new SyncService(operations, changes, devices, memberships, access, spaces, memberService,
                references, budgets, spending, reminders, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                mock(PlatformTransactionManager.class), authorizations, auth, settings, invitations,
                Validation.buildDefaultValidatorFactory().getValidator());
    }

    @Test
    void rejectsInvalidNestedFinancePayloadAfterJsonDeserialization() {
        var invalid = new IncomeReceiptRequest(UUID.randomUUID(), UUID.randomUUID(), null, NOW,
                "Africa/Johannesburg", null);
        assertThatThrownBy(() -> service.validatePayload(invalid))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("amount");
        var excessiveScale = new IncomeReceiptRequest(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("1.001"), NOW, "Africa/Johannesburg", null);
        assertThatThrownBy(() -> service.validatePayload(excessiveScale))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void legacyReceiptPayloadRemainsDistinctFromAnExplicitEmptyDeductionReview() {
        var legacy = new IncomeReceiptRequest(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("4000.00"), NOW, "Africa/Johannesburg", null);
        var reviewed = new IncomeReceiptRequest(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("4000.00"), NOW, "Africa/Johannesburg", null, List.of());

        assertThat(legacy.deductions()).isNull();
        assertThat(reviewed.deductions()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullResyncIncludesCurrentReversalTombstoneAndOrderedVersionHistory() {
        Device device = new Device(); device.setId(DEVICE); device.setUserId(ACTOR);
        when(devices.findByIdAndUserIdAndRevokedAtIsNull(DEVICE, ACTOR)).thenReturn(java.util.Optional.of(device));
        SpaceMembership membership = new SpaceMembership(); membership.setSpaceId(SPACE); membership.setUserId(ACTOR);
        when(memberships.findAllByUserIdAndStatusAndDeletedAtIsNull(ACTOR, MembershipStatus.ACTIVE))
                .thenReturn(List.of(membership));
        when(spaces.list(ACTOR)).thenReturn(List.of());
        when(memberService.list(SPACE, ACTOR)).thenReturn(List.of());
        when(references.categories(SPACE, ACTOR)).thenReturn(List.of());
        when(references.incomeTypes(SPACE, ACTOR)).thenReturn(List.of());
        when(budgets.list(SPACE, ACTOR)).thenReturn(List.of());
        when(spending.list(ACTOR, null, null, null, 0, 200)).thenReturn(new PageImpl<>(List.of()));
        when(reminders.list(ACTOR)).thenReturn(List.of());
        when(authorizations.authorizationFingerprint(ACTOR)).thenReturn("fingerprint");

        FundingAllocationResponse reversed = new FundingAllocationResponse(ALLOCATION, UUID.randomUUID(),
                UUID.randomUUID(), SPACE, FundingSourceType.INCOME_ENTRY, new BigDecimal("2000.00"),
                new BigDecimal("1500.00"), NOW, "Africa/Johannesburg", "Reversed", ACTOR, ACTOR,
                NOW.minusSeconds(60), NOW, NOW, 2);
        when(budgets.fundingAllocationHistory(SPACE, ACTOR)).thenReturn(List.of(reversed));
        List<ServerChangeLog> events = List.of(event(1, SyncOperationType.CREATE, 0, false),
                event(2, SyncOperationType.UPDATE, 1, false), event(3, SyncOperationType.DELETE, 2, true));
        when(changes.findAllBySpaceIdInAndEntityTypeOrderBySequenceIdAsc(List.of(SPACE), "FUNDING_ALLOCATION"))
                .thenReturn(events);

        var response = service.fullResync(ACTOR, DEVICE);
        var current = (List<FundingAllocationResponse>) response.data().get("fundingAllocations");
        var history = (List<Change>) response.data().get("fundingAllocationHistory");

        assertThat(current).singleElement().satisfies(value -> assertThat(value.deletedAt()).isEqualTo(NOW));
        assertThat(history).extracting(Change::sequenceId).containsExactly(1L, 2L, 3L);
        assertThat(history.get(2).tombstone()).isTrue();
        assertThat(history.get(2).payload()).isNotNull();
    }

    private ServerChangeLog event(long sequence, SyncOperationType operation, long version, boolean tombstone) {
        ServerChangeLog value = new ServerChangeLog(); value.setSequenceId(sequence); value.setSpaceId(SPACE);
        value.setEntityType("FUNDING_ALLOCATION"); value.setEntityId(ALLOCATION); value.setOperationType(operation);
        value.setEntityVersion(version); value.setChangedByUserId(ACTOR); value.setChangedAt(NOW.plusSeconds(sequence));
        value.setPayload("{\"id\":\"" + ALLOCATION + "\",\"confirmedAllocatedAmount\":\"1500.00\"}");
        value.setTombstone(tombstone); return value;
    }
}
