package solutions.shapeit.wethrive.report.service;

import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import solutions.shapeit.wethrive.common.domain.DomainEnums.IncomeStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.TransactionType;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetItemResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetSummary;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.SpendingResponse;
import solutions.shapeit.wethrive.finance.entity.BudgetItem;
import solutions.shapeit.wethrive.finance.entity.BudgetMonth;
import solutions.shapeit.wethrive.finance.entity.IncomeEntry;
import solutions.shapeit.wethrive.finance.entity.IncomeDeduction;
import solutions.shapeit.wethrive.finance.entity.IncomeReceipt;
import solutions.shapeit.wethrive.finance.entity.IncomeReceiptDeduction;
import solutions.shapeit.wethrive.finance.entity.BudgetFundingAllocation;
import solutions.shapeit.wethrive.finance.entity.SpendingEntry;
import solutions.shapeit.wethrive.finance.repository.BudgetItemRepository;
import solutions.shapeit.wethrive.finance.repository.BudgetMonthRepository;
import solutions.shapeit.wethrive.finance.repository.CategoryRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeEntryRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeDeductionRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptDeductionRepository;
import solutions.shapeit.wethrive.finance.repository.BudgetFundingAllocationRepository;
import solutions.shapeit.wethrive.finance.repository.SpendingEntryRepository;
import solutions.shapeit.wethrive.finance.service.FinanceCalculationService;
import solutions.shapeit.wethrive.identity.repository.AppUserRepository;
import solutions.shapeit.wethrive.report.dto.ReportDtos.AnnualReport;
import solutions.shapeit.wethrive.report.dto.ReportDtos.DailyAmount;
import solutions.shapeit.wethrive.report.dto.ReportDtos.DailyCashFlow;
import solutions.shapeit.wethrive.report.dto.ReportDtos.IncomeBudgetMapping;
import solutions.shapeit.wethrive.report.dto.ReportDtos.IncomeScheduleRow;
import solutions.shapeit.wethrive.report.dto.ReportDtos.IncomeStatusCounts;
import solutions.shapeit.wethrive.report.dto.ReportDtos.MonthlyReport;
import solutions.shapeit.wethrive.report.dto.ReportDtos.NamedAmount;
import solutions.shapeit.wethrive.report.dto.ReportDtos.PeriodRow;
import solutions.shapeit.wethrive.report.dto.ReportDtos.PeriodTotals;
import solutions.shapeit.wethrive.report.dto.ReportDtos.PlannedDeductionRow;
import solutions.shapeit.wethrive.report.dto.ReportDtos.QuarterlyReport;
import solutions.shapeit.wethrive.report.dto.ReportDtos.RealizedDeductionRow;
import solutions.shapeit.wethrive.report.dto.ReportDtos.ReportChartData;
import solutions.shapeit.wethrive.report.dto.ReportDtos.ReportFilter;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;
import solutions.shapeit.wethrive.space.service.SpaceAccessService.Capability;
import solutions.shapeit.wethrive.space.repository.SpaceRepository;

@Service
public class ReportService {
    private final BudgetMonthRepository budgets;
    private final IncomeEntryRepository income;
    private final IncomeDeductionRepository deductions;
    private final IncomeReceiptRepository receipts;
    private final IncomeReceiptDeductionRepository receiptDeductions;
    private final BudgetItemRepository items;
    private final BudgetFundingAllocationRepository allocations;
    private final SpendingEntryRepository spending;
    private final CategoryRepository categories;
    private final AppUserRepository users;
    private final FinanceCalculationService calculations;
    private final SpaceAccessService access;
    private final SpaceRepository spaces;
    private final Clock clock;

    public ReportService(BudgetMonthRepository budgets, IncomeEntryRepository income, IncomeReceiptRepository receipts,
                         IncomeDeductionRepository deductions,
                         IncomeReceiptDeductionRepository receiptDeductions,
                         BudgetItemRepository items, BudgetFundingAllocationRepository allocations,
                         SpendingEntryRepository spending, CategoryRepository categories, AppUserRepository users,
                         FinanceCalculationService calculations, SpaceAccessService access,
                         SpaceRepository spaces, Clock clock) {
        this.budgets = budgets; this.income = income; this.receipts = receipts;
        this.deductions = deductions; this.receiptDeductions = receiptDeductions;
        this.items = items; this.allocations = allocations; this.spending = spending;
        this.categories = categories; this.users = users; this.calculations = calculations; this.access = access;
        this.spaces = spaces; this.clock = clock;
    }

    @Transactional
    public MonthlyReport monthly(UUID spaceId, int year, int month, UUID actorId) {
        return monthly(spaceId, year, month, actorId, ReportFilter.unfiltered());
    }

    @Transactional
    public MonthlyReport monthly(UUID spaceId, int year, int month, UUID actorId, ReportFilter requestedFilter) {
        ReportFilter filter = requestedFilter == null ? ReportFilter.unfiltered() : requestedFilter;
        access.require(spaceId, actorId, Capability.VIEW);
        BudgetMonth budget = budgets.findBySpaceIdAndYearAndMonthAndDeletedAtIsNull(spaceId, year, month).orElse(null);
        if (budget != null && !filter.includesBudget(budget.getId())) budget = null;
        var categoryValues = categories.findAllBySpaceIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(spaceId);
        UUID selectedCategoryId = filter.category() == null ? null : categoryValues.stream()
                .filter(value -> value.getName().equals(filter.category()))
                .map(value -> value.getId()).findFirst().orElse(null);
        List<IncomeEntry> incomeEntries = budget == null ? List.of() : income.findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(budget.getId());
        List<BudgetItem> allBudgetItems = budget == null ? List.of()
                : items.findAllByBudgetMonthIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(budget.getId());
        List<BudgetItem> budgetItems = allBudgetItems.stream()
                        .filter(value -> filter.category() == null
                                || selectedCategoryId != null && selectedCategoryId.equals(value.getCategoryId()))
                        .toList();
        List<SpendingEntry> entries = budgetItems.isEmpty() ? List.of()
                : spending.findAllByBudgetItemIdInAndDeletedAtIsNull(
                                budgetItems.stream().map(BudgetItem::getId).toList()).stream()
                        .filter(value -> filter.includesDate(value.getUserSelectedDate())).toList();
        List<IncomeReceipt> receiptEntries = incomeEntries.isEmpty() ? List.of() : receipts.findAllByIncomeEntryIdInAndDeletedAtIsNull(incomeEntries.stream().map(IncomeEntry::getId).toList());
        List<IncomeDeduction> deductionEntries = incomeEntries.isEmpty() ? List.of()
                : deductions.findAllByIncomeEntryIdInAndDeletedAtIsNull(incomeEntries.stream().map(IncomeEntry::getId).toList());
        List<IncomeReceiptDeduction> actualDeductionEntries = receiptEntries.isEmpty() ? List.of()
                : receiptDeductions.findAllByIncomeReceiptIdInAndDeletedAtIsNull(
                        receiptEntries.stream().map(IncomeReceipt::getId).toList());
        List<BudgetFundingAllocation> allFundingEntries = allBudgetItems.isEmpty() ? List.of()
                : allocations.findAllByBudgetItemIdInAndDeletedAtIsNull(
                        allBudgetItems.stream().map(BudgetItem::getId).toList());
        List<BudgetFundingAllocation> fundingHistory = budgetItems.isEmpty() ? List.of()
                : allocations.findAllByBudgetItemIdInOrderByCreatedAtAsc(
                        budgetItems.stream().map(BudgetItem::getId).toList());
        ZoneId zone = spaces.findByIdAndDeletedAtIsNull(spaceId).map(space -> ZoneId.of(space.getTimeZone()))
                .orElse(ZoneId.of("UTC"));
        LocalDate today = LocalDate.now(clock.withZone(zone));
        // Category filters narrow item/funding views, but income availability is
        // a whole-budget invariant. Include allocations to excluded categories
        // so "available to allocate" cannot pretend already-reserved cash is free.
        BudgetSummary totals = calculations.budget(incomeEntries, budgetItems, entries, receiptEntries,
                deductionEntries, actualDeductionEntries, allFundingEntries,
                YearMonth.of(year, month), today, this::todayForIncome);
        Map<UUID, List<IncomeReceipt>> receiptsByIncome = receiptEntries.stream()
                .collect(Collectors.groupingBy(IncomeReceipt::getIncomeEntryId));
        Map<UUID, List<IncomeDeduction>> deductionsByIncome = deductionEntries.stream()
                .collect(Collectors.groupingBy(IncomeDeduction::getIncomeEntryId));
        Map<UUID, List<IncomeReceiptDeduction>> deductionsByReceipt = actualDeductionEntries.stream()
                .collect(Collectors.groupingBy(IncomeReceiptDeduction::getIncomeReceiptId));
        Map<UUID, List<BudgetFundingAllocation>> fundingByIncome = allFundingEntries.stream()
                .filter(value -> value.getIncomeEntryId() != null)
                .collect(Collectors.groupingBy(BudgetFundingAllocation::getIncomeEntryId));
        Map<UUID, IncomeResponse> calculatedIncome = incomeEntries.stream().collect(Collectors.toMap(
                IncomeEntry::getId,
                entry -> {
                    List<IncomeReceipt> entryReceipts = receiptsByIncome.getOrDefault(entry.getId(), List.of());
                    List<IncomeReceiptDeduction> actuals = entryReceipts.stream()
                            .flatMap(receipt -> deductionsByReceipt.getOrDefault(receipt.getId(), List.of()).stream()).toList();
                    return calculations.income(entry, entryReceipts,
                            deductionsByIncome.getOrDefault(entry.getId(), List.of()), actuals,
                            fundingByIncome.getOrDefault(entry.getId(), List.of()), todayForIncome(entry));
                },
                (left, right) -> left,
                LinkedHashMap::new));
        List<IncomeScheduleRow> incomeSchedule = incomeEntries.stream()
                .map(entry -> schedule(entry, calculatedIncome.get(entry.getId()),
                        receiptsByIncome.getOrDefault(entry.getId(), List.of()),
                        fundingByIncome.getOrDefault(entry.getId(), List.of())))
                .toList();
        Map<UUID, String> incomeNames = incomeEntries.stream().collect(Collectors.toMap(
                IncomeEntry::getId, IncomeEntry::getSourceName));
        Map<UUID, IncomeEntry> incomeById = incomeEntries.stream()
                .collect(Collectors.toMap(IncomeEntry::getId, Function.identity()));
        Map<UUID, IncomeReceipt> receiptById = receiptEntries.stream()
                .collect(Collectors.toMap(IncomeReceipt::getId, Function.identity()));
        List<PlannedDeductionRow> plannedDeductionRows = deductionEntries.stream()
                .sorted(Comparator.comparing((IncomeDeduction value) ->
                                incomeById.get(value.getIncomeEntryId()).getSortOrder())
                        .thenComparingInt(IncomeDeduction::getSortOrder)
                        .thenComparing(IncomeDeduction::getId))
                .map(value -> {
                    IncomeEntry entry = incomeById.get(value.getIncomeEntryId());
                    return new PlannedDeductionRow(value.getId(), value.getIncomeEntryId(), entry.getSourceName(),
                            value.getName(), value.getDeductionType(), value.getPercentageRate(),
                            value.getFixedAmount() == null ? null : calculations.money(value.getFixedAmount()),
                            calculations.projectedAmount(value, entry.getExpectedAmount()), value.getSortOrder(),
                            value.isLegacyTithe());
                }).toList();
        List<RealizedDeductionRow> realizedDeductionRows = actualDeductionEntries.stream()
                .filter(value -> {
                    IncomeReceipt receipt = receiptById.get(value.getIncomeReceiptId());
                    return receipt != null && filter.includesDate(receipt.getReceivedAt()
                            .atZone(ZoneId.of(receipt.getTimeZone())).toLocalDate());
                })
                .sorted(Comparator.comparing((IncomeReceiptDeduction value) ->
                                receiptById.get(value.getIncomeReceiptId()).getReceivedAt())
                        .thenComparing(IncomeReceiptDeduction::getId))
                .map(value -> {
                    IncomeReceipt receipt = receiptById.get(value.getIncomeReceiptId());
                    IncomeEntry entry = incomeById.get(receipt.getIncomeEntryId());
                    return new RealizedDeductionRow(value.getId(), receipt.getId(), value.getIncomeDeductionId(),
                            receipt.getIncomeEntryId(), entry.getSourceName(), receipt.getReceivedAt(),
                            receipt.getTimeZone(), value.getNameSnapshot(), calculations.money(value.getActualAmount()));
                }).toList();
        Map<UUID, String> itemNames = budgetItems.stream().collect(Collectors.toMap(BudgetItem::getId, BudgetItem::getName));
        List<IncomeBudgetMapping> incomeMappings = fundingHistory.stream()
                .map(value -> mapping(value, incomeNames, itemNames)).toList();
        Map<UUID, String> categoryNames = categoryValues.stream()
                .collect(Collectors.toMap(c -> c.getId(), c -> c.getName()));
        Map<UUID, BudgetItem> itemMap = budgetItems.stream().collect(Collectors.toMap(BudgetItem::getId, Function.identity()));
        List<NamedAmount> categoryTotals = group(entries, e -> itemMap.get(e.getBudgetItemId()).getCategoryId(),
                id -> categoryNames.getOrDefault(id, "Archived category"));
        Map<UUID, BigDecimal> projectedByCategory = new LinkedHashMap<>();
        budgetItems.forEach(item -> projectedByCategory.merge(item.getCategoryId(),
                calculations.money(item.getPlannedAmount()), BigDecimal::add));
        List<NamedAmount> projectedCategories = projectedByCategory.entrySet().stream()
                .map(value -> new NamedAmount(value.getKey(),
                        categoryNames.getOrDefault(value.getKey(), "Archived category"),
                        calculations.money(value.getValue())))
                .sorted(Comparator.comparing(NamedAmount::amount).reversed()).toList();
        List<UUID> spendingMemberIds = entries.stream().map(SpendingEntry::getSpentByUserId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID, String> memberNames = spendingMemberIds.isEmpty() ? Map.of()
                : users.findAllById(spendingMemberIds).stream().collect(Collectors.toMap(
                        value -> value.getId(), value -> value.getDisplayName()));
        List<NamedAmount> memberTotals = group(entries, SpendingEntry::getSpentByUserId,
                id -> id == null ? "Household" : memberNames.getOrDefault(id, "Former member"));
        Map<LocalDate, BigDecimal> daily = new java.util.TreeMap<>();
        entries.forEach(entry -> daily.merge(entry.getUserSelectedDate(), signed(entry), BigDecimal::add));
        List<DailyAmount> trend = daily.entrySet().stream().map(e -> new DailyAmount(e.getKey(), calculations.money(e.getValue()))).toList();
        List<SpendingResponse> largest = entries.stream().filter(e -> e.getTransactionType() == TransactionType.EXPENSE)
                .sorted(Comparator.comparing(SpendingEntry::getAmount).reversed()).limit(10).map(this::map).toList();
        List<UUID> overspent = totals.items().stream().filter(i -> i.calculation().remaining().signum() < 0)
                .map(BudgetItemResponse::id).toList();
        PeriodTotals reportTotals = totals(totals, incomeSchedule, incomeMappings);
        Map<UUID, BigDecimal> actualByReceipt = actualDeductionEntries.stream()
                .collect(Collectors.groupingBy(IncomeReceiptDeduction::getIncomeReceiptId,
                        Collectors.reducing(BigDecimal.ZERO, IncomeReceiptDeduction::getActualAmount, BigDecimal::add)));
        Map<LocalDate, BigDecimal> receiptCash = new java.util.TreeMap<>();
        receiptEntries.forEach(receipt -> {
            LocalDate receiptDate = receipt.getReceivedAt().atZone(ZoneId.of(receipt.getTimeZone())).toLocalDate();
            if (filter.includesDate(receiptDate)) {
                receiptCash.merge(receiptDate, calculations.money(receipt.getAmount().subtract(
                        actualByReceipt.getOrDefault(receipt.getId(), BigDecimal.ZERO))), BigDecimal::add);
            }
        });
        java.util.TreeSet<LocalDate> cashDates = new java.util.TreeSet<>();
        cashDates.addAll(receiptCash.keySet());
        cashDates.addAll(daily.keySet());
        List<DailyCashFlow> cashFlow = cashDates.stream().map(date -> {
            BigDecimal received = calculations.money(receiptCash.getOrDefault(date, BigDecimal.ZERO));
            BigDecimal spent = calculations.money(daily.getOrDefault(date, BigDecimal.ZERO));
            return new DailyCashFlow(date, received, spent, calculations.money(received.subtract(spent)));
        }).toList();
        List<NamedAmount> variance = totals.items().stream()
                .map(value -> new NamedAmount(value.id(), value.name(),
                        calculations.money(value.plannedAmount().subtract(value.calculation().netSpending()))))
                .sorted(Comparator.comparing((NamedAmount value) -> value.amount().abs()).reversed()).toList();
        ReportChartData chartData = new ReportChartData(projectedCategories, categoryTotals,
                List.of(new NamedAmount(null, "Planned budget", totals.plannedSpending()),
                        new NamedAmount(null, "Confirmed funding", totals.confirmedFundedBudget()),
                        new NamedAmount(null, "Actual spending", totals.netSpending())),
                List.of(new NamedAmount(null, "Projected net", totals.projectedNetIncome()),
                        new NamedAmount(null, "Received net", totals.receivedNetIncome()),
                        new NamedAmount(null, "Confirmed allocations", totals.confirmedAllocatedIncome()),
                        new NamedAmount(null, "Available to allocate", totals.unallocatedReceivedIncome())),
                List.of(new NamedAmount(null, "Fully funded", BigDecimal.valueOf(totals.fullyFundedItemCount())),
                        new NamedAmount(null, "Partially funded", BigDecimal.valueOf(totals.partiallyFundedItemCount())),
                        new NamedAmount(null, "Unfunded", BigDecimal.valueOf(totals.unfundedItemCount())),
                        new NamedAmount(null, "Overfunded", BigDecimal.valueOf(totals.items().stream()
                                .filter(value -> value.funding().status() == solutions.shapeit.wethrive.common.domain.DomainEnums.FundingStatus.OVERFUNDED)
                                .count()))), cashFlow, variance);
        return new MonthlyReport(spaceId, budget == null ? null : budget.getId(), year, month,
                budget == null ? null : budget.getName(), totals, categoryTotals, memberTotals, trend, largest,
                overspent, reportTotals, incomeSchedule, incomeMappings, plannedDeductionRows,
                realizedDeductionRows, chartData);
    }

    @Transactional
    public QuarterlyReport quarterly(UUID spaceId, int year, int quarter, UUID actorId) {
        return quarterly(spaceId, year, quarter, actorId, ReportFilter.unfiltered());
    }

    @Transactional
    public QuarterlyReport quarterly(UUID spaceId, int year, int quarter, UUID actorId,
                                     ReportFilter requestedFilter) {
        ReportFilter filter = requestedFilter == null ? ReportFilter.unfiltered() : requestedFilter;
        if (quarter < 1 || quarter > 4) throw solutions.shapeit.wethrive.common.web.ApiException.badRequest("Quarter must be between 1 and 4");
        List<MonthlyReport> months = new ArrayList<>();
        for (int month = (quarter - 1) * 3 + 1; month <= quarter * 3; month++) {
            MonthlyReport report = monthly(spaceId, year, month, actorId, filter);
            if (!filter.hasBudgetSelection() || report.budgetId() != null) months.add(report);
        }
        List<PeriodRow> rows = months.stream().map(m -> new PeriodRow(
                YearMonth.of(year, m.month()).getMonth().name(), year, m.month(), m.reportTotals())).toList();
        return new QuarterlyReport(spaceId, year, quarter, rows, aggregate(rows),
                mergeNamed(months.stream().map(MonthlyReport::categories).toList()),
                mergeNamed(months.stream().map(MonthlyReport::members).toList()),
                months.stream().flatMap(value -> value.incomeSchedule().stream()).toList(),
                months.stream().flatMap(value -> value.incomeToBudgetMappings().stream()).toList(),
                months.stream().flatMap(value -> value.plannedDeductions().stream()).toList(),
                months.stream().flatMap(value -> value.realizedDeductions().stream()).toList());
    }

    @Transactional
    public AnnualReport annual(UUID spaceId, int year, UUID actorId) {
        return annual(spaceId, year, actorId, ReportFilter.unfiltered());
    }

    @Transactional
    public AnnualReport annual(UUID spaceId, int year, UUID actorId, ReportFilter requestedFilter) {
        ReportFilter filter = requestedFilter == null ? ReportFilter.unfiltered() : requestedFilter;
        List<MonthlyReport> reports = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            MonthlyReport report = monthly(spaceId, year, month, actorId, filter);
            if (!filter.hasBudgetSelection() || report.budgetId() != null) reports.add(report);
        }
        List<PeriodRow> months = reports.stream().map(m -> new PeriodRow(
                YearMonth.of(year, m.month()).getMonth().name(), year, m.month(), m.reportTotals())).toList();
        List<PeriodRow> quarters = new ArrayList<>();
        for (int q = 1; q <= 4; q++) {
            int firstMonth = (q - 1) * 3 + 1;
            int lastMonth = q * 3;
            List<PeriodRow> quarterRows = months.stream()
                    .filter(value -> value.month() != null && value.month() >= firstMonth && value.month() <= lastMonth)
                    .toList();
            if (!filter.hasBudgetSelection() || !quarterRows.isEmpty()) {
                quarters.add(new PeriodRow("Q" + q, year, null, aggregate(quarterRows)));
            }
        }
        return new AnnualReport(spaceId, year, months, quarters, aggregate(months),
                mergeNamed(reports.stream().map(MonthlyReport::categories).toList()),
                mergeNamed(reports.stream().map(MonthlyReport::members).toList()),
                reports.stream().flatMap(value -> value.incomeSchedule().stream()).toList(),
                reports.stream().flatMap(value -> value.incomeToBudgetMappings().stream()).toList(),
                reports.stream().flatMap(value -> value.plannedDeductions().stream()).toList(),
                reports.stream().flatMap(value -> value.realizedDeductions().stream()).toList());
    }

    @Transactional
    public List<NamedAmount> categories(UUID spaceId, int year, int month, UUID actorId) { return monthly(spaceId, year, month, actorId).categories(); }
    @Transactional
    public List<NamedAmount> members(UUID spaceId, int year, int month, UUID actorId) { return monthly(spaceId, year, month, actorId).members(); }
    @Transactional
    public List<IncomeScheduleRow> incomeSchedule(UUID spaceId, int year, int month, UUID actorId) {
        return monthly(spaceId, year, month, actorId).incomeSchedule();
    }
    @Transactional
    public List<IncomeBudgetMapping> incomeToBudgetMappings(UUID spaceId, int year, int month, UUID actorId) {
        return monthly(spaceId, year, month, actorId).incomeToBudgetMappings();
    }

    private PeriodTotals totals(BudgetSummary s, List<IncomeScheduleRow> schedule,
                                List<IncomeBudgetMapping> mappings) {
        BigDecimal plannedFunding = calculations.money(s.items().stream()
                .map(value -> value.funding().plannedFunding()).reduce(FinanceCalculationService.ZERO, BigDecimal::add));
        BigDecimal unspentAllocated = calculations.money(s.items().stream()
                .map(value -> value.funding().unspentAllocatedFunds()).reduce(FinanceCalculationService.ZERO, BigDecimal::add));
        BigDecimal unfundedSpending = calculations.money(s.items().stream()
                .map(value -> value.funding().unfundedSpending()).reduce(FinanceCalculationService.ZERO, BigDecimal::add));
        return new PeriodTotals(s.projectedGrossIncome(), s.projectedNetIncome(), s.receivedGrossIncome(), s.receivedNetIncome(),
                s.projectedDeductions(), s.realizedDeductions(), s.remainingExpectedIncome(), s.confirmedAllocatedIncome(),
                s.unallocatedReceivedIncome(), s.plannedSpending(), plannedFunding, s.confirmedFundedBudget(), s.fundingGap(),
                s.fullyFundedItemCount(), s.partiallyFundedItemCount(), s.unfundedItemCount(), s.grossSpending(), s.refunds(),
                s.netSpending(), s.unbudgetedSpending(), unspentAllocated, unfundedSpending, s.remainingIncome(),
                s.spendingRate(), s.status(), statusCounts(schedule), schedule.stream().mapToInt(IncomeScheduleRow::receiptCount).sum(),
                (int) mappings.stream().filter(value -> value.reversedAt() == null).count(),
                (int) mappings.stream().filter(value -> value.reversedAt() != null).count());
    }
    private PeriodTotals aggregate(List<PeriodRow> rows) {
        BigDecimal projectedGross = sum(rows, r -> r.totals().projectedGrossIncome());
        BigDecimal projectedNet = sum(rows, r -> r.totals().projectedNetIncome());
        BigDecimal receivedGross = sum(rows, r -> r.totals().receivedGrossIncome());
        BigDecimal receivedNet = sum(rows, r -> r.totals().receivedNetIncome());
        BigDecimal projectedDeductions = sum(rows, r -> r.totals().projectedDeductions());
        BigDecimal realizedDeductions = sum(rows, r -> r.totals().realizedDeductions());
        BigDecimal outstanding = sum(rows, r -> r.totals().outstandingExpectedIncome());
        BigDecimal confirmedAllocated = sum(rows, r -> r.totals().confirmedAllocatedIncome());
        BigDecimal unallocatedReceived = sum(rows, r -> r.totals().unallocatedReceivedIncome());
        BigDecimal planned = sum(rows, r -> r.totals().plannedSpending());
        BigDecimal plannedFunding = sum(rows, r -> r.totals().plannedFunding());
        BigDecimal confirmedFunded = sum(rows, r -> r.totals().confirmedFundedBudget());
        BigDecimal fundingGap = sum(rows, r -> r.totals().fundingGap());
        int fullyFunded = rows.stream().mapToInt(r -> r.totals().fullyFundedItemCount()).sum();
        int partiallyFunded = rows.stream().mapToInt(r -> r.totals().partiallyFundedItemCount()).sum();
        int unfunded = rows.stream().mapToInt(r -> r.totals().unfundedItemCount()).sum();
        BigDecimal gross = sum(rows, r -> r.totals().grossSpending());
        BigDecimal refunds = sum(rows, r -> r.totals().refunds());
        BigDecimal net = sum(rows, r -> r.totals().netSpending());
        BigDecimal unbudgeted = sum(rows, r -> r.totals().unbudgetedSpending());
        BigDecimal unspentAllocated = sum(rows, r -> r.totals().unspentAllocatedFunds());
        BigDecimal unfundedSpending = sum(rows, r -> r.totals().unfundedSpending());
        BigDecimal remaining = sum(rows, r -> r.totals().remaining());
        BigDecimal rate = receivedNet.signum() == 0 ? FinanceCalculationService.ZERO : net.multiply(new BigDecimal("100")).divide(receivedNet, 2, RoundingMode.HALF_EVEN);
        IncomeStatusCounts statusCounts = new IncomeStatusCounts(
                rows.stream().mapToInt(r -> r.totals().incomeStatuses().scheduled()).sum(),
                rows.stream().mapToInt(r -> r.totals().incomeStatuses().due()).sum(),
                rows.stream().mapToInt(r -> r.totals().incomeStatuses().late()).sum(),
                rows.stream().mapToInt(r -> r.totals().incomeStatuses().partiallyReceived()).sum(),
                rows.stream().mapToInt(r -> r.totals().incomeStatuses().received()).sum(),
                rows.stream().mapToInt(r -> r.totals().incomeStatuses().cancelled()).sum());
        return new PeriodTotals(projectedGross, projectedNet, receivedGross, receivedNet, projectedDeductions,
                realizedDeductions, outstanding, confirmedAllocated, unallocatedReceived, planned, plannedFunding, confirmedFunded,
                fundingGap, fullyFunded, partiallyFunded, unfunded, gross, refunds, net, unbudgeted,
                unspentAllocated, unfundedSpending, remaining, rate, calculations.status(rate), statusCounts,
                rows.stream().mapToInt(r -> r.totals().receiptCount()).sum(),
                rows.stream().mapToInt(r -> r.totals().fundingAllocationCount()).sum(),
                rows.stream().mapToInt(r -> r.totals().reversedFundingAllocationCount()).sum());
    }
    private BigDecimal sum(List<PeriodRow> rows, Function<PeriodRow, BigDecimal> mapper) { return calculations.money(rows.stream().map(mapper).reduce(FinanceCalculationService.ZERO, BigDecimal::add)); }

    private IncomeScheduleRow schedule(IncomeEntry entry, IncomeResponse response,
                                       List<IncomeReceipt> receiptValues,
                                       List<BudgetFundingAllocation> fundingValues) {
        BigDecimal plannedFunding = calculations.money(fundingValues.stream()
                .map(BudgetFundingAllocation::getPlannedAmount)
                .reduce(FinanceCalculationService.ZERO, BigDecimal::add));
        return new IncomeScheduleRow(entry.getId(), entry.getBudgetMonthId(), entry.getSourceName(),
                calculations.money(entry.getExpectedAmount()), entry.getExpectedDate(), entry.getExpectedTime(),
                entry.getTimeZone(), response.availability().status(),
                response.availability().projectedGrossIncome(), response.availability().projectedNetIncome(),
                response.availability().receivedGrossIncome(), response.availability().receivedNetIncome(),
                response.availability().remainingExpectedIncome(), plannedFunding,
                response.availability().confirmedAllocatedAmount(),
                response.availability().unallocatedReceivedIncome(), receiptValues.size(),
                entry.isRecurring(), entry.getCancelledAt());
    }

    private IncomeBudgetMapping mapping(BudgetFundingAllocation value, Map<UUID, String> incomeNames,
                                        Map<UUID, String> itemNames) {
        String sourceName = value.getIncomeEntryId() == null
                ? switch (value.getSourceType()) {
                    case EXTERNAL_FUNDS -> "External funds";
                    case UNASSIGNED_FUNDS -> "Unassigned funds";
                    case ROLLOVER_FUNDS -> "Rollover funds";
                    case INCOME_ENTRY -> "Archived income";
                }
                : incomeNames.getOrDefault(value.getIncomeEntryId(), "Archived income");
        return new IncomeBudgetMapping(value.getId(), value.getIncomeEntryId(), sourceName,
                value.getBudgetItemId(), itemNames.getOrDefault(value.getBudgetItemId(), "Archived budget item"),
                value.getSourceType(), calculations.money(value.getPlannedAmount()),
                calculations.money(value.getConfirmedAllocatedAmount()), value.getAllocatedAt(),
                value.getTimeZone(), value.getCreatedAt(), value.getUpdatedAt(), value.getDeletedAt(),
                value.getNotes());
    }

    private IncomeStatusCounts statusCounts(List<IncomeScheduleRow> schedule) {
        Map<IncomeStatus, Integer> values = new EnumMap<>(IncomeStatus.class);
        schedule.forEach(value -> values.merge(value.status(), 1, Integer::sum));
        return new IncomeStatusCounts(values.getOrDefault(IncomeStatus.SCHEDULED, 0),
                values.getOrDefault(IncomeStatus.DUE, 0), values.getOrDefault(IncomeStatus.LATE, 0),
                values.getOrDefault(IncomeStatus.PARTIALLY_RECEIVED, 0),
                values.getOrDefault(IncomeStatus.RECEIVED, 0),
                values.getOrDefault(IncomeStatus.CANCELLED, 0));
    }

    private List<NamedAmount> group(List<SpendingEntry> entries, Function<SpendingEntry, UUID> key, Function<UUID, String> name) {
        Map<UUID, BigDecimal> values = new LinkedHashMap<>();
        entries.forEach(entry -> values.merge(key.apply(entry), signed(entry), BigDecimal::add));
        return values.entrySet().stream().map(e -> new NamedAmount(e.getKey(), name.apply(e.getKey()), calculations.money(e.getValue())))
                .sorted(Comparator.comparing(NamedAmount::amount).reversed()).toList();
    }
    private List<NamedAmount> mergeNamed(List<List<NamedAmount>> groups) {
        Map<UUID, NamedAmount> values = new LinkedHashMap<>();
        groups.stream().flatMap(List::stream).forEach(value -> values.merge(value.id(), value,
                (a, b) -> new NamedAmount(a.id(), a.name(), calculations.money(a.amount().add(b.amount())))));
        return values.values().stream().sorted(Comparator.comparing(NamedAmount::amount).reversed()).toList();
    }
    private BigDecimal signed(SpendingEntry e) { return e.getTransactionType() == TransactionType.REFUND ? e.getAmount().negate() : e.getAmount(); }
    private LocalDate todayForIncome(IncomeEntry entry) {
        return LocalDate.now(clock.withZone(ZoneId.of(entry.getTimeZone())));
    }
    private SpendingResponse map(SpendingEntry e) { return new SpendingResponse(e.getId(), e.getSpaceId(), e.getBudgetItemId(), e.getTransactionType(), e.getTitle(), e.getAmount(), e.getSpentAt(), e.getUserSelectedDate(), e.getUserSelectedTime(), e.getTimeZone(), e.getPaymentMethod(), e.getMerchant(), e.getNotes(), e.getSpentByUserId(), e.getCreatedByUserId(), e.getRefundForSpendingEntryId(), e.getVersion()); }
}
