package solutions.shapeit.wethrive.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import solutions.shapeit.wethrive.common.domain.DomainChangeRecorder;
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetItemType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.MembershipStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.TransactionType;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.SpendingRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.SpendingUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.MoveSpendingRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.RestoreSpendingRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.VersionRequest;
import solutions.shapeit.wethrive.finance.entity.BudgetItem;
import solutions.shapeit.wethrive.finance.entity.SpendingEntry;
import solutions.shapeit.wethrive.finance.repository.SpendingEntryRepository;
import solutions.shapeit.wethrive.space.repository.SpaceMembershipRepository;
import solutions.shapeit.wethrive.space.entity.SpaceMembership;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;

/**
 * Verifies locked spending, linked-refund, and optimistic-delete invariants.
 *
 * @author Daniel Jr Nkulu
 */
class SpendingServiceInvariantTest {
    private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SPACE = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID BUDGET = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID ITEM = UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID ORIGINAL = UUID.fromString("10000000-0000-0000-0000-000000000005");
    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    private final SpendingEntryRepository spending = mock(SpendingEntryRepository.class);
    private final BudgetService budgets = mock(BudgetService.class);
    private final SpaceMembershipRepository memberships = mock(SpaceMembershipRepository.class);
    private final SpaceAccessService access = mock(SpaceAccessService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DomainChangeRecorder> changes = mock(ObjectProvider.class);
    private final FinanceCalculationService calculations = new FinanceCalculationService();
    private SpendingService service;
    private BudgetItem item;
    private SpendingEntry original;

    @BeforeEach
    void setUp() {
        service = new SpendingService(spending, budgets, memberships, access, calculations, changes,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(changes.orderedStream()).thenAnswer(invocation -> Stream.empty());
        when(spending.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        item = item(ITEM, BUDGET);
        when(budgets.requireItem(ITEM)).thenReturn(item);
        when(budgets.requireOpenItemForUpdate(any(), any(), any())).thenReturn(item);
        original = entry(ORIGINAL, TransactionType.EXPENSE, "100.00");
        when(spending.findByIdAndDeletedAtIsNull(ORIGINAL)).thenReturn(Optional.of(original));
        when(spending.findById(ORIGINAL)).thenReturn(Optional.of(original));
        when(spending.findForUpdateById(ORIGINAL)).thenReturn(Optional.of(original));
        when(spending.findAllByBudgetItemIdAndDeletedAtIsNullOrderBySpentAtDesc(ITEM))
                .thenReturn(List.of(original));
        when(memberships.findBySpaceIdAndUserIdAndStatusAndDeletedAtIsNull(
                SPACE, ACTOR, MembershipStatus.ACTIVE)).thenReturn(Optional.of(new SpaceMembership()));
    }

    @Test
    void cumulativeLinkedRefundCannotExceedOriginalExpense() {
        SpendingEntry prior = entry(UUID.randomUUID(), TransactionType.REFUND, "80.00");
        prior.setRefundForSpendingEntryId(ORIGINAL);
        when(spending.findAllByRefundForSpendingEntryIdAndDeletedAtIsNullOrderBySpentAtAscCreatedAtAsc(ORIGINAL))
                .thenReturn(List.of(prior));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.create(ACTOR, linkedRefund("30.00")));

        assertThat(failure.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        assertThat(failure.code()).isEqualTo("refund_capacity_exceeded");
        assertThat(failure).hasMessageContaining("cannot exceed");

        verify(spending).findForUpdateById(ORIGINAL);
        verify(spending, never()).saveAndFlush(any());
    }

    @Test
    void everyNewRefundRequiresAnOriginalExpenseLink() {
        SpendingRequest unlinked = new SpendingRequest(UUID.randomUUID(), ITEM, TransactionType.REFUND,
                "Unlinked refund", new BigDecimal("10.00"), LocalDate.of(2026, 8, 20), LocalTime.NOON,
                "Africa/Johannesburg", "Card", "Grocer", "Missing source", ACTOR);

        assertThatThrownBy(() -> service.create(ACTOR, unlinked))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("original expense");

        verify(spending, never()).saveAndFlush(any());
    }

    @Test
    void linkedRefundPersistsItsSourceWhenRemainingAmountIsAvailable() {
        when(spending.findAllByRefundForSpendingEntryIdAndDeletedAtIsNullOrderBySpentAtAscCreatedAtAsc(ORIGINAL))
                .thenReturn(List.of());

        var response = service.create(ACTOR, linkedRefund("30.00"));

        assertThat(response.refundForSpendingEntryId()).isEqualTo(ORIGINAL);
        assertThat(response.amount()).isEqualByComparingTo("30.00");
    }

    @Test
    void duplicateIdentifierCheckRunsAfterTheBudgetItemLifecycleLock() {
        UUID duplicateId = UUID.randomUUID();
        SpendingRequest duplicate = new SpendingRequest(duplicateId, ITEM, TransactionType.EXPENSE,
                "Groceries", new BigDecimal("25.00"), LocalDate.of(2026, 8, 20), LocalTime.NOON,
                "Africa/Johannesburg", "Card", "Grocer", "Weekly shop", ACTOR);
        when(spending.existsById(duplicateId)).thenReturn(true);

        ApiException failure = assertThrows(ApiException.class, () -> service.create(ACTOR, duplicate));

        assertThat(failure.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        assertThat(failure).hasMessageContaining("identifier already exists");

        InOrder lockedDuplicateCheck = inOrder(budgets, spending);
        lockedDuplicateCheck.verify(budgets)
                .requireOpenItemForUpdate(ITEM, ACTOR, SpaceAccessService.Capability.RECORD_SPENDING);
        lockedDuplicateCheck.verify(spending).existsById(duplicateId);
        verify(spending, never()).saveAndFlush(any());
    }

    @Test
    void expenseReductionCannotInvalidateAnActiveRefund() {
        SpendingEntry prior = entry(UUID.randomUUID(), TransactionType.REFUND, "80.00");
        prior.setRefundForSpendingEntryId(ORIGINAL);
        when(spending.findAllByRefundForSpendingEntryIdAndDeletedAtIsNullOrderBySpentAtAscCreatedAtAsc(ORIGINAL))
                .thenReturn(List.of(prior));

        assertThatThrownBy(() -> service.update(ORIGINAL, ACTOR, update(ITEM, "50.00", 0L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot be lower");
    }

    @Test
    void updateRejectsMovingSpendingAcrossBudgetMonths() {
        UUID otherItemId = UUID.randomUUID();
        BudgetItem other = item(otherItemId, UUID.randomUUID());
        when(budgets.requireItem(otherItemId)).thenReturn(other);

        assertThatThrownBy(() -> service.update(ORIGINAL, ACTOR, update(otherItemId, "100.00", 0L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("between budget months");

        verify(spending, never()).saveAndFlush(any());
    }

    @Test
    void deleteRequiresTheCurrentOptimisticVersion() {
        original.setVersion(3L);

        assertThatThrownBy(() -> service.delete(ORIGINAL, ACTOR, new VersionRequest(2L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("another device");

        verify(spending, never()).saveAndFlush(any());
    }

    @Test
    void legacyUnlinkedRefundCanDecreaseButCannotIncreaseMoveOrRestore() {
        UUID legacyId = UUID.randomUUID();
        SpendingEntry legacy = entry(legacyId, TransactionType.REFUND, "25.00");
        when(spending.findByIdAndDeletedAtIsNull(legacyId)).thenReturn(Optional.of(legacy));
        when(spending.findById(legacyId)).thenReturn(Optional.of(legacy));
        when(spending.findForUpdateById(legacyId)).thenReturn(Optional.of(legacy));
        when(spending.findAllByBudgetItemIdAndDeletedAtIsNullOrderBySpentAtDesc(ITEM))
                .thenReturn(List.of(original, legacy));

        var decreased = service.update(legacyId, ACTOR, refundUpdate(ITEM, "20.00", 0L));
        assertThat(decreased.amount()).isEqualByComparingTo("20.00");

        legacy.setAmount(new BigDecimal("25.00"));
        ApiException increase = assertThrows(ApiException.class,
                () -> service.update(legacyId, ACTOR, refundUpdate(ITEM, "26.00", 0L)));
        assertThat(increase.code()).isEqualTo("legacy_refund_reconciliation_required");

        UUID otherItemId = UUID.randomUUID();
        BudgetItem other = item(otherItemId, BUDGET);
        when(budgets.requireItem(otherItemId)).thenReturn(other);
        when(budgets.requireOpenItemForUpdate(otherItemId, ACTOR,
                SpaceAccessService.Capability.EDIT_FINANCE)).thenReturn(other);
        ApiException move = assertThrows(ApiException.class,
                () -> service.move(legacyId, ACTOR, new MoveSpendingRequest(otherItemId, 0L)));
        assertThat(move.code()).isEqualTo("legacy_refund_reconciliation_required");

        legacy.setDeletedAt(NOW);
        when(spending.findByIdAndDeletedAtIsNull(legacyId)).thenReturn(Optional.empty());
        when(spending.findAnyForUpdateById(legacyId)).thenReturn(Optional.of(legacy));
        ApiException restore = assertThrows(ApiException.class,
                () -> service.restore(legacyId, ACTOR, new RestoreSpendingRequest(0L)));
        assertThat(restore.code()).isEqualTo("legacy_refund_reconciliation_required");
    }

    @Test
    void deletingLegacyUnlinkedRefundRemainsAllowed() {
        UUID legacyId = UUID.randomUUID();
        SpendingEntry legacy = entry(legacyId, TransactionType.REFUND, "25.00");
        when(spending.findByIdAndDeletedAtIsNull(legacyId)).thenReturn(Optional.of(legacy));
        when(spending.findById(legacyId)).thenReturn(Optional.of(legacy));
        when(spending.findForUpdateById(legacyId)).thenReturn(Optional.of(legacy));
        when(spending.findAllByBudgetItemIdAndDeletedAtIsNullOrderBySpentAtDesc(ITEM))
                .thenReturn(List.of(original, legacy));

        service.delete(legacyId, ACTOR, new VersionRequest(0L));

        assertThat(legacy.getDeletedAt()).isEqualTo(NOW);
    }

    @Test
    void legacyRefundCountsAgainstNewLinkedRefundCapacity() {
        SpendingEntry legacy = entry(UUID.randomUUID(), TransactionType.REFUND, "25.00");
        when(spending.findAllByRefundForSpendingEntryIdAndDeletedAtIsNullOrderBySpentAtAscCreatedAtAsc(ORIGINAL))
                .thenReturn(List.of());
        when(spending.findAllByBudgetItemIdAndDeletedAtIsNullOrderBySpentAtDesc(ITEM))
                .thenReturn(List.of(original, legacy));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.create(ACTOR, linkedRefund("100.00")));

        assertThat(failure.code()).isEqualTo("refund_capacity_exceeded");
        verify(spending, never()).saveAndFlush(any());
    }

    @Test
    void expenseCannotBeReducedDeletedOrMovedWhenThatWouldUncoverLegacyRefunds() {
        SpendingEntry legacy = entry(UUID.randomUUID(), TransactionType.REFUND, "25.00");
        when(spending.findAllByBudgetItemIdAndDeletedAtIsNullOrderBySpentAtDesc(ITEM))
                .thenReturn(List.of(original, legacy));
        when(spending.findAllByRefundForSpendingEntryIdAndDeletedAtIsNullOrderBySpentAtAscCreatedAtAsc(ORIGINAL))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.update(ORIGINAL, ACTOR, update(ITEM, "20.00", 0L)))
                .isInstanceOf(ApiException.class).hasMessageContaining("cannot exceed");
        assertThatThrownBy(() -> service.delete(ORIGINAL, ACTOR, new VersionRequest(0L)))
                .isInstanceOf(ApiException.class).hasMessageContaining("cannot exceed");

        UUID otherItemId = UUID.randomUUID();
        BudgetItem other = item(otherItemId, BUDGET);
        when(budgets.requireItem(otherItemId)).thenReturn(other);
        when(budgets.requireOpenItemForUpdate(otherItemId, ACTOR,
                SpaceAccessService.Capability.RECORD_SPENDING)).thenReturn(other);
        assertThatThrownBy(() -> service.move(ORIGINAL, ACTOR, new MoveSpendingRequest(otherItemId, 0L)))
                .isInstanceOf(ApiException.class).hasMessageContaining("cannot exceed");
    }

    private SpendingRequest linkedRefund(String amount) {
        return new SpendingRequest(UUID.randomUUID(), ITEM, TransactionType.REFUND, "Refund: Groceries",
                new BigDecimal(amount), LocalDate.of(2026, 8, 20), LocalTime.of(12, 0), "Africa/Johannesburg",
                "Card", "Grocer", "Returned goods", ACTOR, ORIGINAL);
    }

    private SpendingUpdateRequest update(UUID itemId, String amount, long version) {
        return new SpendingUpdateRequest(itemId, TransactionType.EXPENSE, "Groceries",
                new BigDecimal(amount), LocalDate.of(2026, 8, 20), LocalTime.of(11, 0),
                "Africa/Johannesburg", "Card", "Grocer", "Weekly shop", ACTOR, version);
    }

    private SpendingUpdateRequest refundUpdate(UUID itemId, String amount, long version) {
        return new SpendingUpdateRequest(itemId, TransactionType.REFUND, "Legacy refund",
                new BigDecimal(amount), LocalDate.of(2026, 8, 20), LocalTime.of(11, 0),
                "Africa/Johannesburg", "Card", "Grocer", "Adjusted legacy refund", ACTOR, version);
    }

    private BudgetItem item(UUID id, UUID budgetId) {
        BudgetItem value = new BudgetItem();
        value.setId(id); value.setSpaceId(SPACE); value.setBudgetMonthId(budgetId); value.setName("Groceries");
        value.setCategoryId(UUID.randomUUID()); value.setPlannedAmount(new BigDecimal("1000.00"));
        value.setItemType(BudgetItemType.PLANNED); value.setTracked(true); return value;
    }

    private SpendingEntry entry(UUID id, TransactionType type, String amount) {
        SpendingEntry value = new SpendingEntry();
        value.setId(id); value.setSpaceId(SPACE); value.setBudgetItemId(ITEM); value.setTransactionType(type);
        value.setTitle(type == TransactionType.EXPENSE ? "Groceries" : "Refund: Groceries");
        value.setAmount(new BigDecimal(amount)); value.setSpentAt(NOW); value.setUserSelectedDate(LocalDate.of(2026, 8, 20));
        value.setUserSelectedTime(LocalTime.NOON); value.setTimeZone("Africa/Johannesburg");
        value.setSpentByUserId(ACTOR); value.setCreatedByUserId(ACTOR); value.setVersion(0L); return value;
    }
}
