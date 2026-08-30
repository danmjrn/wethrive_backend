package solutions.shapeit.wethrive.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import solutions.shapeit.wethrive.audit.service.AuditService;
import solutions.shapeit.wethrive.common.domain.DomainChangeRecorder;
import solutions.shapeit.wethrive.common.domain.DomainEnums.AuditResult;
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.DeductionType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.FundingSourceType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.IncomeStatus;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetCopyRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.FundingAllocationRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.FundingAllocationUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptDeductionUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeStateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.VersionRequest;
import solutions.shapeit.wethrive.finance.entity.BudgetFundingAllocation;
import solutions.shapeit.wethrive.finance.entity.BudgetItem;
import solutions.shapeit.wethrive.finance.entity.BudgetMonth;
import solutions.shapeit.wethrive.finance.entity.IncomeEntry;
import solutions.shapeit.wethrive.finance.entity.IncomeDeduction;
import solutions.shapeit.wethrive.finance.entity.IncomeReceipt;
import solutions.shapeit.wethrive.finance.entity.IncomeReceiptDeduction;
import solutions.shapeit.wethrive.finance.repository.BudgetFundingAllocationRepository;
import solutions.shapeit.wethrive.finance.repository.BudgetItemRepository;
import solutions.shapeit.wethrive.finance.repository.BudgetMonthRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeEntryRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeDeductionRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptDeductionRepository;
import solutions.shapeit.wethrive.finance.repository.SpendingEntryRepository;
import solutions.shapeit.wethrive.space.entity.Space;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;
import solutions.shapeit.wethrive.space.service.SpaceService;

class BudgetServiceFundingInvariantTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID BUDGET = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID INCOME = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID TYPE = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final Instant NOW = Instant.parse("2026-07-15T10:00:00Z");

    private final BudgetMonthRepository budgets = mock(BudgetMonthRepository.class);
    private final IncomeEntryRepository income = mock(IncomeEntryRepository.class);
    private final IncomeReceiptRepository receipts = mock(IncomeReceiptRepository.class);
    private final IncomeDeductionRepository deductions = mock(IncomeDeductionRepository.class);
    private final IncomeReceiptDeductionRepository receiptDeductions = mock(IncomeReceiptDeductionRepository.class);
    private final BudgetItemRepository items = mock(BudgetItemRepository.class);
    private final BudgetFundingAllocationRepository allocations = mock(BudgetFundingAllocationRepository.class);
    private final SpendingEntryRepository spending = mock(SpendingEntryRepository.class);
    private final ReferenceFinanceService references = mock(ReferenceFinanceService.class);
    private final SpaceAccessService access = mock(SpaceAccessService.class);
    private final SpaceService spaces = mock(SpaceService.class);
    private final AuditService audit = mock(AuditService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DomainChangeRecorder> changes = mock(ObjectProvider.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final FinanceCalculationService calculations = new FinanceCalculationService();
    private BudgetService service;
    private IncomeEntry salary;

    @BeforeEach
    void setUp() {
        service = new BudgetService(budgets, income, receipts, deductions, receiptDeductions,
                items, allocations, spending, references,
                calculations, access, spaces, audit, changes, clock, events);
        when(changes.orderedStream()).thenAnswer(invocation -> Stream.empty());
        BudgetMonth budget = new BudgetMonth(); budget.setId(BUDGET); budget.setSpaceId(SPACE);
        budget.setYear(2026); budget.setMonth(7); budget.setStatus(BudgetStatus.ACTIVE);
        when(budgets.findByIdAndDeletedAtIsNull(BUDGET)).thenReturn(Optional.of(budget));
        when(budgets.findForUpdateById(BUDGET)).thenReturn(Optional.of(budget));
        Space space = new Space(); space.setId(SPACE); space.setTimeZone("Africa/Johannesburg");
        when(spaces.requireSpace(SPACE)).thenReturn(space);
        salary = income(INCOME, "5000.00");
        when(income.findForUpdateById(INCOME)).thenReturn(Optional.of(salary));
        when(income.findByIdAndDeletedAtIsNull(INCOME)).thenReturn(Optional.of(salary));
        when(income.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(budgets.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(items.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(receipts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(deductions.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(receiptDeductions.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(allocations.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void rejectsReceiptReductionBelowConfirmedCoverage() {
        IncomeReceipt receipt = receipt(INCOME, "5000.00");
        BudgetFundingAllocation confirmed = allocation(INCOME, "4500.00", "4500.00");
        stubLockedReceipt(receipt);
        when(receipts.findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(INCOME)).thenReturn(List.of(receipt));
        when(allocations.findAllByIncomeEntryIdAndDeletedAtIsNullOrderByCreatedAtAsc(INCOME)).thenReturn(List.of(confirmed));

        assertThatThrownBy(() -> service.updateReceipt(receipt.getId(), ACTOR,
                new IncomeReceiptUpdateRequest(new BigDecimal("4000.00"), NOW, "Africa/Johannesburg", "Reduced", 0L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("confirmed allocations");
        verify(audit, never()).record(eq(ACTOR), eq(SPACE), eq("INCOME_RECEIPT_UPDATED"),
                eq("INCOME_RECEIPT"), eq(receipt.getId()), eq(AuditResult.SUCCESS), any());
    }

    @Test
    void incomeStatusUsesTheEntryTimeZoneAcrossTheMidnightBoundary() {
        salary.setExpectedDate(LocalDate.of(2026, 7, 16));
        salary.setTimeZone("Pacific/Kiritimati");
        when(income.findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(BUDGET))
                .thenReturn(List.of(salary));

        var result = service.income(BUDGET, ACTOR);

        assertThat(result).singleElement().satisfies(value ->
                assertThat(value.availability().status()).isEqualTo(IncomeStatus.DUE));
    }

    @Test
    void plannedOnlyFundingDoesNotBlockReceiptDeletion() {
        IncomeReceipt receipt = receipt(INCOME, "5000.00");
        BudgetFundingAllocation planned = allocation(INCOME, "6000.00", "0.00");
        stubLockedReceipt(receipt);
        when(receipts.findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(INCOME)).thenReturn(List.of(receipt));
        when(allocations.findAllByIncomeEntryIdAndDeletedAtIsNullOrderByCreatedAtAsc(INCOME)).thenReturn(List.of(planned));

        service.deleteReceipt(receipt.getId(), ACTOR, new IncomeStateRequest(0L));

        assertThat(receipt.getDeletedAt()).isEqualTo(NOW);
        verify(receipts).saveAndFlush(receipt);
    }

    @Test
    void rejectsReviewedReceiptDeductionThatWouldOverallocateReceivedIncome() {
        IncomeReceipt receipt = receipt(INCOME, "5000.00");
        IncomeDeduction planned = new IncomeDeduction(); planned.setId(UUID.randomUUID());
        planned.setIncomeEntryId(INCOME); planned.setSpaceId(SPACE); planned.setName("Medical aid");
        planned.setDeductionType(DeductionType.FIXED); planned.setFixedAmount(new BigDecimal("500.00"));
        IncomeReceiptDeduction actual = new IncomeReceiptDeduction(); actual.setId(UUID.randomUUID());
        actual.setIncomeReceiptId(receipt.getId()); actual.setIncomeDeductionId(planned.getId());
        actual.setSpaceId(SPACE); actual.setNameSnapshot("Medical aid"); actual.setActualAmount(new BigDecimal("0.00"));
        BudgetFundingAllocation confirmed = allocation(INCOME, "5000.00", "5000.00");
        when(receiptDeductions.findIncomeReceiptIdById(actual.getId())).thenReturn(Optional.of(receipt.getId()));
        when(receipts.findIncomeEntryIdById(receipt.getId())).thenReturn(Optional.of(INCOME));
        when(receipts.findForUpdateById(receipt.getId())).thenReturn(Optional.of(receipt));
        when(receiptDeductions.findAllForUpdateByIncomeReceiptId(receipt.getId())).thenReturn(List.of(actual));
        when(receipts.findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(INCOME)).thenReturn(List.of(receipt));
        when(deductions.findAllByIncomeEntryIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(INCOME)).thenReturn(List.of(planned));
        when(receiptDeductions.findAllByIncomeReceiptIdInAndDeletedAtIsNull(List.of(receipt.getId())))
                .thenReturn(List.of(actual));
        when(allocations.findAllByIncomeEntryIdAndDeletedAtIsNullOrderByCreatedAtAsc(INCOME)).thenReturn(List.of(confirmed));

        assertThatThrownBy(() -> service.updateReceiptDeduction(actual.getId(), ACTOR,
                new IncomeReceiptDeductionUpdateRequest(new BigDecimal("100.00"), 0L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("confirmed allocations");
        verify(audit, never()).record(eq(ACTOR), eq(SPACE),
                eq("INCOME_RECEIPT_DEDUCTION_UPDATED"), eq("INCOME_RECEIPT_DEDUCTION"),
                eq(actual.getId()), eq(AuditResult.SUCCESS), any());
    }

    @Test
    void sourceSwitchRejectsCashThatIsUnavailableOnTheNewIncome() {
        UUID secondIncomeId = UUID.fromString("00000000-0000-0000-0000-000000000006");
        IncomeEntry second = income(secondIncomeId, "500.00");
        when(income.findForUpdateById(secondIncomeId)).thenReturn(Optional.of(second));
        when(income.findByIdAndDeletedAtIsNull(secondIncomeId)).thenReturn(Optional.of(second));
        IncomeReceipt secondReceipt = receipt(secondIncomeId, "500.00");
        when(receipts.findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(secondIncomeId)).thenReturn(List.of(secondReceipt));
        when(allocations.findAllByIncomeEntryIdAndDeletedAtIsNullOrderByCreatedAtAsc(secondIncomeId)).thenReturn(List.of());

        BudgetItem item = item();
        BudgetFundingAllocation existing = allocation(INCOME, "1000.00", "1000.00"); existing.setBudgetItemId(item.getId());
        var scope = mock(BudgetFundingAllocationRepository.LockScope.class);
        when(scope.getBudgetItemId()).thenReturn(item.getId()); when(scope.getIncomeEntryId()).thenReturn(INCOME);
        when(allocations.findLockScopeById(existing.getId())).thenReturn(Optional.of(scope));
        when(allocations.findForUpdateById(existing.getId())).thenReturn(Optional.of(existing));
        when(items.findByIdAndDeletedAtIsNull(item.getId())).thenReturn(Optional.of(item));

        FundingAllocationUpdateRequest request = new FundingAllocationUpdateRequest(secondIncomeId,
                FundingSourceType.INCOME_ENTRY, new BigDecimal("1000.00"), new BigDecimal("1000.00"),
                NOW, "Africa/Johannesburg", "Switch", 0L);

        assertThatThrownBy(() -> service.updateFundingAllocation(existing.getId(), ACTOR, request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("unallocated received balance");
        verify(allocations, never()).saveAndFlush(any());
    }

    @Test
    void partialReductionIsAuditedAndReversedRowsRemainInHistory() {
        BudgetItem item = item();
        BudgetFundingAllocation external = allocation(null, "2000.00", "2000.00");
        external.setBudgetItemId(item.getId()); external.setSourceType(FundingSourceType.EXTERNAL_FUNDS);
        var scope = mock(BudgetFundingAllocationRepository.LockScope.class);
        when(scope.getBudgetItemId()).thenReturn(item.getId()); when(scope.getIncomeEntryId()).thenReturn(null);
        when(allocations.findLockScopeById(external.getId())).thenReturn(Optional.of(scope));
        when(allocations.findForUpdateById(external.getId())).thenReturn(Optional.of(external));
        when(items.findByIdAndDeletedAtIsNull(item.getId())).thenReturn(Optional.of(item));

        service.updateFundingAllocation(external.getId(), ACTOR, new FundingAllocationUpdateRequest(null,
                FundingSourceType.EXTERNAL_FUNDS, new BigDecimal("1500.00"), new BigDecimal("1500.00"),
                NOW, "Africa/Johannesburg", "Reduced", 0L));

        verify(audit).record(eq(ACTOR), eq(SPACE), eq("FUNDING_ALLOCATION_REDUCED"),
                eq("FUNDING_ALLOCATION"), eq(external.getId()), eq(AuditResult.SUCCESS),
                org.mockito.ArgumentMatchers.contains("released=500.00"));

        external.setDeletedAt(NOW);
        when(allocations.findAllBySpaceIdOrderByCreatedAtAsc(SPACE)).thenReturn(List.of(external));
        var history = service.fundingAllocationHistory(SPACE, ACTOR);
        assertThat(history).singleElement().satisfies(value -> assertThat(value.deletedAt()).isEqualTo(NOW));
    }

    @Test
    void externalFundingCreationLocksBudgetThenItemBeforeCheckingOrCreatingAllocation() {
        BudgetItem item = item();
        UUID allocationId = UUID.fromString("00000000-0000-0000-0000-00000000000a");

        service.createFundingAllocation(item.getId(), ACTOR, new FundingAllocationRequest(allocationId,
                item.getId(), null, FundingSourceType.EXTERNAL_FUNDS, new BigDecimal("1000.00"),
                new BigDecimal("1000.00"), NOW, "Africa/Johannesburg", "External reserve"));

        InOrder lifecycle = inOrder(budgets, items, allocations);
        lifecycle.verify(budgets).findForUpdateById(BUDGET);
        lifecycle.verify(items).findForUpdateById(item.getId());
        lifecycle.verify(allocations).existsById(allocationId);
        verify(income, never()).findForUpdateById(any());
    }

    @Test
    void itemDeletionUsesTheSameBudgetThenItemLockPrefixBeforeAllocationCheck() {
        BudgetItem item = item();

        service.deleteItem(item.getId(), ACTOR, new VersionRequest(0L));

        InOrder lifecycle = inOrder(budgets, items, allocations);
        lifecycle.verify(budgets).findForUpdateById(BUDGET);
        lifecycle.verify(items).findForUpdateById(item.getId());
        lifecycle.verify(allocations).existsByBudgetItemId(item.getId());
        assertThat(item.getDeletedAt()).isEqualTo(NOW);
    }

    @Test
    void fullyReceivedIncomeRejectsAnotherReceiptAfterLockingTheParent() {
        IncomeReceipt existing = receipt(INCOME, "5000.00");
        when(receipts.findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(INCOME))
                .thenReturn(List.of(existing));

        ApiException failure = assertThrows(ApiException.class, () -> service.createReceipt(INCOME, ACTOR,
                new IncomeReceiptRequest(UUID.randomUUID(), INCOME, new BigDecimal("1.00"), NOW,
                        "Africa/Johannesburg", "Stale device", List.of())));

        assertThat(failure.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        assertThat(failure.code()).isEqualTo("income_already_received");
        assertThat(failure).hasMessageContaining("already fully received");

        InOrder serialized = inOrder(income, receipts);
        serialized.verify(income).findForUpdateById(INCOME);
        serialized.verify(receipts).findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(INCOME);
        verify(receipts, never()).saveAndFlush(any());
    }

    @Test
    void partialAndZeroExpectedIncomeFollowCanonicalDerivedStateBeforeReceiptCreation() {
        IncomeReceipt partial = receipt(INCOME, "2000.00");
        AtomicReference<IncomeReceipt> created = new AtomicReference<>();
        when(receipts.findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(INCOME))
                .thenAnswer(invocation -> created.get() == null ? List.of(partial) : List.of(partial, created.get()));
        when(receipts.saveAndFlush(any(IncomeReceipt.class))).thenAnswer(invocation -> {
            IncomeReceipt saved = invocation.getArgument(0); created.set(saved); return saved;
        });

        var additional = service.createReceipt(INCOME, ACTOR,
                new IncomeReceiptRequest(UUID.randomUUID(), INCOME, new BigDecimal("3000.00"), NOW,
                        "Africa/Johannesburg", "Remaining salary", List.of()));

        assertThat(additional.amount()).isEqualByComparingTo("3000.00");

        salary.setExpectedAmount(BigDecimal.ZERO);
        created.set(null);
        when(receipts.findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(INCOME))
                .thenAnswer(invocation -> created.get() == null ? List.of() : List.of(created.get()));
        assertThat(service.incomeAvailability(INCOME, ACTOR).status()).isEqualTo(IncomeStatus.SCHEDULED);
        var firstForZero = service.createReceipt(INCOME, ACTOR,
                new IncomeReceiptRequest(UUID.randomUUID(), INCOME, new BigDecimal("1.00"), NOW,
                        "Africa/Johannesburg", "Unexpected income", List.of()));
        assertThat(firstForZero.amount()).isEqualByComparingTo("1.00");
    }

    @Test
    void budgetDeletionLocksBudgetThenEveryItemBeforeCheckingHistoricalFunding() {
        BudgetItem item = item();
        when(items.findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(BUDGET))
                .thenReturn(List.of(item));
        when(items.findAllByBudgetMonthIdOrderBySortOrderAscCreatedAtAsc(BUDGET)).thenReturn(List.of(item));

        service.delete(BUDGET, ACTOR, new VersionRequest(0L));

        InOrder lifecycle = inOrder(budgets, items, allocations);
        lifecycle.verify(budgets).findForUpdateById(BUDGET);
        lifecycle.verify(items).findForUpdateById(item.getId());
        lifecycle.verify(allocations).countByBudgetItemIdIn(List.of(item.getId()));
        assertThat(item.getDeletedAt()).isEqualTo(NOW);
    }

    @Test
    void budgetDeletionRejectsHistoryOwnedByTombstonedChildren() {
        BudgetItem removedItem = item();
        removedItem.setDeletedAt(NOW);
        IncomeEntry removedIncome = income(UUID.randomUUID(), "1000.00");
        removedIncome.setDeletedAt(NOW);
        when(items.findAllByBudgetMonthIdOrderBySortOrderAscCreatedAtAsc(BUDGET))
                .thenReturn(List.of(removedItem));
        when(income.findAllByBudgetMonthIdOrderBySortOrderAscCreatedAtAsc(BUDGET))
                .thenReturn(List.of(removedIncome));
        when(spending.existsByBudgetItemId(removedItem.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.delete(BUDGET, ACTOR, new VersionRequest(0L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("history cannot be deleted");

        verify(budgets, never()).saveAndFlush(any());
        verify(items, never()).saveAndFlush(any());
        verify(income, never()).saveAndFlush(any());
    }

    @Test
    void budgetDeletionRejectsReversedFundingAndDeletedReceiptHistory() {
        BudgetItem item = item();
        when(items.findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(BUDGET))
                .thenReturn(List.of(item));
        when(items.findAllByBudgetMonthIdOrderBySortOrderAscCreatedAtAsc(BUDGET)).thenReturn(List.of(item));
        when(income.findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(BUDGET))
                .thenReturn(List.of(salary));
        when(income.findAllByBudgetMonthIdOrderBySortOrderAscCreatedAtAsc(BUDGET)).thenReturn(List.of(salary));
        when(allocations.countByBudgetItemIdIn(List.of(item.getId()))).thenReturn(1L);
        when(receipts.countByIncomeEntryIdIn(List.of(INCOME))).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(BUDGET, ACTOR, new VersionRequest(0L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("history cannot be deleted");

        verify(budgets, never()).saveAndFlush(any());
    }

    @Test
    void incomeDeletionRejectsDeletedReceiptAndReversedAllocationHistory() {
        IncomeReceipt deletedReceipt = receipt(INCOME, "500.00");
        deletedReceipt.setDeletedAt(NOW);
        when(receipts.findAllByIncomeEntryIdOrderByReceivedAtAscCreatedAtAsc(INCOME))
                .thenReturn(List.of(deletedReceipt));

        assertThatThrownBy(() -> service.deleteIncome(INCOME, ACTOR, new VersionRequest(0L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot be deleted");

        when(receipts.findAllByIncomeEntryIdOrderByReceivedAtAscCreatedAtAsc(INCOME)).thenReturn(List.of());
        BudgetFundingAllocation reversed = allocation(INCOME, "500.00", "0.00");
        reversed.setDeletedAt(NOW);
        when(allocations.findAllByIncomeEntryIdOrderByCreatedAtAsc(INCOME)).thenReturn(List.of(reversed));

        assertThatThrownBy(() -> service.deleteIncome(INCOME, ACTOR, new VersionRequest(0L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot be deleted");
        verify(income, never()).saveAndFlush(any());
    }

    @Test
    void copyWithScheduleAdjustmentPreservesOffsetFromSourceBudgetStart() {
        UUID targetId = UUID.fromString("00000000-0000-0000-0000-000000000007");
        salary.setExpectedDate(LocalDate.of(2026, 7, 31));
        salary.setExpectedAmount(new BigDecimal("5000.00"));
        salary.setTitheEnabled(true);
        salary.setTitheAmountOverride(new BigDecimal("500.00"));
        salary.setRecurring(true);
        IncomeEntry oneOff = income(UUID.fromString("00000000-0000-0000-0000-000000000008"), "400.00");
        oneOff.setRecurring(false);
        IncomeEntry cancelled = income(UUID.fromString("00000000-0000-0000-0000-000000000009"), "300.00");
        cancelled.setRecurring(true);
        cancelled.setCancelledAt(NOW);
        when(income.findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(BUDGET))
                .thenReturn(List.of(salary, oneOff, cancelled));

        AtomicReference<BudgetMonth> target = new AtomicReference<>();
        when(budgets.saveAndFlush(any(BudgetMonth.class))).thenAnswer(invocation -> {
            BudgetMonth value = invocation.getArgument(0);
            if (targetId.equals(value.getId())) target.set(value);
            return value;
        });
        when(budgets.findByIdAndDeletedAtIsNull(targetId)).thenAnswer(invocation -> Optional.ofNullable(target.get()));

        service.copy(BUDGET, ACTOR, new BudgetCopyRequest(targetId, 2027, 2, "February 2027",
                false, false, false, true, true, false));

        ArgumentCaptor<IncomeEntry> clone = ArgumentCaptor.forClass(IncomeEntry.class);
        verify(income).saveAndFlush(clone.capture());
        assertThat(clone.getValue().getBudgetMonthId()).isEqualTo(targetId);
        assertThat(clone.getValue().getExpectedDate()).isEqualTo(LocalDate.of(2027, 3, 3));
        assertThat(clone.getValue().getExpectedAmount()).isEqualByComparingTo("0.00");
        assertThat(clone.getValue().getTitheAmountOverride()).isEqualByComparingTo("0.00");
    }

    @Test
    void copyRejectsAnAdjustedExpectedDateBeyondTheSupportedYear() {
        salary.setExpectedDate(LocalDate.of(2026, 8, 5));
        salary.setRecurring(true);
        when(income.findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(BUDGET))
                .thenReturn(List.of(salary));

        assertThatThrownBy(() -> service.copy(BUDGET, ACTOR, new BudgetCopyRequest(UUID.randomUUID(),
                2200, 12, "December 2200", false, true, true, true, true, false)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("2000 to 2200");
        verify(income, never()).saveAndFlush(any());
    }

    @Test
    void incomeExpectedDateMayFallOutsideItsBudgetMonth() {
        IncomeUpdateRequest request = new IncomeUpdateRequest("Salary", TYPE, new BigDecimal("5000.00"),
                LocalDate.of(2026, 6, 25), null, "Africa/Johannesburg", false,
                new BigDecimal("0.1000"), null, false, null, "Early salary", 0, 0L);

        var response = service.updateIncome(INCOME, ACTOR, request);

        assertThat(response.expectedDate()).isEqualTo(LocalDate.of(2026, 6, 25));
        verify(income).saveAndFlush(salary);
    }

    @Test
    void rejectsExpectedIncomeDatesOutsideTheSupportedYearRange() {
        IncomeUpdateRequest request = new IncomeUpdateRequest("Salary", TYPE, new BigDecimal("5000.00"),
                LocalDate.of(2201, 1, 1), null, "Africa/Johannesburg", false,
                new BigDecimal("0.1000"), null, false, null, "Too far ahead", 0, 0L);

        assertThatThrownBy(() -> service.updateIncome(INCOME, ACTOR, request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("2000 to 2200");
        verify(income, never()).saveAndFlush(any());
    }

    @Test
    void editingMigratedLegacyTithePreservesReviewedReceiptActuals() {
        salary.setExpectedAmount(new BigDecimal("5000.00"));
        salary.setTitheEnabled(true); salary.setTitheRate(new BigDecimal("0.1000"));
        IncomeDeduction legacy = new IncomeDeduction(); legacy.setId(INCOME); legacy.setIncomeEntryId(INCOME);
        legacy.setSpaceId(SPACE); legacy.setName("Tithe"); legacy.setDeductionType(DeductionType.PERCENTAGE);
        legacy.setPercentageRate(new BigDecimal("0.1000")); legacy.setLegacyTithe(true);
        IncomeReceipt reviewedReceipt = receipt(INCOME, "4000.00");
        IncomeReceiptDeduction reviewedActual = new IncomeReceiptDeduction(); reviewedActual.setId(reviewedReceipt.getId());
        reviewedActual.setIncomeReceiptId(reviewedReceipt.getId()); reviewedActual.setIncomeDeductionId(INCOME);
        reviewedActual.setSpaceId(SPACE); reviewedActual.setNameSnapshot("Tithe");
        reviewedActual.setActualAmount(new BigDecimal("375.00"));
        when(deductions.findById(INCOME)).thenReturn(Optional.of(legacy));
        when(deductions.findAllByIncomeEntryIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(INCOME))
                .thenReturn(List.of(legacy));
        when(receipts.findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(INCOME))
                .thenReturn(List.of(reviewedReceipt));
        when(receiptDeductions.findAllByIncomeReceiptIdInAndDeletedAtIsNull(List.of(reviewedReceipt.getId())))
                .thenReturn(List.of(reviewedActual));

        var response = service.updateIncome(INCOME, ACTOR, new IncomeUpdateRequest("Salary", TYPE,
                new BigDecimal("5000.00"), LocalDate.of(2026, 7, 25), null, "Africa/Johannesburg",
                true, new BigDecimal("0.2000"), null, false, null, "Prospective rate", 0, 0L));

        assertThat(legacy.getPercentageRate()).isEqualByComparingTo("0.2000");
        assertThat(reviewedActual.getActualAmount()).isEqualByComparingTo("375.00");
        assertThat(response.availability().realizedDeductions()).isEqualByComparingTo("375.00");
        verify(receiptDeductions, never()).saveAndFlush(any());
    }

    @Test
    void legacyQueuedReceiptSynthesizesCanonicalTitheLine() {
        salary.setExpectedAmount(new BigDecimal("10000.00")); salary.setTitheEnabled(true);
        salary.setTitheRate(new BigDecimal("0.1000"));
        IncomeDeduction legacy = new IncomeDeduction(); legacy.setId(INCOME); legacy.setIncomeEntryId(INCOME);
        legacy.setSpaceId(SPACE); legacy.setName("Tithe"); legacy.setDeductionType(DeductionType.PERCENTAGE);
        legacy.setPercentageRate(new BigDecimal("0.1000")); legacy.setLegacyTithe(true);
        UUID receiptId = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        AtomicReference<IncomeReceipt> savedReceipt = new AtomicReference<>();
        AtomicReference<IncomeReceiptDeduction> savedDeduction = new AtomicReference<>();
        when(receipts.saveAndFlush(any(IncomeReceipt.class))).thenAnswer(invocation -> {
            IncomeReceipt value = invocation.getArgument(0); savedReceipt.set(value); return value;
        });
        when(receiptDeductions.saveAndFlush(any(IncomeReceiptDeduction.class))).thenAnswer(invocation -> {
            IncomeReceiptDeduction value = invocation.getArgument(0); savedDeduction.set(value); return value;
        });
        when(deductions.findByIncomeEntryIdAndLegacyTitheTrueAndDeletedAtIsNull(INCOME))
                .thenReturn(Optional.of(legacy));
        when(deductions.findAllByIncomeEntryIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(INCOME))
                .thenReturn(List.of(legacy));
        when(receipts.findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(INCOME))
                .thenAnswer(invocation -> savedReceipt.get() == null ? List.of() : List.of(savedReceipt.get()));
        when(receiptDeductions.findAllForUpdateByIncomeReceiptIdIn(List.of(receiptId))).thenReturn(List.of());
        when(receiptDeductions.findAllByIncomeReceiptIdAndDeletedAtIsNullOrderByCreatedAtAsc(receiptId))
                .thenAnswer(invocation -> savedDeduction.get() == null ? List.of() : List.of(savedDeduction.get()));
        when(receiptDeductions.findAllByIncomeReceiptIdInAndDeletedAtIsNull(List.of(receiptId)))
                .thenAnswer(invocation -> savedDeduction.get() == null ? List.of() : List.of(savedDeduction.get()));

        var response = service.createReceipt(INCOME, ACTOR, new IncomeReceiptRequest(receiptId, INCOME,
                new BigDecimal("4000.00"), NOW, "Africa/Johannesburg", "Partial salary"));

        assertThat(response.totalDeductions()).isEqualByComparingTo("400.00");
        assertThat(response.netAmount()).isEqualByComparingTo("3600.00");
        assertThat(response.deductions()).singleElement().satisfies(value -> {
            assertThat(value.id()).isEqualTo(receiptId);
            assertThat(value.incomeDeductionId()).isEqualTo(INCOME);
            assertThat(value.amount()).isEqualByComparingTo("400.00");
        });
    }

    private void stubLockedReceipt(IncomeReceipt receipt) {
        when(receipts.findIncomeEntryIdById(receipt.getId())).thenReturn(Optional.of(INCOME));
        when(receipts.findForUpdateById(receipt.getId())).thenReturn(Optional.of(receipt));
    }

    private IncomeEntry income(UUID id, String expected) {
        IncomeEntry value = new IncomeEntry(); value.setId(id); value.setSpaceId(SPACE); value.setBudgetMonthId(BUDGET);
        value.setIncomeTypeId(TYPE); value.setSourceName("Salary"); value.setExpectedAmount(new BigDecimal(expected));
        value.setExpectedDate(LocalDate.of(2026, 7, 25)); value.setTimeZone("Africa/Johannesburg");
        value.setTitheRate(new BigDecimal("0.1000")); return value;
    }

    private IncomeReceipt receipt(UUID incomeId, String amount) {
        IncomeReceipt value = new IncomeReceipt(); value.setId(UUID.randomUUID()); value.setSpaceId(SPACE);
        value.setIncomeEntryId(incomeId); value.setAmount(new BigDecimal(amount)); value.setReceivedAt(NOW);
        value.setTimeZone("Africa/Johannesburg"); value.setCreatedByUserId(ACTOR); value.setUpdatedByUserId(ACTOR);
        value.setRecordedByUserId(ACTOR); return value;
    }

    private BudgetFundingAllocation allocation(UUID incomeId, String planned, String confirmed) {
        BudgetFundingAllocation value = new BudgetFundingAllocation(); value.setId(UUID.randomUUID()); value.setSpaceId(SPACE);
        value.setIncomeEntryId(incomeId); value.setSourceType(incomeId == null ? FundingSourceType.EXTERNAL_FUNDS : FundingSourceType.INCOME_ENTRY);
        value.setPlannedAmount(new BigDecimal(planned)); value.setConfirmedAllocatedAmount(new BigDecimal(confirmed));
        value.setAllocatedAt(NOW); value.setTimeZone("Africa/Johannesburg"); value.setCreatedByUserId(ACTOR); value.setUpdatedByUserId(ACTOR);
        return value;
    }

    private BudgetItem item() {
        BudgetItem value = new BudgetItem(); value.setId(UUID.randomUUID()); value.setSpaceId(SPACE);
        value.setBudgetMonthId(BUDGET); value.setPlannedAmount(new BigDecimal("2000.00")); value.setName("Transport");
        when(items.findBudgetMonthIdById(value.getId())).thenReturn(Optional.of(BUDGET));
        when(items.findForUpdateById(value.getId())).thenReturn(Optional.of(value));
        return value;
    }
}
