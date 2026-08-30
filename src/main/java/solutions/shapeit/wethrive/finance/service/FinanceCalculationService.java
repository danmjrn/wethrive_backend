package solutions.shapeit.wethrive.finance.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetItemType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.DeductionType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.FundingStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.IncomeStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.TransactionType;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetFundingSummary;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetItemResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetSummary;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.FundingAllocationResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.FundingSummary;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeAvailability;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeDeductionResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptDeductionResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.ItemCalculation;
import solutions.shapeit.wethrive.finance.entity.BudgetFundingAllocation;
import solutions.shapeit.wethrive.finance.entity.BudgetItem;
import solutions.shapeit.wethrive.finance.entity.IncomeEntry;
import solutions.shapeit.wethrive.finance.entity.IncomeDeduction;
import solutions.shapeit.wethrive.finance.entity.IncomeReceipt;
import solutions.shapeit.wethrive.finance.entity.IncomeReceiptDeduction;
import solutions.shapeit.wethrive.finance.entity.SpendingEntry;

/** Canonical finance arithmetic shared by API, reports, exports, and tests. */
@Service
public class FinanceCalculationService {
    public static final BigDecimal ZERO = new BigDecimal("0.00");

    public IncomeResponse income(IncomeEntry entry, List<IncomeReceipt> receipts,
                                 List<BudgetFundingAllocation> allocations, LocalDate today) {
        // Compatibility entry point for V2 callers. It translates legacy Tithe fields into the
        // same generic deduction inputs used everywhere else; it is not a competing formula path.
        List<IncomeDeduction> planned = legacyPlannedDeduction(entry);
        List<IncomeReceiptDeduction> actuals = legacyReceiptDeductions(entry, receipts, planned);
        return income(entry, receipts, planned, actuals, allocations, today);
    }

    /** Canonical calculation backed by planned and reviewed receipt-level deduction ledgers. */
    public IncomeResponse income(IncomeEntry entry, List<IncomeReceipt> receipts,
                                 List<IncomeDeduction> deductions,
                                 List<IncomeReceiptDeduction> receiptDeductions,
                                 List<BudgetFundingAllocation> allocations, LocalDate today) {
        List<IncomeReceipt> activeReceipts = receipts.stream().filter(value -> value.getDeletedAt() == null).toList();
        java.util.Set<UUID> activeReceiptIds = activeReceipts.stream().map(IncomeReceipt::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<IncomeDeduction> activeDeductions = deductions.stream()
                .filter(value -> value.getDeletedAt() == null).toList();
        List<IncomeReceiptDeduction> activeActuals = receiptDeductions.stream()
                .filter(value -> value.getDeletedAt() == null && activeReceiptIds.contains(value.getIncomeReceiptId()))
                .toList();
        BigDecimal receivedGross = sum(activeReceipts.stream().map(IncomeReceipt::getAmount).toList());
        BigDecimal configuredGross = money(entry.getExpectedAmount());
        BigDecimal configuredDeductions = sum(activeDeductions.stream()
                .map(value -> projectedAmount(value, configuredGross)).toList());
        boolean cancelled = entry.getCancelledAt() != null;
        BigDecimal projectedGross = cancelled ? ZERO : configuredGross;
        BigDecimal projectedDeductions = cancelled ? ZERO : configuredDeductions;
        BigDecimal projectedNet = maxZero(projectedGross.subtract(projectedDeductions));
        BigDecimal realizedDeductions = sum(activeActuals.stream()
                .map(IncomeReceiptDeduction::getActualAmount).toList());
        BigDecimal receivedNet = maxZero(receivedGross.subtract(realizedDeductions));
        BigDecimal confirmed = sum(allocations.stream().filter(value -> value.getDeletedAt() == null)
                .map(BudgetFundingAllocation::getConfirmedAllocatedAmount).toList());
        BigDecimal unallocated = maxZero(receivedNet.subtract(confirmed));
        BigDecimal remainingExpected = cancelled ? ZERO : maxZero(projectedNet.subtract(receivedNet));
        IncomeAvailability availability = new IncomeAvailability(projectedGross, projectedDeductions, projectedNet,
                receivedGross, realizedDeductions, receivedNet, confirmed, unallocated, remainingExpected,
                incomeStatus(entry, receivedGross, today));
        List<IncomeDeductionResponse> deductionResponses = activeDeductions.stream()
                .sorted(Comparator.comparingInt(IncomeDeduction::getSortOrder)
                        .thenComparing(IncomeDeduction::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(value -> deduction(value, configuredGross)).toList();
        return new IncomeResponse(entry.getId(), entry.getBudgetMonthId(), entry.getSpaceId(), entry.getSourceName(),
                entry.getIncomeTypeId(), configuredGross, entry.getExpectedDate(), entry.getExpectedTime(),
                entry.getTimeZone(), entry.isTitheEnabled(), entry.getTitheRate(), nullableMoney(entry.getTitheAmountOverride()),
                entry.isRecurring(), entry.getRecurrenceRule(), entry.getCancelledAt(), entry.getNotes(), entry.getSortOrder(),
                entry.getVersion(), availability, deductionResponses);
    }

    public IncomeResponse income(IncomeEntry entry) {
        return income(entry, List.of(), List.of(), LocalDate.now());
    }

    public IncomeReceiptResponse receipt(IncomeReceipt receipt) {
        return receipt(receipt, List.of());
    }

    public IncomeReceiptResponse receipt(IncomeReceipt receipt, List<IncomeReceiptDeduction> deductions) {
        List<IncomeReceiptDeductionResponse> lines = deductions.stream()
                .sorted(Comparator.comparing(IncomeReceiptDeduction::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::receiptDeduction).toList();
        BigDecimal gross = money(receipt.getAmount());
        BigDecimal totalDeductions = sum(deductions.stream().filter(value -> value.getDeletedAt() == null
                        || receipt.getDeletedAt() != null
                        && java.util.Objects.equals(value.getDeletedAt(), receipt.getDeletedAt()))
                .map(IncomeReceiptDeduction::getActualAmount).toList());
        return new IncomeReceiptResponse(receipt.getId(), receipt.getIncomeEntryId(), receipt.getSpaceId(), money(receipt.getAmount()),
                receipt.getReceivedAt(), receipt.getTimeZone(), receipt.getNotes(), receipt.getRecordedByUserId(),
                receipt.getCreatedByUserId(), receipt.getUpdatedByUserId(), receipt.getCreatedAt(), receipt.getUpdatedAt(),
                receipt.getDeletedAt(), receipt.getVersion(), lines, totalDeductions,
                maxZero(gross.subtract(totalDeductions)));
    }

    public IncomeDeductionResponse deduction(IncomeDeduction deduction, BigDecimal expectedGross) {
        return new IncomeDeductionResponse(deduction.getId(), deduction.getIncomeEntryId(), deduction.getSpaceId(),
                deduction.getName(), deduction.getDeductionType(), deduction.getPercentageRate(),
                nullableMoney(deduction.getFixedAmount()), projectedAmount(deduction, expectedGross),
                deduction.getNotes(), deduction.getSortOrder(), deduction.isLegacyTithe(),
                deduction.getCreatedByUserId(), deduction.getUpdatedByUserId(), deduction.getCreatedAt(),
                deduction.getUpdatedAt(), deduction.getDeletedAt(), deduction.getVersion());
    }

    public IncomeReceiptDeductionResponse receiptDeduction(IncomeReceiptDeduction deduction) {
        return new IncomeReceiptDeductionResponse(deduction.getId(), deduction.getIncomeReceiptId(),
                deduction.getIncomeDeductionId(), deduction.getSpaceId(), deduction.getNameSnapshot(),
                money(deduction.getActualAmount()), deduction.getCreatedByUserId(), deduction.getUpdatedByUserId(),
                deduction.getCreatedAt(), deduction.getUpdatedAt(), deduction.getDeletedAt(), deduction.getVersion());
    }

    public BigDecimal projectedAmount(IncomeDeduction deduction, BigDecimal expectedGross) {
        return deduction.getDeductionType() == DeductionType.PERCENTAGE
                ? money(money(expectedGross).multiply(deduction.getPercentageRate()))
                : money(deduction.getFixedAmount());
    }

    public FundingAllocationResponse allocation(BudgetFundingAllocation allocation) {
        return new FundingAllocationResponse(allocation.getId(), allocation.getBudgetItemId(), allocation.getIncomeEntryId(),
                allocation.getSpaceId(), allocation.getSourceType(), money(allocation.getPlannedAmount()),
                money(allocation.getConfirmedAllocatedAmount()), allocation.getAllocatedAt(), allocation.getTimeZone(),
                allocation.getNotes(), allocation.getCreatedByUserId(), allocation.getUpdatedByUserId(), allocation.getCreatedAt(),
                allocation.getUpdatedAt(), allocation.getDeletedAt(), allocation.getVersion());
    }

    public FundingSummary funding(BudgetItem item, List<SpendingEntry> entries,
                                  List<BudgetFundingAllocation> allocations) {
        BigDecimal plannedBudget = money(item.getPlannedAmount());
        BigDecimal plannedFunding = sum(allocations.stream().map(BudgetFundingAllocation::getPlannedAmount).toList());
        BigDecimal confirmed = sum(allocations.stream().map(BudgetFundingAllocation::getConfirmedAllocatedAmount).toList());
        BigDecimal netSpending = netSpending(entries);
        FundingStatus status = fundingStatus(plannedBudget, plannedFunding, confirmed);
        return new FundingSummary(item.getId(), plannedBudget, plannedFunding, confirmed,
                maxZero(plannedBudget.subtract(confirmed)), maxZero(confirmed.subtract(netSpending)),
                maxZero(netSpending.subtract(confirmed)), status,
                allocations.stream().map(this::allocation).toList());
    }

    public BudgetItemResponse item(BudgetItem item, List<SpendingEntry> entries,
                                   List<BudgetFundingAllocation> allocations) {
        BigDecimal gross = sum(entries.stream().filter(e -> e.getTransactionType() == TransactionType.EXPENSE)
                .map(SpendingEntry::getAmount).toList());
        BigDecimal refunds = sum(entries.stream().filter(e -> e.getTransactionType() == TransactionType.REFUND)
                .map(SpendingEntry::getAmount).toList());
        BigDecimal net = money(gross.subtract(refunds));
        BigDecimal planned = money(item.getPlannedAmount());
        BigDecimal remaining = money(planned.subtract(net));
        BigDecimal usage = planned.signum() > 0 ? percentage(net, planned) : ZERO;
        FundingSummary funding = funding(item, entries, allocations);
        ItemCalculation calculation = new ItemCalculation(gross, refunds, net, remaining, usage,
                planned.signum() == 0 ? "Unbudgeted" : status(usage), entries.size(), funding.plannedFunding(),
                funding.confirmedAllocatedAmount(), funding.fundingGap(), funding.unspentAllocatedFunds(),
                funding.unfundedSpending(), funding.status());
        return new BudgetItemResponse(item.getId(), item.getBudgetMonthId(), item.getSpaceId(), item.getName(),
                item.getCategoryId(), planned, item.isTracked(), item.getItemType(), item.isRecurring(),
                item.isRolloverEnabled(), item.getNotes(), item.getSortOrder(), item.getVersion(), calculation, funding);
    }

    public BudgetItemResponse item(BudgetItem item, List<SpendingEntry> entries) {
        return item(item, entries, List.of());
    }

    public BudgetSummary budget(List<IncomeEntry> incomeEntries, List<BudgetItem> budgetItems,
                                List<SpendingEntry> spendingEntries, List<IncomeReceipt> receipts,
                                List<BudgetFundingAllocation> allocations, YearMonth period, LocalDate today) {
        return budget(incomeEntries, budgetItems, spendingEntries, receipts, allocations, period, today,
                ignored -> today);
    }

    public BudgetSummary budget(List<IncomeEntry> incomeEntries, List<BudgetItem> budgetItems,
                                List<SpendingEntry> spendingEntries, List<IncomeReceipt> receipts,
                                List<BudgetFundingAllocation> allocations, YearMonth period, LocalDate budgetToday,
                                Function<IncomeEntry, LocalDate> todayByIncome) {
        return budget(incomeEntries, budgetItems, spendingEntries, receipts, null, null, allocations,
                period, budgetToday, todayByIncome);
    }

    public BudgetSummary budget(List<IncomeEntry> incomeEntries, List<BudgetItem> budgetItems,
                                List<SpendingEntry> spendingEntries, List<IncomeReceipt> receipts,
                                List<IncomeDeduction> deductions,
                                List<IncomeReceiptDeduction> receiptDeductions,
                                List<BudgetFundingAllocation> allocations, YearMonth period, LocalDate budgetToday,
                                Function<IncomeEntry, LocalDate> todayByIncome) {
        Map<UUID, List<IncomeReceipt>> receiptsByIncome = receipts.stream()
                .collect(Collectors.groupingBy(IncomeReceipt::getIncomeEntryId));
        Map<UUID, List<BudgetFundingAllocation>> allocationsByIncome = allocations.stream()
                .filter(value -> value.getIncomeEntryId() != null)
                .collect(Collectors.groupingBy(BudgetFundingAllocation::getIncomeEntryId));
        Map<UUID, List<IncomeDeduction>> deductionsByIncome = deductions == null ? Map.of()
                : deductions.stream().collect(Collectors.groupingBy(IncomeDeduction::getIncomeEntryId));
        Map<UUID, List<IncomeReceiptDeduction>> deductionsByReceipt = receiptDeductions == null ? Map.of()
                : receiptDeductions.stream().collect(Collectors.groupingBy(IncomeReceiptDeduction::getIncomeReceiptId));
        List<IncomeResponse> incomes = incomeEntries.stream().map(entry -> {
            List<IncomeReceipt> entryReceipts = receiptsByIncome.getOrDefault(entry.getId(), List.of());
            if (deductions == null || receiptDeductions == null) {
                return income(entry, entryReceipts, allocationsByIncome.getOrDefault(entry.getId(), List.of()),
                        todayByIncome.apply(entry));
            }
            List<IncomeReceiptDeduction> actuals = entryReceipts.stream()
                    .flatMap(receipt -> deductionsByReceipt.getOrDefault(receipt.getId(), List.of()).stream()).toList();
            return income(entry, entryReceipts, deductionsByIncome.getOrDefault(entry.getId(), List.of()), actuals,
                    allocationsByIncome.getOrDefault(entry.getId(), List.of()), todayByIncome.apply(entry));
        }).toList();
        Map<UUID, List<SpendingEntry>> spendingByItem = spendingEntries.stream()
                .collect(Collectors.groupingBy(SpendingEntry::getBudgetItemId));
        Map<UUID, List<BudgetFundingAllocation>> allocationsByItem = allocations.stream()
                .collect(Collectors.groupingBy(BudgetFundingAllocation::getBudgetItemId));
        List<BudgetItemResponse> items = budgetItems.stream()
                .sorted(Comparator.comparingInt(BudgetItem::getSortOrder).thenComparing(BudgetItem::getName))
                .map(item -> item(item, spendingByItem.getOrDefault(item.getId(), List.of()),
                        allocationsByItem.getOrDefault(item.getId(), List.of())))
                .toList();
        BigDecimal projectedGross = sum(incomes.stream().map(value -> value.availability().projectedGrossIncome()).toList());
        BigDecimal projectedDeductions = sum(incomes.stream().map(value -> value.availability().projectedDeductions()).toList());
        BigDecimal projectedNet = sum(incomes.stream().map(value -> value.availability().projectedNetIncome()).toList());
        BigDecimal receivedGross = sum(incomes.stream().map(value -> value.availability().receivedGrossIncome()).toList());
        BigDecimal realizedDeductions = sum(incomes.stream().map(value -> value.availability().realizedDeductions()).toList());
        BigDecimal receivedNet = sum(incomes.stream().map(value -> value.availability().receivedNetIncome()).toList());
        BigDecimal linkedConfirmed = sum(incomes.stream().map(value -> value.availability().confirmedAllocatedAmount()).toList());
        BigDecimal unallocatedReceived = sum(incomes.stream().map(value -> value.availability().unallocatedReceivedIncome()).toList());
        BigDecimal remainingExpected = sum(incomes.stream().map(value -> value.availability().remainingExpectedIncome()).toList());
        BigDecimal planned = sum(items.stream().map(BudgetItemResponse::plannedAmount).toList());
        BigDecimal confirmedFunded = sum(items.stream().map(value -> value.funding().confirmedAllocatedAmount()).toList());
        BigDecimal fundingGap = sum(items.stream().map(value -> value.funding().fundingGap()).toList());
        BigDecimal gross = sum(items.stream().map(value -> value.calculation().grossSpending()).toList());
        BigDecimal refunds = sum(items.stream().map(value -> value.calculation().refunds()).toList());
        BigDecimal net = money(gross.subtract(refunds));
        BigDecimal unbudgeted = sum(items.stream().filter(value -> value.itemType() == BudgetItemType.UNBUDGETED
                        || value.plannedAmount().signum() == 0).map(value -> value.calculation().netSpending()).toList());
        // Allocations reserve income for their selected item but never alter monthly cash or spending totals.
        BigDecimal remainingIncome = money(receivedNet.subtract(net));
        BigDecimal spendingRate = receivedNet.signum() > 0 ? percentage(net, receivedNet) : ZERO;
        BigDecimal budgetUsage = planned.signum() > 0 ? percentage(net, planned) : ZERO;
        BigDecimal unfundedSpending = sum(items.stream().map(value -> value.funding().unfundedSpending()).toList());
        // Confirmed income-linked allocations are reserved cash. Spending beyond confirmed funding
        // consumes the unallocated balance; spending covered by an allocation does not consume it twice.
        BigDecimal safeUnallocatedCash = maxZero(unallocatedReceived.subtract(unfundedSpending));
        int fullyFunded = (int) items.stream().filter(value -> value.funding().status() == FundingStatus.FULLY_FUNDED
                || value.funding().status() == FundingStatus.OVERFUNDED).count();
        int partiallyFunded = (int) items.stream().filter(value -> value.funding().status() == FundingStatus.PARTIALLY_FUNDED).count();
        int unfunded = (int) items.stream().filter(value -> value.funding().status() == FundingStatus.NOT_FUNDED
                || value.funding().status() == FundingStatus.FUNDING_SCHEDULED).count();
        return new BudgetSummary(projectedGross, projectedNet, receivedGross, receivedNet, projectedDeductions,
                realizedDeductions, linkedConfirmed, unallocatedReceived, remainingExpected, planned, confirmedFunded,
                fundingGap, gross, refunds, net, unbudgeted, remainingIncome, spendingRate,
                safeToSpend(safeUnallocatedCash, period, budgetToday), forecast(net, period, budgetToday), fullyFunded,
                partiallyFunded, unfunded, planned.signum() == 0 ? "No planned budget" : status(budgetUsage), items);
    }

    public BudgetFundingSummary budgetFunding(UUID budgetId, List<BudgetItemResponse> items) {
        return new BudgetFundingSummary(budgetId, sum(items.stream().map(BudgetItemResponse::plannedAmount).toList()),
                sum(items.stream().map(value -> value.funding().plannedFunding()).toList()),
                sum(items.stream().map(value -> value.funding().confirmedAllocatedAmount()).toList()),
                sum(items.stream().map(value -> value.funding().fundingGap()).toList()),
                (int) items.stream().filter(value -> value.funding().status() == FundingStatus.FULLY_FUNDED
                        || value.funding().status() == FundingStatus.OVERFUNDED).count(),
                (int) items.stream().filter(value -> value.funding().status() == FundingStatus.PARTIALLY_FUNDED).count(),
                (int) items.stream().filter(value -> value.funding().status() == FundingStatus.NOT_FUNDED
                        || value.funding().status() == FundingStatus.FUNDING_SCHEDULED).count(),
                items.stream().map(BudgetItemResponse::funding).toList());
    }

    public BigDecimal money(BigDecimal value) { return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_EVEN); }
    public BigDecimal nullableMoney(BigDecimal value) { return value == null ? null : money(value); }
    public BigDecimal percentage(BigDecimal numerator, BigDecimal denominator) {
        return denominator == null || denominator.signum() == 0 ? ZERO
                : numerator.multiply(new BigDecimal("100")).divide(denominator, 2, RoundingMode.HALF_EVEN);
    }
    public String status(BigDecimal percentage) {
        if (percentage.compareTo(new BigDecimal("100")) > 0) return "Overspent";
        if (percentage.compareTo(new BigDecimal("100")) == 0) return "Fully used";
        if (percentage.compareTo(new BigDecimal("90")) >= 0) return "Almost reached";
        if (percentage.compareTo(new BigDecimal("75")) >= 0) return "Watch closely";
        if (percentage.compareTo(new BigDecimal("50")) >= 0) return "On track";
        return "Healthy";
    }


    private List<IncomeDeduction> legacyPlannedDeduction(IncomeEntry entry) {
        if (!entry.isTitheEnabled()) return List.of();
        IncomeDeduction deduction = new IncomeDeduction();
        deduction.setId(entry.getId()); deduction.setIncomeEntryId(entry.getId()); deduction.setSpaceId(entry.getSpaceId());
        deduction.setName("Tithe"); deduction.setSortOrder(0); deduction.setLegacyTithe(true);
        if (entry.getTitheAmountOverride() == null) {
            deduction.setDeductionType(DeductionType.PERCENTAGE); deduction.setPercentageRate(defaultRate(entry));
        } else {
            deduction.setDeductionType(DeductionType.FIXED); deduction.setFixedAmount(money(entry.getTitheAmountOverride()));
        }
        return List.of(deduction);
    }

    /** Deterministic cumulative proposal retained only to translate V2 receipt calculations. */
    private List<IncomeReceiptDeduction> legacyReceiptDeductions(IncomeEntry entry, List<IncomeReceipt> receipts,
                                                                 List<IncomeDeduction> planned) {
        if (planned.isEmpty()) return List.of();
        IncomeDeduction deduction = planned.getFirst();
        List<IncomeReceipt> ordered = receipts.stream().filter(value -> value.getDeletedAt() == null)
                .sorted(Comparator.comparing(IncomeReceipt::getReceivedAt)
                        .thenComparing(IncomeReceipt::getId)).toList();
        List<IncomeReceiptDeduction> result = new java.util.ArrayList<>();
        BigDecimal runningGross = ZERO; BigDecimal previousCumulative = ZERO;
        BigDecimal expectedGross = money(entry.getExpectedAmount());
        for (IncomeReceipt receipt : ordered) {
            runningGross = money(runningGross.add(receipt.getAmount()));
            BigDecimal cumulative;
            if (deduction.getDeductionType() == DeductionType.PERCENTAGE) {
                cumulative = money(runningGross.multiply(deduction.getPercentageRate()));
            } else if (expectedGross.signum() <= 0) {
                cumulative = money(runningGross.min(deduction.getFixedAmount()));
            } else {
                cumulative = money(deduction.getFixedAmount().multiply(runningGross)
                        .divide(expectedGross, 8, RoundingMode.HALF_EVEN).min(deduction.getFixedAmount()));
            }
            IncomeReceiptDeduction actual = new IncomeReceiptDeduction();
            actual.setId(receipt.getId()); actual.setIncomeReceiptId(receipt.getId());
            actual.setIncomeDeductionId(deduction.getId()); actual.setSpaceId(entry.getSpaceId());
            actual.setNameSnapshot("Tithe"); actual.setActualAmount(money(cumulative.subtract(previousCumulative)));
            result.add(actual); previousCumulative = cumulative;
        }
        return result;
    }

    private BigDecimal defaultRate(IncomeEntry entry) {
        return entry.getTitheRate() == null ? new BigDecimal("0.1000") : entry.getTitheRate();
    }

    private IncomeStatus incomeStatus(IncomeEntry entry, BigDecimal receivedGross, LocalDate today) {
        if (entry.getCancelledAt() != null) return IncomeStatus.CANCELLED;
        BigDecimal expected = money(entry.getExpectedAmount());
        if (receivedGross.signum() > 0 && (expected.signum() == 0 || receivedGross.compareTo(expected) >= 0)) return IncomeStatus.RECEIVED;
        if (receivedGross.signum() > 0) return IncomeStatus.PARTIALLY_RECEIVED;
        if (entry.getExpectedDate() == null || entry.getExpectedDate().isAfter(today)) return IncomeStatus.SCHEDULED;
        if (entry.getExpectedDate().isEqual(today)) return IncomeStatus.DUE;
        return IncomeStatus.LATE;
    }

    private FundingStatus fundingStatus(BigDecimal plannedBudget, BigDecimal plannedFunding, BigDecimal confirmed) {
        if (plannedBudget.signum() == 0 && confirmed.signum() == 0) return FundingStatus.NOT_APPLICABLE;
        if (confirmed.compareTo(plannedBudget) > 0) return FundingStatus.OVERFUNDED;
        if (confirmed.signum() > 0 && confirmed.compareTo(plannedBudget) >= 0) return FundingStatus.FULLY_FUNDED;
        if (confirmed.signum() > 0) return FundingStatus.PARTIALLY_FUNDED;
        if (plannedFunding.signum() > 0) return FundingStatus.FUNDING_SCHEDULED;
        return FundingStatus.NOT_FUNDED;
    }

    private BigDecimal sum(List<BigDecimal> values) {
        return money(values.stream().filter(java.util.Objects::nonNull).reduce(ZERO, BigDecimal::add));
    }
    private BigDecimal maxZero(BigDecimal value) { return money(value.max(BigDecimal.ZERO)); }
    private BigDecimal netSpending(List<SpendingEntry> entries) {
        return money(sum(entries.stream().filter(value -> value.getTransactionType() == TransactionType.EXPENSE)
                .map(SpendingEntry::getAmount).toList()).subtract(sum(entries.stream()
                .filter(value -> value.getTransactionType() == TransactionType.REFUND).map(SpendingEntry::getAmount).toList())));
    }
    private BigDecimal safeToSpend(BigDecimal remaining, YearMonth period, LocalDate today) {
        if (remaining.signum() <= 0 || YearMonth.from(today).isAfter(period)) return ZERO;
        int days = YearMonth.from(today).equals(period) ? period.lengthOfMonth() - today.getDayOfMonth() + 1 : period.lengthOfMonth();
        return remaining.divide(BigDecimal.valueOf(days), 2, RoundingMode.DOWN).max(ZERO);
    }
    private BigDecimal forecast(BigDecimal net, YearMonth period, LocalDate today) {
        if (!YearMonth.from(today).equals(period) || net.signum() <= 0) return net.max(ZERO);
        return money(net.divide(BigDecimal.valueOf(today.getDayOfMonth()), 8, RoundingMode.HALF_EVEN)
                .multiply(BigDecimal.valueOf(period.lengthOfMonth()))).max(ZERO);
    }
}
