package solutions.shapeit.wethrive.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import solutions.shapeit.wethrive.audit.service.AuditService;
import solutions.shapeit.wethrive.common.domain.DomainChangeRecorder;
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.IncomeStatus;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeStateRequest;
import solutions.shapeit.wethrive.finance.entity.BudgetMonth;
import solutions.shapeit.wethrive.finance.entity.IncomeEntry;
import solutions.shapeit.wethrive.finance.entity.IncomeReceipt;
import solutions.shapeit.wethrive.finance.repository.BudgetFundingAllocationRepository;
import solutions.shapeit.wethrive.finance.repository.BudgetItemRepository;
import solutions.shapeit.wethrive.finance.repository.BudgetMonthRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeDeductionRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeEntryRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptDeductionRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptRepository;
import solutions.shapeit.wethrive.finance.repository.SpendingEntryRepository;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;
import solutions.shapeit.wethrive.space.service.SpaceService;

/**
 * Runs receipt mutations through {@link BudgetService} while retaining an in-memory receipt ledger
 * behind the repository mock, so derived state and tombstone history are checked together.
 *
 * @author Daniel Jr Nkulu
 */
class IncomeReceiptLifecycleAcceptanceTest {
    private static final UUID ACTOR = UUID.fromString("31000000-0000-0000-0000-000000000001");
    private static final UUID SPACE = UUID.fromString("31000000-0000-0000-0000-000000000002");
    private static final UUID BUDGET = UUID.fromString("31000000-0000-0000-0000-000000000003");
    private static final UUID INCOME = UUID.fromString("31000000-0000-0000-0000-000000000004");
    private static final UUID TYPE = UUID.fromString("31000000-0000-0000-0000-000000000005");
    private static final UUID FIRST_RECEIPT = UUID.fromString("31000000-0000-0000-0000-000000000006");
    private static final UUID SECOND_RECEIPT = UUID.fromString("31000000-0000-0000-0000-000000000007");
    private static final Instant NOW = Instant.parse("2026-08-24T10:00:00Z");
    private static final String TIME_ZONE = "Africa/Johannesburg";

    private final BudgetMonthRepository budgets = mock(BudgetMonthRepository.class);
    private final IncomeEntryRepository incomes = mock(IncomeEntryRepository.class);
    private final IncomeReceiptRepository receipts = mock(IncomeReceiptRepository.class);
    private final IncomeDeductionRepository deductions = mock(IncomeDeductionRepository.class);
    private final IncomeReceiptDeductionRepository receiptDeductions =
            mock(IncomeReceiptDeductionRepository.class);
    private final BudgetItemRepository items = mock(BudgetItemRepository.class);
    private final BudgetFundingAllocationRepository allocations =
            mock(BudgetFundingAllocationRepository.class);
    private final SpendingEntryRepository spending = mock(SpendingEntryRepository.class);
    private final ReferenceFinanceService references = mock(ReferenceFinanceService.class);
    private final SpaceAccessService access = mock(SpaceAccessService.class);
    private final SpaceService spaces = mock(SpaceService.class);
    private final AuditService audit = mock(AuditService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DomainChangeRecorder> changes = mock(ObjectProvider.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final List<IncomeReceipt> receiptLedger = new ArrayList<>();
    private BudgetService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new BudgetService(budgets, incomes, receipts, deductions, receiptDeductions,
                items, allocations, spending, references, new FinanceCalculationService(), access,
                spaces, audit, changes, clock, events);
        when(changes.orderedStream()).thenAnswer(invocation -> Stream.empty());

        BudgetMonth budget = new BudgetMonth();
        budget.setId(BUDGET);
        budget.setSpaceId(SPACE);
        budget.setYear(2026);
        budget.setMonth(8);
        budget.setStatus(BudgetStatus.ACTIVE);
        when(budgets.findByIdAndDeletedAtIsNull(BUDGET)).thenReturn(Optional.of(budget));

        IncomeEntry income = new IncomeEntry();
        income.setId(INCOME);
        income.setSpaceId(SPACE);
        income.setBudgetMonthId(BUDGET);
        income.setIncomeTypeId(TYPE);
        income.setSourceName("Salary");
        income.setExpectedAmount(new BigDecimal("5000.00"));
        income.setExpectedDate(LocalDate.of(2026, 8, 25));
        income.setTimeZone(TIME_ZONE);
        income.setTitheRate(new BigDecimal("0.1000"));
        when(incomes.findForUpdateById(INCOME)).thenReturn(Optional.of(income));
        when(incomes.findByIdAndDeletedAtIsNull(INCOME)).thenReturn(Optional.of(income));

        when(receipts.saveAndFlush(any(IncomeReceipt.class))).thenAnswer(invocation -> {
            IncomeReceipt saved = invocation.getArgument(0);
            if (receiptLedger.stream().noneMatch(existing -> existing.getId().equals(saved.getId()))) {
                receiptLedger.add(saved);
            }
            return saved;
        });
        when(receipts.existsById(any(UUID.class))).thenAnswer(invocation -> receiptLedger.stream()
                .anyMatch(value -> value.getId().equals(invocation.getArgument(0))));
        when(receipts.findIncomeEntryIdById(any(UUID.class))).thenAnswer(invocation -> activeReceipt(
                invocation.getArgument(0)).map(IncomeReceipt::getIncomeEntryId));
        when(receipts.findForUpdateById(any(UUID.class))).thenAnswer(invocation ->
                activeReceipt(invocation.getArgument(0)));
        when(receipts.findAllByIncomeEntryIdAndDeletedAtIsNullOrderByReceivedAtAscCreatedAtAsc(INCOME))
                .thenAnswer(invocation -> receiptLedger.stream()
                        .filter(value -> value.getDeletedAt() == null).toList());
        when(receipts.findAllByIncomeEntryIdOrderByReceivedAtAscCreatedAtAsc(INCOME))
                .thenAnswer(invocation -> List.copyOf(receiptLedger));

        when(deductions.findAllForUpdateByIncomeEntryId(INCOME)).thenReturn(List.of());
        when(deductions.findAllByIncomeEntryIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(INCOME))
                .thenReturn(List.of());
        when(allocations.findAllByIncomeEntryIdAndDeletedAtIsNullOrderByCreatedAtAsc(INCOME))
                .thenReturn(List.of());
        when(receiptDeductions.findAllForUpdateByIncomeReceiptId(any(UUID.class))).thenReturn(List.of());
        when(receiptDeductions.findAllByIncomeReceiptIdAndDeletedAtIsNullOrderByCreatedAtAsc(any(UUID.class)))
                .thenReturn(List.of());
        when(receiptDeductions.findAllByIncomeReceiptIdOrderByCreatedAtAsc(any(UUID.class)))
                .thenReturn(List.of());
        when(receiptDeductions.findAllByIncomeReceiptIdInAndDeletedAtIsNull(any())).thenReturn(List.of());
        when(receiptDeductions.findAllByIncomeReceiptIdIn(any())).thenReturn(List.of());
    }

    @Test
    void editAndSoftDeleteRecalculateStatusWhileHistoryRetainsEveryReceipt() {
        var first = service.createReceipt(INCOME, ACTOR, receipt(FIRST_RECEIPT, "1000.00"));
        var second = service.createReceipt(INCOME, ACTOR, receipt(SECOND_RECEIPT, "4000.00"));
        assertThat(service.incomeAvailability(INCOME, ACTOR).status()).isEqualTo(IncomeStatus.RECEIVED);

        var edited = service.updateReceipt(SECOND_RECEIPT, ACTOR,
                new IncomeReceiptUpdateRequest(new BigDecimal("2000.00"), NOW, TIME_ZONE,
                        "Corrected salary tranche", second.version(), List.of()));
        assertThat(edited.amount()).isEqualByComparingTo("2000.00");
        assertThat(service.incomeAvailability(INCOME, ACTOR).status())
                .isEqualTo(IncomeStatus.PARTIALLY_RECEIVED);

        service.deleteReceipt(SECOND_RECEIPT, ACTOR, new IncomeStateRequest(edited.version()));
        assertThat(service.incomeAvailability(INCOME, ACTOR).status())
                .isEqualTo(IncomeStatus.PARTIALLY_RECEIVED);
        assertThat(service.receipts(INCOME, ACTOR)).singleElement()
                .satisfies(value -> assertThat(value.id()).isEqualTo(FIRST_RECEIPT));
        assertThat(service.receiptHistory(INCOME, ACTOR)).hasSize(2)
                .anySatisfy(value -> {
                    assertThat(value.id()).isEqualTo(SECOND_RECEIPT);
                    assertThat(value.deletedAt()).isEqualTo(NOW);
                    assertThat(value.amount()).isEqualByComparingTo("2000.00");
                });

        service.deleteReceipt(FIRST_RECEIPT, ACTOR, new IncomeStateRequest(first.version()));
        assertThat(service.incomeAvailability(INCOME, ACTOR).status()).isEqualTo(IncomeStatus.SCHEDULED);
        assertThat(service.receipts(INCOME, ACTOR)).isEmpty();
        assertThat(service.receiptHistory(INCOME, ACTOR)).hasSize(2)
                .allSatisfy(value -> assertThat(value.deletedAt()).isEqualTo(NOW));
    }

    private IncomeReceiptRequest receipt(UUID id, String amount) {
        return new IncomeReceiptRequest(id, INCOME, new BigDecimal(amount), NOW, TIME_ZONE,
                "Salary tranche", List.of());
    }

    private Optional<IncomeReceipt> activeReceipt(UUID id) {
        return receiptLedger.stream()
                .filter(value -> value.getId().equals(id) && value.getDeletedAt() == null)
                .findFirst();
    }
}
