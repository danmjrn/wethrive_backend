package solutions.shapeit.wethrive.export.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import javax.imageio.ImageIO;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import solutions.shapeit.wethrive.common.config.ApplicationProperties;
import solutions.shapeit.wethrive.common.domain.DomainEnums.TransactionType;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetItemResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.FundingAllocationResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeDeductionResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptDeductionResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.SpendingResponse;
import solutions.shapeit.wethrive.finance.entity.BudgetFundingAllocation;
import solutions.shapeit.wethrive.finance.entity.IncomeDeduction;
import solutions.shapeit.wethrive.finance.entity.IncomeReceipt;
import solutions.shapeit.wethrive.finance.entity.IncomeReceiptDeduction;
import solutions.shapeit.wethrive.finance.repository.BudgetFundingAllocationRepository;
import solutions.shapeit.wethrive.finance.repository.BudgetItemRepository;
import solutions.shapeit.wethrive.finance.repository.BudgetMonthRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeDeductionRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeEntryRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptDeductionRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptRepository;
import solutions.shapeit.wethrive.finance.repository.SpendingEntryRepository;
import solutions.shapeit.wethrive.finance.service.BudgetService;
import solutions.shapeit.wethrive.finance.service.FinanceCalculationService;
import solutions.shapeit.wethrive.finance.service.SpendingService;
import solutions.shapeit.wethrive.identity.repository.AppUserRepository;
import solutions.shapeit.wethrive.report.dto.ReportDtos.AnnualReport;
import solutions.shapeit.wethrive.report.dto.ReportDtos.MonthlyReport;
import solutions.shapeit.wethrive.report.dto.ReportDtos.PeriodRow;
import solutions.shapeit.wethrive.report.dto.ReportDtos.PeriodTotals;
import solutions.shapeit.wethrive.report.dto.ReportDtos.QuarterlyReport;
import solutions.shapeit.wethrive.report.dto.ReportDtos.ReportFilter;
import solutions.shapeit.wethrive.report.service.ReportService;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.SpaceResponse;
import solutions.shapeit.wethrive.space.repository.SpaceMembershipRepository;
import solutions.shapeit.wethrive.space.service.SpaceService;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;
import solutions.shapeit.wethrive.space.service.SpaceAccessService.Capability;

@Service
public class ExcelExportService {
    static final int MAX_LEDGER_ROWS = 25_000;
    static final int MAX_FINANCE_SOURCE_ROWS = 25_000;
    static final int MAX_WORKBOOK_DATA_ROWS = 50_000;
    static final int MAX_XLSX_BYTES = 64 * 1024 * 1024;
    private static final int PAGE_SIZE = 200;

    /**
     * Selects the visual palette used by generated Excel and PDF exports.
     *
     * @author Daniel Jr Nkulu
     */
    public enum ExportTheme { WETHRIVE_ORIGINAL, SHAPE_IT_MONOCHROME, WORKBOOK_INSPIRED, CUSTOM }
    public enum ExportScope { CURRENT_REPORT, SELECTED_BUDGETS, FULL_YEAR }
    public record ExcelExportOptions(boolean includeDetailedLedgers, boolean includeCharts,
                                     ExportTheme theme, String accent, ExportScope scope,
                                     List<UUID> budgetIds, LocalDate from, LocalDate to,
                                     String category) {
        public ExcelExportOptions {
            theme = theme == null ? ExportTheme.WETHRIVE_ORIGINAL : theme;
            accent = accent == null ? "" : accent.trim();
            scope = scope == null ? ExportScope.CURRENT_REPORT : scope;
            budgetIds = budgetIds == null ? List.of()
                    : List.copyOf(new java.util.LinkedHashSet<>(budgetIds));
            category = category == null || category.isBlank() ? null : category.trim();
            if (from != null && to != null && from.isAfter(to)) {
                throw solutions.shapeit.wethrive.common.web.ApiException.badRequest(
                        "Export start date must be on or before the end date");
            }
            if (theme == ExportTheme.CUSTOM && !accent.matches("#[0-9a-fA-F]{6}")) {
                throw solutions.shapeit.wethrive.common.web.ApiException.badRequest(
                        "Custom export accent must be a six-digit hexadecimal colour");
            }
            if (scope == ExportScope.SELECTED_BUDGETS && budgetIds.isEmpty()) {
                throw solutions.shapeit.wethrive.common.web.ApiException.badRequest(
                        "Select at least one budget for this export scope");
            }
        }
        public ExcelExportOptions(boolean includeDetailedLedgers, boolean includeCharts,
                                  ExportTheme theme, String accent) {
            this(includeDetailedLedgers, includeCharts, theme, accent, ExportScope.CURRENT_REPORT,
                    List.of(), null, null, null);
        }
        public static ExcelExportOptions defaults() {
            return new ExcelExportOptions(true, true, ExportTheme.WETHRIVE_ORIGINAL, "");
        }
        public ReportFilter reportFilter() {
            return new ReportFilter(from, to, category,
                    scope == ExportScope.SELECTED_BUDGETS ? budgetIds : List.of());
        }
        public boolean hasReportSelection() {
            return scope == ExportScope.SELECTED_BUDGETS || from != null || to != null || category != null;
        }
    }
    private final BudgetService budgets;
    private final SpendingService spending;
    private final ReportService reports;
    private final SpaceService spaces;
    private final SpaceMembershipRepository memberships;
    private final AppUserRepository users;
    private final BudgetItemRepository budgetItems;
    private final BudgetMonthRepository budgetMonths;
    private final IncomeEntryRepository incomeEntries;
    private final IncomeReceiptRepository incomeReceipts;
    private final IncomeDeductionRepository incomeDeductions;
    private final IncomeReceiptDeductionRepository actualDeductions;
    private final BudgetFundingAllocationRepository fundingAllocations;
    private final SpendingEntryRepository spendingEntries;
    private final FinanceCalculationService calculations;
    private final ApplicationProperties properties;
    private final Clock clock;
    private final SpaceAccessService access;
    private final Map<Workbook, WorkbookState> workbookStates = java.util.Collections.synchronizedMap(new WeakHashMap<>());

    public ExcelExportService(BudgetService budgets, SpendingService spending, ReportService reports,
                              SpaceService spaces, SpaceMembershipRepository memberships,
                              AppUserRepository users, BudgetItemRepository budgetItems,
                              BudgetMonthRepository budgetMonths,
                              IncomeEntryRepository incomeEntries, IncomeReceiptRepository incomeReceipts,
                              IncomeDeductionRepository incomeDeductions,
                              IncomeReceiptDeductionRepository actualDeductions,
                              BudgetFundingAllocationRepository fundingAllocations,
                              SpendingEntryRepository spendingEntries,
                              FinanceCalculationService calculations, SpaceAccessService access,
                              ApplicationProperties properties, Clock clock) {
        this.budgets = budgets; this.spending = spending; this.reports = reports; this.spaces = spaces;
        this.memberships = memberships; this.users = users; this.budgetItems = budgetItems;
        this.budgetMonths = budgetMonths;
        this.incomeEntries = incomeEntries; this.incomeReceipts = incomeReceipts;
        this.incomeDeductions = incomeDeductions; this.actualDeductions = actualDeductions;
        this.fundingAllocations = fundingAllocations; this.spendingEntries = spendingEntries;
        this.calculations = calculations;
        this.properties = properties; this.clock = clock;
        this.access = access;
    }

    public byte[] allBudgets(UUID actorId, UUID requestedSpaceId) {
        return allBudgets(actorId, requestedSpaceId, ExcelExportOptions.defaults());
    }

    public byte[] allBudgets(UUID actorId, UUID requestedSpaceId, ExcelExportOptions options) {
        List<SpaceResponse> authorized;
        if (requestedSpaceId == null) {
            authorized = spaces.list(actorId).stream()
                    .filter(space -> access.can(space.id(), actorId, Capability.EXPORT)).toList();
        } else {
            access.require(requestedSpaceId, actorId, Capability.EXPORT);
            authorized = List.of(spaces.get(requestedSpaceId, actorId));
        }
        preflightSpaceSources(authorized.stream().map(SpaceResponse::id)
                .collect(java.util.stream.Collectors.toSet()));
        return workbook("All Budgets", options, workbook -> {
            Sheet sheet = sheet(workbook, "Monthly Budgets", "Authorized budgets", requestedSpaceId == null ? "All accessible spaces" : spaces.get(requestedSpaceId, actorId).name());
            String[] headers = {"Space", "Currency", "Year", "Month", "Budget", "Status", "Projected Net", "Received Net", "Unallocated Received", "Planned", "Confirmed Funded", "Funding Gap", "Net Spending", "Budget vs Actual Variance", "Remaining Cash", "Health"};
            int row = tableHeader(sheet, 5, headers);
            for (SpaceResponse space : authorized) {
                for (BudgetResponse budget : budgets.listForExport(space.id(), actorId)) {
                    reserveWorkbookRows(workbook, 1, "Excel budget register");
                    Row data = sheet.createRow(row++);
                    text(data, 0, space.name()); text(data, 1, space.currencyCode());
                    number(data, 2, budget.year(), null); number(data, 3, budget.month(), null);
                    text(data, 4, budget.name()); text(data, 5, budget.status().name());
                    money(data, 6, budget.summary().projectedNetIncome(), workbook, space.currencyCode());
                    money(data, 7, budget.summary().receivedNetIncome(), workbook, space.currencyCode());
                    money(data, 8, budget.summary().unallocatedReceivedIncome(), workbook, space.currencyCode());
                    money(data, 9, budget.summary().plannedSpending(), workbook, space.currencyCode());
                    money(data, 10, budget.summary().confirmedFundedBudget(), workbook, space.currencyCode());
                    money(data, 11, budget.summary().fundingGap(), workbook, space.currencyCode());
                    money(data, 12, budget.summary().netSpending(), workbook, space.currencyCode());
                    money(data, 13, budget.summary().plannedSpending().subtract(budget.summary().netSpending()), workbook, space.currencyCode());
                    money(data, 14, budget.summary().remainingIncome(), workbook, space.currencyCode());
                    text(data, 15, budget.summary().status());
                }
            }
            finishTable(sheet, 5, row - 1, headers.length);
        });
    }

    public byte[] budget(UUID budgetId, UUID actorId) {
        return budget(budgetId, actorId, ExcelExportOptions.defaults());
    }

    public byte[] budget(UUID budgetId, UUID actorId, ExcelExportOptions options) {
        var budgetReference = budgets.requireBudget(budgetId);
        access.require(budgetReference.getSpaceId(), actorId, Capability.EXPORT);
        preflightSpaceSources(Set.of(budgetReference.getSpaceId()));
        BudgetResponse budget = budgets.getForExport(budgetId, actorId);
        selectBudgets(List.of(budget), options);
        if (options.includeDetailedLedgers()) preflightFinanceDetail(List.of(budget));
        SpaceResponse space = spaces.get(budget.spaceId(), actorId);
        return workbook(budget.name(), options,
                workbook -> { setCurrency(workbook, space.currencyCode()); writeMonthly(workbook, space, budget, actorId); });
    }

    public byte[] budgetItem(UUID itemId, UUID actorId) {
        return budgetItem(itemId, actorId, ExcelExportOptions.defaults());
    }

    public byte[] budgetItem(UUID itemId, UUID actorId, ExcelExportOptions options) {
        var itemReference = budgets.requireItem(itemId);
        access.require(itemReference.getSpaceId(), actorId, Capability.EXPORT);
        preflightSpaceSources(Set.of(itemReference.getSpaceId()));
        BudgetItemResponse item = budgets.getItem(itemId, actorId);
        SpaceResponse space = spaces.get(item.spaceId(), actorId);
        return workbook(item.name(), options, workbook -> {
            setCurrency(workbook, space.currencyCode());
            Sheet overview = sheet(workbook, "Overview", item.name(), space.name());
            keyValue(overview, 5, "Planned", item.plannedAmount(), workbook);
            keyValue(overview, 6, "Gross spending", item.calculation().grossSpending(), workbook);
            keyValue(overview, 7, "Refunds", item.calculation().refunds(), workbook);
            keyValue(overview, 8, "Net spending", item.calculation().netSpending(), workbook);
            keyValue(overview, 9, "Budget vs actual variance", item.calculation().remaining(), workbook);
            keyValue(overview, 10, "Usage", item.calculation().usagePercentage().divide(new BigDecimal("100")), workbook, true);
            keyValue(overview, 11, "Planned funding", item.funding().plannedFunding(), workbook);
            keyValue(overview, 12, "Confirmed allocation", item.funding().confirmedAllocatedAmount(), workbook);
            keyValue(overview, 13, "Funding gap", item.funding().fundingGap(), workbook);
            keyValue(overview, 14, "Unspent allocated funds", item.funding().unspentAllocatedFunds(), workbook);
            keyValue(overview, 15, "Unfunded spending", item.funding().unfundedSpending(), workbook);
            text(overview.createRow(16), 0, "Funding status"); text(overview.getRow(16), 1, item.funding().status().name());
            List<SpendingResponse> entries = pageAll(actorId, item.spaceId()).stream().filter(s -> s.budgetItemId().equals(itemId)).toList();
            writeSpending(workbook, entries, space, actorId);
        });
    }

    public byte[] spending(UUID actorId, UUID spaceId, UUID memberId, Instant from, Instant to) {
        return spending(actorId, spaceId, memberId, from, to, ExcelExportOptions.defaults());
    }

    public byte[] spending(UUID actorId, UUID spaceId, UUID memberId, Instant from, Instant to,
                           ExcelExportOptions options) {
        if (spaceId != null) access.require(spaceId, actorId, Capability.EXPORT);
        SpaceResponse space = spaceId == null ? null : spaces.get(spaceId, actorId);
        java.util.Set<UUID> exportableSpaces = spaceId == null
                ? spaces.list(actorId).stream().filter(value -> access.can(value.id(), actorId, Capability.EXPORT))
                    .map(SpaceResponse::id).collect(java.util.stream.Collectors.toSet())
                : java.util.Set.of(spaceId);
        List<SpendingResponse> candidates = new ArrayList<>();
        for (UUID exportableSpaceId : exportableSpaces.stream().sorted().toList()) {
            List<SpendingResponse> spaceEntries = pageAll(actorId, exportableSpaceId, from, to,
                    MAX_LEDGER_ROWS - candidates.size());
            requireWithinLimit(candidates.size() + (long) spaceEntries.size(), MAX_LEDGER_ROWS,
                    "Excel spending ledger");
            candidates.addAll(spaceEntries);
        }
        List<SpendingResponse> values = candidates.stream()
                .filter(entry -> exportableSpaces.contains(entry.spaceId()))
                .filter(entry -> memberId == null || memberId.equals(entry.spentByUserId()))
                .filter(entry -> from == null || !entry.spentAt().isBefore(from))
                .filter(entry -> to == null || entry.spentAt().isBefore(to))
                .sorted(Comparator.comparing(SpendingResponse::spentAt).reversed()
                        .thenComparing(SpendingResponse::id))
                .toList();
        return workbook("Spending Ledger", options,
                workbook -> { if (space != null) setCurrency(workbook, space.currencyCode()); writeSpending(workbook, values, space, actorId); });
    }

    public byte[] monthly(UUID actorId, UUID spaceId, int year, int month) {
        return monthly(actorId, spaceId, year, month, ExcelExportOptions.defaults());
    }

    public byte[] monthly(UUID actorId, UUID spaceId, int year, int month, ExcelExportOptions options) {
        access.require(spaceId, actorId, Capability.EXPORT);
        preflightSpaceSources(Set.of(spaceId));
        SpaceResponse space = spaces.get(spaceId, actorId);
        List<BudgetResponse> periodBudgets = selectBudgets(
                budgets.listForExport(spaceId, year, month, month, actorId), options);
        if (options.includeDetailedLedgers()) preflightFinanceDetail(periodBudgets);
        MonthlyReport report = monthlyReport(spaceId, year, month, actorId, options);
        BudgetResponse budget = periodBudgets.isEmpty() ? null : periodBudgets.getFirst();
        return workbook("Monthly Report", options, workbook -> {
            setCurrency(workbook, space.currencyCode()); writeMonthlyReport(workbook, report, space);
            if (options.includeDetailedLedgers()) {
                writeIncomeAndFunding(workbook, budget == null ? List.of() : List.of(budget), actorId, space,
                        reportItemIds(report, options));
            }
        });
    }

    public byte[] quarterly(UUID actorId, UUID spaceId, int year, int quarter) {
        return quarterly(actorId, spaceId, year, quarter, ExcelExportOptions.defaults());
    }

    public byte[] quarterly(UUID actorId, UUID spaceId, int year, int quarter, ExcelExportOptions options) {
        access.require(spaceId, actorId, Capability.EXPORT);
        preflightSpaceSources(Set.of(spaceId));
        SpaceResponse space = spaces.get(spaceId, actorId);
        int firstMonth = (quarter - 1) * 3 + 1;
        List<BudgetResponse> periodBudgets = selectBudgets(
                budgets.listForExport(spaceId, year, firstMonth, firstMonth + 2, actorId), options);
        if (options.includeDetailedLedgers()) preflightFinanceDetail(periodBudgets);
        QuarterlyReport report = quarterlyReport(spaceId, year, quarter, actorId, options);
        return workbook("Quarterly Report", options, workbook -> {
            setCurrency(workbook, space.currencyCode());
            writePeriodOverview(workbook, "Q" + quarter + " " + year, report.totals(), space);
            writePeriodRows(workbook, "Quarterly Summary", "Q" + quarter + " " + year,
                    report.months(), report.totals(), space);
            if (options.includeDetailedLedgers()) {
                writeIncomeAndFunding(workbook, periodBudgets, actorId, space,
                        reportItemIds(spaceId, periodBudgets, actorId, options));
            }
        });
    }

    public byte[] annual(UUID actorId, UUID spaceId, int year) {
        return annual(actorId, spaceId, year, ExcelExportOptions.defaults());
    }

    public byte[] annual(UUID actorId, UUID spaceId, int year, ExcelExportOptions options) {
        access.require(spaceId, actorId, Capability.EXPORT);
        preflightSpaceSources(Set.of(spaceId));
        SpaceResponse space = spaces.get(spaceId, actorId);
        List<BudgetResponse> periodBudgets = selectBudgets(
                budgets.listForExport(spaceId, year, 1, 12, actorId), options);
        if (options.includeDetailedLedgers()) preflightFinanceDetail(periodBudgets);
        AnnualReport report = annualReport(spaceId, year, actorId, options);
        return workbook("Annual Report", options, workbook -> {
            setCurrency(workbook, space.currencyCode());
            writePeriodOverview(workbook, String.valueOf(year), report.totals(), space);
            writePeriodRows(workbook, "Monthly Summary", String.valueOf(year), report.months(), report.totals(), space);
            writePeriodRows(workbook, "Quarterly Summary", String.valueOf(year), report.quarters(), report.totals(), space);
            if (options.includeDetailedLedgers()) {
                writeIncomeAndFunding(workbook, periodBudgets, actorId, space,
                        reportItemIds(spaceId, periodBudgets, actorId, options));
            }
        });
    }

    public byte[] full(UUID actorId, UUID spaceId, int year) {
        return full(actorId, spaceId, year, ExcelExportOptions.defaults());
    }

    public byte[] full(UUID actorId, UUID spaceId, int year, ExcelExportOptions options) {
        access.require(spaceId, actorId, Capability.EXPORT);
        preflightSpaceSources(Set.of(spaceId));
        SpaceResponse space = spaces.get(spaceId, actorId);
        List<BudgetResponse> periodBudgets = selectBudgets(
                budgets.listForExport(spaceId, year, 1, 12, actorId), options);
        if (options.includeDetailedLedgers()) preflightFinanceDetail(periodBudgets);
        AnnualReport report = annualReport(spaceId, year, actorId, options);
        Set<UUID> includedItemIds = reportItemIds(spaceId, periodBudgets, actorId, options);
        Set<UUID> ledgerItemIds = includedItemIds == null ? periodItemIds(periodBudgets) : includedItemIds;
        return workbook("Complete Financial Report", options, workbook -> {
            setCurrency(workbook, space.currencyCode());
            Sheet overview = sheet(workbook, "Overview", "Complete Financial Report " + year, space.name());
            keyValue(overview, 5, "Projected net income", report.totals().projectedNetIncome(), workbook);
            keyValue(overview, 6, "Received net income", report.totals().receivedNetIncome(), workbook);
            keyValue(overview, 7, "Outstanding expected income", report.totals().outstandingExpectedIncome(), workbook);
            keyValue(overview, 8, "Unallocated received income", report.totals().unallocatedReceivedIncome(), workbook);
            keyValue(overview, 9, "Planned spending", report.totals().plannedSpending(), workbook);
            keyValue(overview, 10, "Planned funding", report.totals().plannedFunding(), workbook);
            keyValue(overview, 11, "Confirmed funded budget", report.totals().confirmedFundedBudget(), workbook);
            keyValue(overview, 12, "Funding gap", report.totals().fundingGap(), workbook);
            keyValue(overview, 13, "Unspent allocated funds", report.totals().unspentAllocatedFunds(), workbook);
            keyValue(overview, 14, "Unfunded spending", report.totals().unfundedSpending(), workbook);
            keyValue(overview, 15, "Gross spending", report.totals().grossSpending(), workbook);
            keyValue(overview, 16, "Refunds", report.totals().refunds(), workbook);
            keyValue(overview, 17, "Net spending", report.totals().netSpending(), workbook);
            keyValue(overview, 18, "Remaining", report.totals().remaining(), workbook);
            keyValue(overview, 19, "Budget vs actual variance",
                    report.totals().plannedSpending().subtract(report.totals().netSpending()), workbook);
            writePeriodRows(workbook, "Monthly Summary", String.valueOf(year), report.months(), report.totals(), space);
            writePeriodRows(workbook, "Quarterly Summary", String.valueOf(year), report.quarters(), report.totals(), space);
            writeNamed(workbook, "Category Analysis", report.categories(), space);
            writeNamed(workbook, "Member Analysis", report.members(), space);
            if (options.includeDetailedLedgers()) {
                writeSpending(workbook, filterSpending(pageAll(actorId, spaceId), options, ledgerItemIds),
                        space, actorId);
                writeIncomeAndFunding(workbook, periodBudgets, actorId, space, includedItemIds);
            }
        });
    }

    private void writeMonthly(Workbook workbook, SpaceResponse space, BudgetResponse budget, UUID actorId) {
        ExcelExportOptions options = state(workbook).options;
        MonthlyReport report = monthlyReport(space.id(), budget.year(), budget.month(), actorId, options);
        Set<UUID> includedItemIds = reportItemIds(report, options);
        writeMonthlyReport(workbook, report, space);
        if (options.includeDetailedLedgers()) {
            writeIncomeAndFunding(workbook, List.of(budget), actorId, space, includedItemIds);
            writeSpending(workbook, filterSpending(pageAll(actorId, space.id()).stream()
                    .filter(s -> budget.summary().items().stream().anyMatch(i -> i.id().equals(s.budgetItemId())))
                    .toList(), options, includedItemIds), space, actorId);
        }
    }

    private void writeMonthlyReport(Workbook workbook, MonthlyReport report, SpaceResponse space) {
        reserveWorkbookRows(workbook, report.totals().items().size(), "Excel monthly report");
        Sheet overview = sheet(workbook, "Overview", report.budgetName() == null ? "Blank monthly report" : report.budgetName(), space.name());
        keyValue(overview, 5, "Projected gross income", report.totals().projectedGrossIncome(), workbook);
        keyValue(overview, 6, "Projected net income", report.totals().projectedNetIncome(), workbook);
        keyValue(overview, 7, "Received gross income", report.totals().receivedGrossIncome(), workbook);
        keyValue(overview, 8, "Received net income", report.totals().receivedNetIncome(), workbook);
        keyValue(overview, 9, "Outstanding expected income", report.reportTotals().outstandingExpectedIncome(), workbook);
        keyValue(overview, 10, "Unallocated received income", report.totals().unallocatedReceivedIncome(), workbook);
        keyValue(overview, 11, "Planned spending", report.totals().plannedSpending(), workbook);
        keyValue(overview, 12, "Planned funding", report.reportTotals().plannedFunding(), workbook);
        keyValue(overview, 13, "Confirmed funded budget", report.totals().confirmedFundedBudget(), workbook);
        keyValue(overview, 14, "Funding gap", report.totals().fundingGap(), workbook);
        keyValue(overview, 15, "Unspent allocated funds", report.reportTotals().unspentAllocatedFunds(), workbook);
        keyValue(overview, 16, "Unfunded spending", report.reportTotals().unfundedSpending(), workbook);
            keyValue(overview, 17, "Gross spending", report.totals().grossSpending(), workbook);
            keyValue(overview, 18, "Refunds", report.totals().refunds(), workbook);
            keyValue(overview, 19, "Net spending", report.totals().netSpending(), workbook);
            keyValue(overview, 20, "Remaining", report.totals().remainingIncome(), workbook);
            keyValue(overview, 21, "Budget vs actual variance",
                    report.totals().plannedSpending().subtract(report.totals().netSpending()), workbook);
        if (state(workbook).options.includeCharts() && report.charts() != null) {
            ExcelExportOptions options = state(workbook).options;
            embedChart(workbook, overview,
                    PdfExportService.doughnut(report.charts().projectedExpensesByCategory(),
                            space.currencyCode(), options),
                    3, 5, 11, 22);
            embedChart(workbook, overview,
                    PdfExportService.bars(report.charts().plannedFundingAndActual(), report.charts().incomeAvailability(),
                            space.currencyCode(), options), 3, 23, 12, 40);
        }
        Sheet items = sheet(workbook, "Budget Items", "Budget Items", space.name());
        String[] headers = {"Item", "Planned", "Planned Funding", "Confirmed Allocation", "Funding Gap", "Funding Status", "Gross", "Refunds", "Net", "Budget vs Actual Variance", "Unspent Allocated", "Unfunded Spending", "Usage", "Status"};
        int row = tableHeader(items, 5, headers);
        for (BudgetItemResponse item : report.totals().items()) {
            Row data = items.createRow(row++); text(data, 0, item.name()); money(data, 1, item.plannedAmount(), workbook);
            money(data, 2, item.funding().plannedFunding(), workbook); money(data, 3, item.funding().confirmedAllocatedAmount(), workbook);
            money(data, 4, item.funding().fundingGap(), workbook); text(data, 5, item.funding().status().name());
            money(data, 6, item.calculation().grossSpending(), workbook); money(data, 7, item.calculation().refunds(), workbook);
            money(data, 8, item.calculation().netSpending(), workbook); money(data, 9, item.calculation().remaining(), workbook);
            money(data, 10, item.funding().unspentAllocatedFunds(), workbook); money(data, 11, item.funding().unfundedSpending(), workbook);
            percent(data, 12, item.calculation().usagePercentage().divide(new BigDecimal("100")), workbook); text(data, 13, item.calculation().status());
        }
        int lastItemRow = row - 1;
        if (!report.totals().items().isEmpty()) {
            Row total = items.createRow(row);
            BigDecimal planned = sumMoney(report.totals().items().stream().map(BudgetItemResponse::plannedAmount).toList());
            BigDecimal net = sumMoney(report.totals().items().stream().map(value -> value.calculation().netSpending()).toList());
            totalText(total, 0, "Total", workbook);
            totalMoney(total, 1, planned, workbook);
            totalMoney(total, 2, sumMoney(report.totals().items().stream().map(value -> value.funding().plannedFunding()).toList()), workbook);
            totalMoney(total, 3, sumMoney(report.totals().items().stream().map(value -> value.funding().confirmedAllocatedAmount()).toList()), workbook);
            totalMoney(total, 4, sumMoney(report.totals().items().stream().map(value -> value.funding().fundingGap()).toList()), workbook);
            totalMoney(total, 6, sumMoney(report.totals().items().stream().map(value -> value.calculation().grossSpending()).toList()), workbook);
            totalMoney(total, 7, sumMoney(report.totals().items().stream().map(value -> value.calculation().refunds()).toList()), workbook);
            totalMoney(total, 8, net, workbook);
            totalMoney(total, 9, planned.subtract(net), workbook);
            totalMoney(total, 10, sumMoney(report.totals().items().stream().map(value -> value.funding().unspentAllocatedFunds()).toList()), workbook);
            totalMoney(total, 11, sumMoney(report.totals().items().stream().map(value -> value.funding().unfundedSpending()).toList()), workbook);
            totalPercent(total, 12, ratio(net, planned), workbook);
        }
        finishTable(items, 5, lastItemRow, headers.length);
        writeNamed(workbook, "Category Analysis", report.categories(), space);
        writeNamed(workbook, "Member Analysis", report.members(), space);
    }

    private void writeIncomeAndFunding(Workbook workbook, List<BudgetResponse> budgetValues,
                                       UUID actorId, SpaceResponse space, Set<UUID> includedItemIds) {
        List<IncomeResponse> incomes = new ArrayList<>();
        List<BudgetItemResponse> itemValues = new ArrayList<>();
        List<FundingAllocationResponse> funding = new ArrayList<>();
        java.util.Map<UUID, String> periods = new java.util.HashMap<>();
        for (BudgetResponse budget : budgetValues) {
            String period = "%04d-%02d".formatted(budget.year(), budget.month());
            List<IncomeResponse> budgetIncome = budgets.income(budget.id(), actorId);
            incomes.addAll(budgetIncome); budgetIncome.forEach(value -> periods.put(value.id(), period));
            List<BudgetItemResponse> periodItems = budget.summary().items().stream()
                    .filter(value -> includedItemIds == null || includedItemIds.contains(value.id())).toList();
            itemValues.addAll(periodItems); periodItems.forEach(value -> periods.put(value.id(), period));
        }
        long sourceRows = incomes.size() + (long) itemValues.size();
        requireWithinLimit(sourceRows, MAX_FINANCE_SOURCE_ROWS, "Excel income and budget-item detail");
        Set<UUID> incomeIds = incomes.stream().map(IncomeResponse::id).collect(java.util.stream.Collectors.toSet());
        Map<UUID, Integer> incomeOrder = new HashMap<>();
        for (int index = 0; index < incomes.size(); index++) incomeOrder.putIfAbsent(incomes.get(index).id(), index);
        ExcelExportOptions exportOptions = state(workbook).options;
        List<IncomeReceipt> receiptEntities;
        if (incomeIds.isEmpty()) {
            receiptEntities = List.of();
        } else {
            long receiptCount = incomeReceipts.countByIncomeEntryIdIn(incomeIds);
            requireWithinLimit(sourceRows + receiptCount, MAX_FINANCE_SOURCE_ROWS, "Excel finance detail");
            receiptEntities = incomeReceipts.findAllByIncomeEntryIdIn(incomeIds).stream()
                    .filter(value -> exportOptions.reportFilter().includesDate(value.getReceivedAt()
                            .atZone(safeZone(value.getTimeZone())).toLocalDate()))
                    .sorted(Comparator.comparingInt((IncomeReceipt value) -> incomeOrder.get(value.getIncomeEntryId()))
                            .thenComparing(IncomeReceipt::getReceivedAt)
                            .thenComparing(IncomeReceipt::getCreatedAt)
                            .thenComparing(IncomeReceipt::getId))
                    .toList();
            sourceRows += receiptEntities.size();
            requireWithinLimit(sourceRows, MAX_FINANCE_SOURCE_ROWS, "Excel finance detail");
        }
        Set<UUID> selectedReceiptIds = receiptEntities.stream().map(IncomeReceipt::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<IncomeReceiptDeduction> actualEntities;
        if (selectedReceiptIds.isEmpty()) {
            actualEntities = List.of();
        } else {
            long actualCount = actualDeductions.countByIncomeReceiptIdIn(selectedReceiptIds);
            requireWithinLimit(sourceRows + actualCount, MAX_FINANCE_SOURCE_ROWS, "Excel finance detail");
            actualEntities = actualDeductions.findAllByIncomeReceiptIdIn(selectedReceiptIds);
            sourceRows += actualEntities.size();
            requireWithinLimit(sourceRows, MAX_FINANCE_SOURCE_ROWS, "Excel finance detail");
        }
        Map<UUID, List<IncomeReceiptDeduction>> actualEntitiesByReceipt = actualEntities.stream()
                .collect(java.util.stream.Collectors.groupingBy(IncomeReceiptDeduction::getIncomeReceiptId));
        List<IncomeReceiptResponse> receiptValues = receiptEntities.stream()
                .map(value -> calculations.receipt(value,
                        actualEntitiesByReceipt.getOrDefault(value.getId(), List.of())))
                .toList();
        java.util.Map<UUID, String> incomeNames = incomes.stream().collect(java.util.stream.Collectors.toMap(
                IncomeResponse::id, IncomeResponse::sourceName, (left, right) -> left));
        java.util.Map<UUID, String> itemNames = itemValues.stream().collect(java.util.stream.Collectors.toMap(
                BudgetItemResponse::id, BudgetItemResponse::name, (left, right) -> left));
        if (!itemNames.isEmpty()) {
            long fundingCount = fundingAllocations.countByBudgetItemIdIn(itemNames.keySet());
            requireWithinLimit(sourceRows + fundingCount, MAX_FINANCE_SOURCE_ROWS, "Excel finance detail");
            List<BudgetFundingAllocation> fundingEntities = fundingAllocations
                    .findAllByBudgetItemIdInOrderByCreatedAtAsc(itemNames.keySet());
            sourceRows += fundingEntities.size();
            requireWithinLimit(sourceRows, MAX_FINANCE_SOURCE_ROWS, "Excel finance detail");
            funding.addAll(fundingEntities.stream().map(calculations::allocation).toList());
        }
        java.util.Map<UUID, List<IncomeReceiptResponse>> receiptsByIncome = receiptValues.stream()
                .collect(java.util.stream.Collectors.groupingBy(IncomeReceiptResponse::incomeEntryId));
        java.util.Map<UUID, List<FundingAllocationResponse>> fundingByIncome = funding.stream()
                .filter(value -> value.deletedAt() == null && value.incomeEntryId() != null)
                .collect(java.util.stream.Collectors.groupingBy(FundingAllocationResponse::incomeEntryId));
        java.util.Map<UUID, List<FundingAllocationResponse>> fundingByItem = funding.stream()
                .filter(value -> value.deletedAt() == null)
                .collect(java.util.stream.Collectors.groupingBy(FundingAllocationResponse::budgetItemId));
        Map<UUID, String> memberNames = memberNames(actorId, Set.of(space.id()), receiptValues.stream()
                .map(IncomeReceiptResponse::recordedByUserId).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet()));

        Sheet schedule = sheet(workbook, "Income Schedule", "Income Schedule", space.name());
        String[] scheduleHeaders = {"Period", "Income", "Expected Amount", "Expected Date", "Expected Time", "Time Zone",
                "Projected Gross", "Projected Net", "Received Amount", "Receipt Count", "Received Net",
                "Remaining Expected", "Planned Allocations", "Confirmed Allocations", "Unallocated Received", "Status",
                "Recurring", "Cancelled At", "Notes"};
        int scheduleRow = tableHeader(schedule, 5, scheduleHeaders);
        for (IncomeResponse value : incomes) {
            List<IncomeReceiptResponse> incomeReceipts = receiptsByIncome.getOrDefault(value.id(), List.of());
            List<FundingAllocationResponse> incomeFunding = fundingByIncome.getOrDefault(value.id(), List.of());
            Row data = schedule.createRow(scheduleRow++); text(data, 0, periods.get(value.id())); text(data, 1, value.sourceName());
            money(data, 2, value.expectedAmount(), workbook); date(data, 3, value.expectedDate(), workbook);
            time(data, 4, value.expectedTime(), workbook); text(data, 5, value.timeZone());
            money(data, 6, value.availability().projectedGrossIncome(), workbook); money(data, 7, value.availability().projectedNetIncome(), workbook);
            money(data, 8, value.availability().receivedGrossIncome(), workbook); number(data, 9, incomeReceipts.size(), null);
            money(data, 10, value.availability().receivedNetIncome(), workbook); money(data, 11, value.availability().remainingExpectedIncome(), workbook);
            money(data, 12, sumMoney(incomeFunding.stream().map(FundingAllocationResponse::plannedAmount).toList()), workbook);
            money(data, 13, value.availability().confirmedAllocatedAmount(), workbook); money(data, 14, value.availability().unallocatedReceivedIncome(), workbook);
            text(data, 15, value.availability().status().name()); text(data, 16, value.recurring() ? "Yes" : "No");
            timestamp(data, 17, value.cancelledAt(), value.timeZone(), workbook); text(data, 18, value.notes());
        }
        finishTable(schedule, 5, scheduleRow - 1, scheduleHeaders.length);

        List<IncomeDeductionResponse> plannedDeductions;
        if (incomeIds.isEmpty()) {
            plannedDeductions = List.of();
        } else {
            long plannedCount = incomeDeductions.countByIncomeEntryIdIn(incomeIds);
            requireWithinLimit(sourceRows + plannedCount, MAX_FINANCE_SOURCE_ROWS, "Excel finance detail");
            Map<UUID, BigDecimal> expectedGross = incomes.stream().collect(java.util.stream.Collectors.toMap(
                    IncomeResponse::id, IncomeResponse::expectedAmount, (left, right) -> left));
            List<IncomeDeduction> plannedEntities = incomeDeductions.findAllByIncomeEntryIdIn(incomeIds);
            sourceRows += plannedEntities.size();
            requireWithinLimit(sourceRows, MAX_FINANCE_SOURCE_ROWS, "Excel finance detail");
            plannedDeductions = plannedEntities.stream()
                    .map(value -> calculations.deduction(value, expectedGross.get(value.getIncomeEntryId())))
                    .sorted(Comparator.comparingInt((IncomeDeductionResponse value) -> incomeOrder.get(value.incomeEntryId()))
                            .thenComparingInt(IncomeDeductionResponse::sortOrder)
                            .thenComparing(IncomeDeductionResponse::createdAt)
                            .thenComparing(IncomeDeductionResponse::id))
                    .toList();
        }
        java.util.Map<UUID, String> deductionIncomeNames = incomes.stream()
                .collect(java.util.stream.Collectors.toMap(IncomeResponse::id, IncomeResponse::sourceName));
        Sheet deductionsSheet = sheet(workbook, "Planned Deductions", "Planned Income Deductions", space.name());
        String[] deductionHeaders = {"Period", "Income", "Deduction", "Method", "Percentage Rate",
                "Fixed Amount", "Projected Amount", "Legacy Tithe", "Created At (UTC)", "Updated At (UTC)",
                "State", "Notes"};
        int deductionRow = tableHeader(deductionsSheet, 5, deductionHeaders);
        for (IncomeDeductionResponse value : plannedDeductions) {
            Row data = deductionsSheet.createRow(deductionRow++);
            text(data, 0, periods.get(value.incomeEntryId()));
            text(data, 1, deductionIncomeNames.getOrDefault(value.incomeEntryId(), "Archived income"));
            text(data, 2, value.name()); text(data, 3, value.deductionType().name());
            if (value.percentageRate() == null) text(data, 4, ""); else percent(data, 4, value.percentageRate(), workbook);
            if (value.fixedAmount() == null) text(data, 5, ""); else money(data, 5, value.fixedAmount(), workbook);
            money(data, 6, value.projectedAmount(), workbook); text(data, 7, value.legacyTithe() ? "Yes" : "No");
            timestamp(data, 8, value.createdAt(), "UTC", workbook); timestamp(data, 9, value.updatedAt(), "UTC", workbook);
            text(data, 10, value.deletedAt() == null ? "ACTIVE" : "REMOVED"); text(data, 11, value.notes());
        }
        finishTable(deductionsSheet, 5, deductionRow - 1, deductionHeaders.length);

        Sheet receiptsSheet = sheet(workbook, "Income Receipts", "Income Receipts", space.name());
        String[] receiptHeaders = {"Period", "Income", "Gross Amount", "Actual Deductions", "Net Amount",
                "Received At", "Time Zone", "Recorded By", "Created At (UTC)", "Updated At (UTC)", "State", "Notes"};
        int receiptRow = tableHeader(receiptsSheet, 5, receiptHeaders);
        for (IncomeReceiptResponse value : receiptValues) {
            Row data = receiptsSheet.createRow(receiptRow++); text(data, 0, periods.get(value.incomeEntryId()));
            text(data, 1, incomeNames.getOrDefault(value.incomeEntryId(), "Archived income")); money(data, 2, value.amount(), workbook);
            money(data, 3, value.totalDeductions(), workbook); money(data, 4, value.netAmount(), workbook);
            timestamp(data, 5, value.receivedAt(), value.timeZone(), workbook); text(data, 6, value.timeZone());
            text(data, 7, memberNames.getOrDefault(value.recordedByUserId(), "Former member"));
            timestamp(data, 8, value.createdAt(), "UTC", workbook); timestamp(data, 9, value.updatedAt(), "UTC", workbook);
            text(data, 10, value.deletedAt() == null ? "ACTIVE" : "REVERSED"); text(data, 11, value.notes());
        }
        finishTable(receiptsSheet, 5, receiptRow - 1, receiptHeaders.length);

        java.util.Map<UUID, IncomeReceiptResponse> receiptMap = receiptValues.stream()
                .collect(java.util.stream.Collectors.toMap(IncomeReceiptResponse::id, value -> value));
        Map<UUID, Integer> receiptOrder = new HashMap<>();
        for (int index = 0; index < receiptValues.size(); index++) receiptOrder.putIfAbsent(receiptValues.get(index).id(), index);
        List<IncomeReceiptDeductionResponse> actualDeductionValues = receiptMap.isEmpty() ? List.of()
                : actualEntities.stream().map(calculations::receiptDeduction)
                .sorted(Comparator.comparingInt((IncomeReceiptDeductionResponse value) -> receiptOrder.get(value.incomeReceiptId()))
                        .thenComparing(IncomeReceiptDeductionResponse::createdAt)
                        .thenComparing(IncomeReceiptDeductionResponse::id))
                .toList();
        reserveWorkbookRows(workbook, receiptValues.size() + plannedDeductions.size()
                + actualDeductionValues.size() + (2L * incomes.size()) + itemValues.size()
                + (2L * funding.size()), "Excel finance detail");
        Sheet actualSheet = sheet(workbook, "Actual Deductions", "Actual Receipt Deductions", space.name());
        String[] actualHeaders = {"Period", "Income", "Received At", "Deduction", "Amount",
                "Created At (UTC)", "Updated At (UTC)", "State"};
        int actualRow = tableHeader(actualSheet, 5, actualHeaders);
        for (IncomeReceiptDeductionResponse value : actualDeductionValues) {
            IncomeReceiptResponse receipt = receiptMap.get(value.incomeReceiptId());
            Row data = actualSheet.createRow(actualRow++);
            text(data, 0, receipt == null ? "" : periods.get(receipt.incomeEntryId()));
            text(data, 1, receipt == null ? "Archived income" : incomeNames.getOrDefault(receipt.incomeEntryId(), "Archived income"));
            timestamp(data, 2, receipt == null ? null : receipt.receivedAt(), receipt == null ? "UTC" : receipt.timeZone(), workbook);
            text(data, 3, value.name()); money(data, 4, value.amount(), workbook);
            timestamp(data, 5, value.createdAt(), "UTC", workbook); timestamp(data, 6, value.updatedAt(), "UTC", workbook);
            text(data, 7, value.deletedAt() == null ? "ACTIVE" : "REVERSED");
        }
        finishTable(actualSheet, 5, actualRow - 1, actualHeaders.length);

        Sheet plan = sheet(workbook, "Funding Plan", "Income-to-budget Funding Plan", space.name());
        String[] planHeaders = {"Period", "Budget Item", "Income Source", "Source Type", "Planned Funding",
                "Confirmed Allocation", "State", "Allocated At", "Time Zone", "Created At (UTC)", "Updated At (UTC)",
                "Reversed At (UTC)", "Notes"};
        int planRow = tableHeader(plan, 5, planHeaders);
        for (FundingAllocationResponse value : funding) {
            Row data = plan.createRow(planRow++); text(data, 0, periods.get(value.budgetItemId()));
            text(data, 1, itemNames.getOrDefault(value.budgetItemId(), "Archived item"));
            text(data, 2, fundingSource(value, incomeNames));
            text(data, 3, value.sourceType().name()); money(data, 4, value.plannedAmount(), workbook);
            money(data, 5, value.confirmedAllocatedAmount(), workbook); text(data, 6, allocationState(value));
            timestamp(data, 7, value.allocatedAt(), value.timeZone(), workbook); text(data, 8, value.timeZone());
            timestamp(data, 9, value.createdAt(), "UTC", workbook); timestamp(data, 10, value.updatedAt(), "UTC", workbook);
            timestamp(data, 11, value.deletedAt(), "UTC", workbook); text(data, 12, value.notes());
        }
        finishTable(plan, 5, planRow - 1, planHeaders.length);

        Sheet confirmed = sheet(workbook, "Funding Allocations", "Confirmed Funding Allocations", space.name());
        String[] confirmedHeaders = {"Period", "Budget Item", "Income Source", "Source Type", "Confirmed Amount",
                "State", "Allocated At", "Time Zone", "Created At (UTC)", "Updated At (UTC)", "Reversed At (UTC)", "Notes"};
        int confirmedRow = tableHeader(confirmed, 5, confirmedHeaders);
        for (FundingAllocationResponse value : funding.stream().filter(entry -> entry.confirmedAllocatedAmount().signum() > 0).toList()) {
            Row data = confirmed.createRow(confirmedRow++); text(data, 0, periods.get(value.budgetItemId()));
            text(data, 1, itemNames.getOrDefault(value.budgetItemId(), "Archived item"));
            text(data, 2, fundingSource(value, incomeNames));
            text(data, 3, value.sourceType().name()); money(data, 4, value.confirmedAllocatedAmount(), workbook);
            text(data, 5, allocationState(value)); timestamp(data, 6, value.allocatedAt(), value.timeZone(), workbook); text(data, 7, value.timeZone());
            timestamp(data, 8, value.createdAt(), "UTC", workbook); timestamp(data, 9, value.updatedAt(), "UTC", workbook);
            timestamp(data, 10, value.deletedAt(), "UTC", workbook); text(data, 11, value.notes());
        }
        finishTable(confirmed, 5, confirmedRow - 1, confirmedHeaders.length);

        Sheet fundingStatus = sheet(workbook, "Budget Funding Status", "Budget Funding Status", space.name());
        String[] fundingHeaders = {"Period", "Budget Item", "Planned Amount", "Planned Funding", "Confirmed Allocation",
                "Funding Gap", "Funding Status", "Funding Sources", "Net Spending", "Budget vs Actual Variance",
                "Unspent Allocated", "Unfunded Spending", "Active Funding Links", "Reversed Funding Links"};
        int fundingRow = tableHeader(fundingStatus, 5, fundingHeaders);
        for (BudgetItemResponse value : itemValues) {
            List<FundingAllocationResponse> activeItemFunding = fundingByItem.getOrDefault(value.id(), List.of());
            Row data = fundingStatus.createRow(fundingRow++); text(data, 0, periods.get(value.id())); text(data, 1, value.name());
            money(data, 2, value.plannedAmount(), workbook); money(data, 3, value.funding().plannedFunding(), workbook);
            money(data, 4, value.funding().confirmedAllocatedAmount(), workbook); money(data, 5, value.funding().fundingGap(), workbook);
            text(data, 6, value.funding().status().name());
            text(data, 7, activeItemFunding.stream().map(entry -> fundingSource(entry, incomeNames)).distinct()
                    .collect(java.util.stream.Collectors.joining(", ")));
            money(data, 8, value.calculation().netSpending(), workbook); money(data, 9, value.calculation().remaining(), workbook);
            money(data, 10, value.funding().unspentAllocatedFunds(), workbook); money(data, 11, value.funding().unfundedSpending(), workbook);
            number(data, 12, activeItemFunding.size(), null);
            number(data, 13, funding.stream().filter(entry -> entry.budgetItemId().equals(value.id()) && entry.deletedAt() != null).count(), null);
        }
        int lastFundingRow = fundingRow - 1;
        if (!itemValues.isEmpty()) {
            Row total = fundingStatus.createRow(fundingRow);
            totalText(total, 0, "Total", workbook);
            totalMoney(total, 2, sumMoney(itemValues.stream().map(BudgetItemResponse::plannedAmount).toList()), workbook);
            totalMoney(total, 3, sumMoney(itemValues.stream().map(value -> value.funding().plannedFunding()).toList()), workbook);
            totalMoney(total, 4, sumMoney(itemValues.stream().map(value -> value.funding().confirmedAllocatedAmount()).toList()), workbook);
            totalMoney(total, 5, sumMoney(itemValues.stream().map(value -> value.funding().fundingGap()).toList()), workbook);
            totalMoney(total, 8, sumMoney(itemValues.stream().map(value -> value.calculation().netSpending()).toList()), workbook);
            totalMoney(total, 9, sumMoney(itemValues.stream().map(value -> value.calculation().remaining()).toList()), workbook);
            totalMoney(total, 10, sumMoney(itemValues.stream().map(value -> value.funding().unspentAllocatedFunds()).toList()), workbook);
            totalMoney(total, 11, sumMoney(itemValues.stream().map(value -> value.funding().unfundedSpending()).toList()), workbook);
            totalNumber(total, 12, fundingByItem.values().stream().mapToInt(List::size).sum(), workbook);
            totalNumber(total, 13, funding.stream().filter(entry -> entry.deletedAt() != null).count(), workbook);
        }
        finishTable(fundingStatus, 5, lastFundingRow, fundingHeaders.length);

        Sheet availability = sheet(workbook, "Income Availability", "Income Availability", space.name());
        String[] availabilityHeaders = {"Period", "Income", "Projected Gross", "Projected Deductions", "Projected Net",
                "Received Gross", "Realized Deductions", "Received Net", "Planned Allocations",
                "Confirmed Allocations", "Unallocated Received", "Remaining Expected", "Status"};
        int availabilityRow = tableHeader(availability, 5, availabilityHeaders);
        for (IncomeResponse value : incomes) {
            List<FundingAllocationResponse> incomeFunding = fundingByIncome.getOrDefault(value.id(), List.of());
            Row data = availability.createRow(availabilityRow++); text(data, 0, periods.get(value.id())); text(data, 1, value.sourceName());
            money(data, 2, value.availability().projectedGrossIncome(), workbook); money(data, 3, value.availability().projectedDeductions(), workbook);
            money(data, 4, value.availability().projectedNetIncome(), workbook); money(data, 5, value.availability().receivedGrossIncome(), workbook);
            money(data, 6, value.availability().realizedDeductions(), workbook); money(data, 7, value.availability().receivedNetIncome(), workbook);
            money(data, 8, sumMoney(incomeFunding.stream().map(FundingAllocationResponse::plannedAmount).toList()), workbook);
            money(data, 9, value.availability().confirmedAllocatedAmount(), workbook); money(data, 10, value.availability().unallocatedReceivedIncome(), workbook);
            money(data, 11, value.availability().remainingExpectedIncome(), workbook); text(data, 12, value.availability().status().name());
        }
        int lastAvailabilityRow = availabilityRow - 1;
        if (!incomes.isEmpty()) {
            Row total = availability.createRow(availabilityRow);
            totalText(total, 0, "Total", workbook);
            totalMoney(total, 2, sumMoney(incomes.stream().map(value -> value.availability().projectedGrossIncome()).toList()), workbook);
            totalMoney(total, 3, sumMoney(incomes.stream().map(value -> value.availability().projectedDeductions()).toList()), workbook);
            totalMoney(total, 4, sumMoney(incomes.stream().map(value -> value.availability().projectedNetIncome()).toList()), workbook);
            totalMoney(total, 5, sumMoney(incomes.stream().map(value -> value.availability().receivedGrossIncome()).toList()), workbook);
            totalMoney(total, 6, sumMoney(incomes.stream().map(value -> value.availability().realizedDeductions()).toList()), workbook);
            totalMoney(total, 7, sumMoney(incomes.stream().map(value -> value.availability().receivedNetIncome()).toList()), workbook);
            totalMoney(total, 8, sumMoney(fundingByIncome.values().stream().flatMap(List::stream)
                    .map(FundingAllocationResponse::plannedAmount).toList()), workbook);
            totalMoney(total, 9, sumMoney(incomes.stream().map(value -> value.availability().confirmedAllocatedAmount()).toList()), workbook);
            totalMoney(total, 10, sumMoney(incomes.stream().map(value -> value.availability().unallocatedReceivedIncome()).toList()), workbook);
            totalMoney(total, 11, sumMoney(incomes.stream().map(value -> value.availability().remainingExpectedIncome()).toList()), workbook);
        }
        finishTable(availability, 5, lastAvailabilityRow, availabilityHeaders.length);
    }

    private void writeSpending(Workbook workbook, List<SpendingResponse> values, SpaceResponse space, UUID actorId) {
        requireWithinLimit(values.size(), MAX_LEDGER_ROWS, "Excel spending ledger");
        reserveWorkbookRows(workbook, values.size(), "Excel spending ledger");
        Sheet sheet = sheet(workbook, "Spending Ledger", "Spending Ledger", space == null ? "All accessible spaces" : space.name());
        Set<UUID> spaceIds = values.stream().map(SpendingResponse::spaceId).collect(java.util.stream.Collectors.toSet());
        Set<UUID> requestedItemIds = values.stream().map(SpendingResponse::budgetItemId)
                .collect(java.util.stream.Collectors.toSet());
        Map<UUID, String> itemNames = budgetItemNames(actorId, spaceIds, requestedItemIds);
        Map<UUID, String> memberNames = memberNames(actorId, spaceIds, values.stream()
                .map(SpendingResponse::spentByUserId).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet()));
        String[] headers = {"Date", "Time", "Type", "Title", "Amount", "Merchant", "Payment Method", "Budget Item", "Spender", "Notes"};
        int row = tableHeader(sheet, 5, headers);
        for (SpendingResponse entry : values) {
            Row data = sheet.createRow(row++); date(data, 0, entry.date(), workbook); time(data, 1, entry.time(), workbook);
            text(data, 2, entry.transactionType().name()); text(data, 3, entry.title()); money(data, 4, entry.amount(), workbook);
            text(data, 5, entry.merchant()); text(data, 6, entry.paymentMethod());
            text(data, 7, itemNames.getOrDefault(entry.budgetItemId(), "Archived budget item"));
            text(data, 8, entry.spentByUserId() == null ? "Household" : memberNames.getOrDefault(entry.spentByUserId(), "Former member"));
            text(data, 9, entry.notes());
        }
        int lastDataRow = row - 1;
        if (!values.isEmpty()) {
            BigDecimal grossExpenses = sumMoney(values.stream()
                    .filter(value -> value.transactionType() == TransactionType.EXPENSE)
                    .map(SpendingResponse::amount).toList());
            BigDecimal refunds = sumMoney(values.stream()
                    .filter(value -> value.transactionType() == TransactionType.REFUND)
                    .map(SpendingResponse::amount).toList());
            Row expenseTotal = sheet.createRow(row++);
            totalText(expenseTotal, 3, "Gross expenses", workbook); totalMoney(expenseTotal, 4, grossExpenses, workbook);
            Row refundTotal = sheet.createRow(row++);
            totalText(refundTotal, 3, "Refunds", workbook); totalMoney(refundTotal, 4, refunds, workbook);
            Row netTotal = sheet.createRow(row);
            totalText(netTotal, 3, "Net spending", workbook); totalMoney(netTotal, 4, grossExpenses.subtract(refunds), workbook);
        }
        finishTable(sheet, 5, lastDataRow, headers.length);
    }

    private void writePeriodOverview(Workbook workbook, String title, PeriodTotals totals, SpaceResponse space) {
        Sheet overview = sheet(workbook, "Overview", title, space.name());
        keyValue(overview, 5, "Projected gross income", totals.projectedGrossIncome(), workbook);
        keyValue(overview, 6, "Projected net income", totals.projectedNetIncome(), workbook);
        keyValue(overview, 7, "Received gross income", totals.receivedGrossIncome(), workbook);
        keyValue(overview, 8, "Received net income", totals.receivedNetIncome(), workbook);
        keyValue(overview, 9, "Outstanding expected income", totals.outstandingExpectedIncome(), workbook);
        keyValue(overview, 10, "Confirmed allocated income", totals.confirmedAllocatedIncome(), workbook);
        keyValue(overview, 11, "Unallocated received income", totals.unallocatedReceivedIncome(), workbook);
        keyValue(overview, 12, "Planned budget", totals.plannedSpending(), workbook);
        keyValue(overview, 13, "Planned funding", totals.plannedFunding(), workbook);
        keyValue(overview, 14, "Confirmed funded budget", totals.confirmedFundedBudget(), workbook);
        keyValue(overview, 15, "Funding gap", totals.fundingGap(), workbook);
        keyValue(overview, 16, "Unspent allocated funds", totals.unspentAllocatedFunds(), workbook);
        keyValue(overview, 17, "Unfunded spending", totals.unfundedSpending(), workbook);
        keyValue(overview, 18, "Net spending", totals.netSpending(), workbook);
        keyValue(overview, 19, "Remaining cash", totals.remaining(), workbook);
        keyValue(overview, 20, "Budget vs actual variance", totals.plannedSpending().subtract(totals.netSpending()), workbook);
        Row fundingCounts = overview.createRow(21); text(fundingCounts, 0, "Funding item counts");
        text(fundingCounts, 1, "Fully %d; partially %d; unfunded %d".formatted(
                totals.fullyFundedItemCount(), totals.partiallyFundedItemCount(), totals.unfundedItemCount()));
        Row incomeCounts = overview.createRow(22); text(incomeCounts, 0, "Income status counts");
        text(incomeCounts, 1, "Scheduled %d; due %d; late %d; partially received %d; received %d; cancelled %d".formatted(
                totals.incomeStatuses().scheduled(), totals.incomeStatuses().due(), totals.incomeStatuses().late(),
                totals.incomeStatuses().partiallyReceived(), totals.incomeStatuses().received(),
                totals.incomeStatuses().cancelled()));
        Row historyCounts = overview.createRow(23); text(historyCounts, 0, "Record counts");
        text(historyCounts, 1, "Receipts %d; active funding links %d; reversed funding links %d".formatted(
                totals.receiptCount(), totals.fundingAllocationCount(), totals.reversedFundingAllocationCount()));
    }

    private void writePeriodRows(Workbook workbook, String name, String title, List<PeriodRow> rows,
                                 PeriodTotals periodTotals, SpaceResponse space) {
        reserveWorkbookRows(workbook, rows.size(), "Excel period summary");
        Sheet sheet = sheet(workbook, name, title, space.name());
        String[] headers = {"Period", "Projected Gross", "Projected Net", "Received Gross", "Received Net",
                "Outstanding Expected", "Confirmed Allocated Income", "Unallocated Received", "Planned Budget",
                "Planned Funding", "Confirmed Funded", "Funding Gap", "Unspent Allocated", "Unfunded Spending",
                "Gross Spending", "Refunds", "Net Spending", "Budget vs Actual Variance", "Unbudgeted Spending", "Remaining", "Spending Rate",
                "Fully Funded Items", "Partially Funded Items", "Unfunded Items", "Scheduled Income", "Due Income",
                "Late Income", "Partially Received Income", "Received Income", "Cancelled Income", "Receipts",
                "Active Funding Links", "Reversed Funding Links", "Status"};
        int row = tableHeader(sheet, 5, headers);
        for (PeriodRow value : rows) {
            writePeriodRow(sheet.createRow(row++), value.label(), value.totals(), workbook, false);
        }
        int lastDataRow = row - 1;
        writePeriodRow(sheet.createRow(row), "Total", periodTotals, workbook, true);
        finishTable(sheet, 5, lastDataRow, headers.length);
    }

    private void writePeriodRow(Row data, String label, PeriodTotals totals, Workbook workbook, boolean total) {
        textValue(data, 0, label, workbook, total);
        moneyValue(data, 1, totals.projectedGrossIncome(), workbook, total);
        moneyValue(data, 2, totals.projectedNetIncome(), workbook, total);
        moneyValue(data, 3, totals.receivedGrossIncome(), workbook, total);
        moneyValue(data, 4, totals.receivedNetIncome(), workbook, total);
        moneyValue(data, 5, totals.outstandingExpectedIncome(), workbook, total);
        moneyValue(data, 6, totals.confirmedAllocatedIncome(), workbook, total);
        moneyValue(data, 7, totals.unallocatedReceivedIncome(), workbook, total);
        moneyValue(data, 8, totals.plannedSpending(), workbook, total);
        moneyValue(data, 9, totals.plannedFunding(), workbook, total);
        moneyValue(data, 10, totals.confirmedFundedBudget(), workbook, total);
        moneyValue(data, 11, totals.fundingGap(), workbook, total);
        moneyValue(data, 12, totals.unspentAllocatedFunds(), workbook, total);
        moneyValue(data, 13, totals.unfundedSpending(), workbook, total);
        moneyValue(data, 14, totals.grossSpending(), workbook, total);
        moneyValue(data, 15, totals.refunds(), workbook, total);
        moneyValue(data, 16, totals.netSpending(), workbook, total);
        moneyValue(data, 17, totals.plannedSpending().subtract(totals.netSpending()), workbook, total);
        moneyValue(data, 18, totals.unbudgetedSpending(), workbook, total);
        moneyValue(data, 19, totals.remaining(), workbook, total);
        percentValue(data, 20, totals.spendingRate().divide(new BigDecimal("100")), workbook, total);
        numberValue(data, 21, totals.fullyFundedItemCount(), workbook, total);
        numberValue(data, 22, totals.partiallyFundedItemCount(), workbook, total);
        numberValue(data, 23, totals.unfundedItemCount(), workbook, total);
        numberValue(data, 24, totals.incomeStatuses().scheduled(), workbook, total);
        numberValue(data, 25, totals.incomeStatuses().due(), workbook, total);
        numberValue(data, 26, totals.incomeStatuses().late(), workbook, total);
        numberValue(data, 27, totals.incomeStatuses().partiallyReceived(), workbook, total);
        numberValue(data, 28, totals.incomeStatuses().received(), workbook, total);
        numberValue(data, 29, totals.incomeStatuses().cancelled(), workbook, total);
        numberValue(data, 30, totals.receiptCount(), workbook, total);
        numberValue(data, 31, totals.fundingAllocationCount(), workbook, total);
        numberValue(data, 32, totals.reversedFundingAllocationCount(), workbook, total);
        textValue(data, 33, totals.status(), workbook, total);
    }

    private BigDecimal sumMoney(List<BigDecimal> values) {
        return values.stream().filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String fundingSource(FundingAllocationResponse value, java.util.Map<UUID, String> incomeNames) {
        if (value.incomeEntryId() != null) return incomeNames.getOrDefault(value.incomeEntryId(), "Archived income");
        return switch (value.sourceType()) {
            case EXTERNAL_FUNDS -> "External funds";
            case UNASSIGNED_FUNDS -> "Unassigned funds";
            case ROLLOVER_FUNDS -> "Rollover funds";
            case INCOME_ENTRY -> "Archived income";
        };
    }

    private String allocationState(FundingAllocationResponse value) {
        if (value.deletedAt() != null) return "REVERSED";
        return value.confirmedAllocatedAmount().signum() > 0 ? "CONFIRMED" : "PLANNED";
    }

    private void writeNamed(Workbook workbook, String name, List<solutions.shapeit.wethrive.report.dto.ReportDtos.NamedAmount> values, SpaceResponse space) {
        reserveWorkbookRows(workbook, values.size(), "Excel summary analysis");
        Sheet sheet = sheet(workbook, name, name, space.name());
        String[] headers = {"Name", "Net Amount"};
        int row = tableHeader(sheet, 5, headers);
        for (var value : values) { Row data = sheet.createRow(row++); text(data, 0, value.name()); money(data, 1, value.amount(), workbook); }
        int lastDataRow = row - 1;
        if (!values.isEmpty()) {
            Row total = sheet.createRow(row);
            totalText(total, 0, "Total", workbook);
            totalMoney(total, 1, sumMoney(values.stream().map(value -> value.amount()).toList()), workbook);
        }
        finishTable(sheet, 5, lastDataRow, headers.length);
    }

    private Map<UUID, String> memberNames(UUID actorId, Set<UUID> spaceIds, Set<UUID> userIds) {
        Map<UUID, String> result = new HashMap<>();
        if (memberships == null || users == null || spaceIds.isEmpty()) return result;
        if (userIds.isEmpty()) return result;
        List<solutions.shapeit.wethrive.space.entity.SpaceMembership> memberRows = memberships
                .findAllBySpaceIdInAndUserIdInAndDeletedAtIsNull(spaceIds, userIds);
        Map<UUID, String> displayNames = users.findAllById(memberRows.stream()
                        .map(solutions.shapeit.wethrive.space.entity.SpaceMembership::getUserId).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(
                        solutions.shapeit.wethrive.identity.entity.AppUser::getId,
                        solutions.shapeit.wethrive.identity.entity.AppUser::getDisplayName));
        memberRows.forEach(member -> result.put(member.getUserId(),
                displayNames.getOrDefault(member.getUserId(), "Former member")));
        return result;
    }

    private Map<UUID, String> budgetItemNames(UUID actorId, Set<UUID> spaceIds, Set<UUID> requestedIds) {
        Map<UUID, String> result = new HashMap<>();
        if (budgetItems == null || spaceIds.isEmpty() || requestedIds.isEmpty()) return result;
        budgetItems.findAllByIdInAndSpaceIdInAndDeletedAtIsNull(requestedIds, spaceIds)
                .forEach(item -> result.put(item.getId(), item.getName()));
        return result;
    }

    private List<BudgetResponse> selectBudgets(List<BudgetResponse> candidates, ExcelExportOptions options) {
        if (options.scope() != ExportScope.SELECTED_BUDGETS) return candidates;
        Set<UUID> candidateIds = candidates.stream().map(BudgetResponse::id)
                .collect(java.util.stream.Collectors.toSet());
        if (!candidateIds.containsAll(options.budgetIds())) {
            throw solutions.shapeit.wethrive.common.web.ApiException.badRequest(
                    "One or more selected budgets are not available in this export scope");
        }
        Set<UUID> selected = Set.copyOf(options.budgetIds());
        return candidates.stream().filter(value -> selected.contains(value.id())).toList();
    }

    private MonthlyReport monthlyReport(UUID spaceId, int year, int month, UUID actorId,
                                        ExcelExportOptions options) {
        return options.hasReportSelection()
                ? reports.monthly(spaceId, year, month, actorId, options.reportFilter())
                : reports.monthly(spaceId, year, month, actorId);
    }

    private QuarterlyReport quarterlyReport(UUID spaceId, int year, int quarter, UUID actorId,
                                            ExcelExportOptions options) {
        return options.hasReportSelection()
                ? reports.quarterly(spaceId, year, quarter, actorId, options.reportFilter())
                : reports.quarterly(spaceId, year, quarter, actorId);
    }

    private AnnualReport annualReport(UUID spaceId, int year, UUID actorId, ExcelExportOptions options) {
        return options.hasReportSelection()
                ? reports.annual(spaceId, year, actorId, options.reportFilter())
                : reports.annual(spaceId, year, actorId);
    }

    /** Null means no category restriction; an empty set means the category matched no items. */
    private Set<UUID> reportItemIds(MonthlyReport report, ExcelExportOptions options) {
        if (options.category() == null) return null;
        return report.totals().items().stream().map(BudgetItemResponse::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Set<UUID> reportItemIds(UUID spaceId, List<BudgetResponse> periodBudgets, UUID actorId,
                                    ExcelExportOptions options) {
        if (options.category() == null) return null;
        Set<UUID> result = new java.util.LinkedHashSet<>();
        for (BudgetResponse budget : periodBudgets) {
            result.addAll(monthlyReport(spaceId, budget.year(), budget.month(), actorId, options)
                    .totals().items().stream().map(BudgetItemResponse::id).toList());
        }
        return Set.copyOf(result);
    }

    private Set<UUID> periodItemIds(List<BudgetResponse> periodBudgets) {
        return periodBudgets.stream().flatMap(value -> value.summary().items().stream())
                .map(BudgetItemResponse::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private List<SpendingResponse> filterSpending(List<SpendingResponse> values, ExcelExportOptions options,
                                                  Set<UUID> includedItemIds) {
        return values.stream()
                .filter(value -> includedItemIds == null || includedItemIds.contains(value.budgetItemId()))
                .filter(value -> options.reportFilter().includesDate(value.date())).toList();
    }

    private List<SpendingResponse> pageAll(UUID actorId, UUID spaceId) {
        return pageAll(actorId, spaceId, null, null);
    }

    private List<SpendingResponse> pageAll(UUID actorId, UUID spaceId, Instant from, Instant to) {
        return pageAll(actorId, spaceId, from, to, MAX_LEDGER_ROWS);
    }

    private List<SpendingResponse> pageAll(UUID actorId, UUID spaceId, Instant from, Instant to, int rowLimit) {
        List<SpendingResponse> result = new ArrayList<>();
        for (int pageNumber = 0; ; pageNumber++) {
            Page<SpendingResponse> page = spending.list(actorId, spaceId, from, to, pageNumber, PAGE_SIZE);
            if (page.getTotalElements() > rowLimit
                    || result.size() + (long) page.getNumberOfElements() > rowLimit) {
                throw exportLimit("Excel spending ledger", MAX_LEDGER_ROWS);
            }
            result.addAll(page.getContent());
            if (!page.hasNext()) break;
            if (result.size() == rowLimit) {
                throw exportLimit("Excel spending ledger", MAX_LEDGER_ROWS);
            }
        }
        return result;
    }

    private void reserveWorkbookRows(Workbook workbook, long rows, String label) {
        WorkbookState workbookState = state(workbook);
        requireWithinLimit(workbookState.dataRows + rows, MAX_WORKBOOK_DATA_ROWS, label);
        workbookState.dataRows += rows;
    }

    private static void requireWithinLimit(long rows, int limit, String label) {
        if (rows > limit) throw exportLimit(label, limit);
    }

    private void preflightSpaceSources(Set<UUID> spaceIds) {
        if (spaceIds.isEmpty()) return;
        long rows = 0;
        if (budgetMonths != null) rows = addWithinLimit(rows,
                budgetMonths.countBySpaceIdInAndDeletedAtIsNull(spaceIds), "Excel source data");
        if (budgetItems != null) rows = addWithinLimit(rows,
                budgetItems.countBySpaceIdInAndDeletedAtIsNull(spaceIds), "Excel source data");
        if (incomeEntries != null) rows = addWithinLimit(rows,
                incomeEntries.countBySpaceIdInAndDeletedAtIsNull(spaceIds), "Excel source data");
        if (spendingEntries != null) rows = addWithinLimit(rows,
                spendingEntries.countBySpaceIdInAndDeletedAtIsNull(spaceIds), "Excel source data");
        if (incomeReceipts != null) rows = addWithinLimit(rows,
                incomeReceipts.countBySpaceIdIn(spaceIds), "Excel source data");
        if (incomeDeductions != null) rows = addWithinLimit(rows,
                incomeDeductions.countBySpaceIdIn(spaceIds), "Excel source data");
        if (actualDeductions != null) rows = addWithinLimit(rows,
                actualDeductions.countBySpaceIdIn(spaceIds), "Excel source data");
        if (fundingAllocations != null) addWithinLimit(rows,
                fundingAllocations.countBySpaceIdIn(spaceIds), "Excel source data");
    }

    private void preflightFinanceDetail(List<BudgetResponse> budgetValues) {
        List<UUID> budgetIds = budgetValues.stream().map(BudgetResponse::id).toList();
        if (budgetIds.isEmpty() || incomeEntries == null || budgetItems == null) return;
        requireWithinLimit(incomeEntries.countByBudgetMonthIdInAndDeletedAtIsNull(budgetIds)
                        + budgetItems.countByBudgetMonthIdInAndDeletedAtIsNull(budgetIds),
                MAX_FINANCE_SOURCE_ROWS, "Excel income and budget-item detail");
    }

    private static long addWithinLimit(long current, long additional, String label) {
        if (additional < 0 || current > MAX_WORKBOOK_DATA_ROWS - additional) {
            throw solutions.shapeit.wethrive.common.web.ApiException.badRequest(label + " exceeds the "
                    + String.format(Locale.ROOT, "%,d", MAX_WORKBOOK_DATA_ROWS)
                    + "-row conservative whole-space limit; use local CSV/encrypted backup or a smaller space");
        }
        return current + additional;
    }

    private static solutions.shapeit.wethrive.common.web.ApiException exportLimit(String label, int limit) {
        return solutions.shapeit.wethrive.common.web.ApiException.badRequest(
                label + " exceeds the " + String.format(Locale.ROOT, "%,d", limit)
                        + "-row export limit; narrow the space, period, member, category, or date filters");
    }

    private byte[] workbook(String title, WorkbookContent content) {
        return workbook(title, ExcelExportOptions.defaults(), content);
    }

    private byte[] workbook(String title, ExcelExportOptions options, WorkbookContent content) {
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        try (workbook; BoundedByteArrayOutputStream output = new BoundedByteArrayOutputStream(MAX_XLSX_BYTES)) {
            workbook.setCompressTempFiles(true);
            state(workbook).options = options;
            content.write(workbook);
            Sheet info = sheet(workbook, "Export Information", title, properties.branding().name());
            info.setColumnWidth(0, 24 * 256);
            info.setColumnWidth(1, 54 * 256);
            Row generated = info.createRow(5); text(generated, 0, "Generated at");
            timestamp(generated, 1, Instant.now(clock), properties.branding().defaultTimeZone(), workbook);
            Row timeZone = info.createRow(6); text(timeZone, 0, "Time zone");
            text(timeZone, 1, properties.branding().defaultTimeZone());
            Row currency = info.createRow(7); text(currency, 0, "Currency");
            text(currency, 1, currency(workbook));
            Row application = info.createRow(8); text(application, 0, "Application");
            text(application, 1, properties.branding().name());
            Row parent = info.createRow(9); text(parent, 0, "Parent company");
            text(parent, 1, properties.branding().parentCompany());
            Row theme = info.createRow(10); text(theme, 0, "Export theme");
            text(theme, 1, options.theme().name().replace('_', ' '));
            Row settings = info.createRow(11); text(settings, 0, "Export contents");
            text(settings, 1, (options.includeDetailedLedgers() ? "Detailed ledgers included" : "Summary only")
                    + "; " + (options.includeCharts() ? "charts included" : "charts omitted"));
            Row scope = info.createRow(12); text(scope, 0, "Export scope");
            text(scope, 1, options.scope().name().replace('_', ' '));
            Row selection = info.createRow(13); text(selection, 0, "Selected budgets");
            text(selection, 1, options.scope() == ExportScope.SELECTED_BUDGETS
                    ? options.budgetIds().size() + " selected" : "Defined by report route");
            Row filters = info.createRow(14); text(filters, 0, "Report filters");
            text(filters, 1, exportFilterDescription(options));
            Row applicationVersion = info.createRow(15); text(applicationVersion, 0, "Application version");
            text(applicationVersion, 1, applicationVersion());
            workbook.write(output);
            return output.toByteArray();
        } catch (ExportSizeLimitException ex) {
            throw solutions.shapeit.wethrive.common.web.ApiException.badRequest(ex.getMessage());
        } catch (IOException ex) {
            if (causedBy(ex, ExportSizeLimitException.class)) {
                throw solutions.shapeit.wethrive.common.web.ApiException.badRequest(
                        "Excel output exceeds the 64 MiB response limit; narrow the export scope or filters");
            }
            throw new IllegalStateException("Unable to generate Excel export", ex);
        } finally {
            synchronized (workbookStates) {
                workbookStates.remove(workbook);
            }
        }
    }

    private Sheet sheet(Workbook workbook, String name, String title, String space) {
        String safe = org.apache.poi.ss.util.WorkbookUtil.createSafeSheetName(name);
        if (workbook.getSheet(safe) != null) safe = safe.substring(0, Math.min(25, safe.length())) + " " + (workbook.getNumberOfSheets() + 1);
        Sheet sheet = workbook.createSheet(safe);
        sheet.setDisplayGridlines(false);
        // Key/value overview sheets do not pass through finishTable, so give
        // their labels and values explicit, readable widths. Table sheets
        // replace these defaults with their bounded per-column widths.
        sheet.setColumnWidth(0, 36 * 256);
        sheet.setColumnWidth(1, 28 * 256);
        Row brand = sheet.createRow(0); Cell cell = brand.createCell(0); cell.setCellValue(properties.branding().name());
        cell.setCellStyle(cachedStyle(workbook, "brand", style -> {
            Font font = workbook.createFont();
            font.setBold(true); font.setFontHeightInPoints((short) 20); setFontColour(font, accent(workbook));
            style.setFont(font);
        }));
        brand.setHeightInPoints(30);
        text(sheet.createRow(1), 0, "Powered by " + properties.branding().parentCompany());
        text(sheet.createRow(2), 0, title); text(sheet.createRow(3), 0, space);
        text(sheet.createRow(4), 0, properties.branding().tagline());
        addLogo(workbook, sheet);
        return sheet;
    }

    private int tableHeader(Sheet sheet, int rowNumber, String[] headers) {
        Row row = sheet.createRow(rowNumber);
        Workbook workbook = sheet.getWorkbook();
        CellStyle style = cachedStyle(workbook, "header", value -> {
            Font font = workbook.createFont(); font.setBold(true);
            int[] colour = accent(workbook);
            boolean light = luminance(colour) > 0.54;
            if (font instanceof XSSFFont xssfFont) {
                xssfFont.setColor(new XSSFColor(new byte[]{(byte) (light ? 0 : 255), (byte) (light ? 0 : 255),
                        (byte) (light ? 0 : 255)}, null));
            } else font.setColor(light ? IndexedColors.BLACK.getIndex() : IndexedColors.WHITE.getIndex());
            value.setFont(font);
            if (value instanceof XSSFCellStyle xssfStyle) {
                xssfStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte) colour[0], (byte) colour[1],
                        (byte) colour[2]}, null));
            } else value.setFillForegroundColor(IndexedColors.GREY_80_PERCENT.getIndex());
            value.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        });
        for (int i = 0; i < headers.length; i++) { Cell cell = row.createCell(i); cell.setCellValue(headers[i]); cell.setCellStyle(style); }
        sheet.createFreezePane(0, rowNumber + 1);
        return rowNumber + 1;
    }

    private void finishTable(Sheet sheet, int firstRow, int lastRow, int columns) {
        if (lastRow >= firstRow) sheet.setAutoFilter(new CellRangeAddress(firstRow, lastRow, 0, columns - 1));
        for (int column = 0; column < columns; column++) sheet.setColumnWidth(column, Math.min(column == 9 ? 12000 : 6500, 15000));
        sheet.setFitToPage(true);
        sheet.getPrintSetup().setFitWidth((short) 1);
        sheet.getPrintSetup().setFitHeight((short) 0);
        sheet.getPrintSetup().setLandscape(columns > 6);
        sheet.setRepeatingRows(new CellRangeAddress(firstRow, firstRow, -1, -1));
        sheet.setMargin(Sheet.LeftMargin, 0.35); sheet.setMargin(Sheet.RightMargin, 0.35);
        sheet.setMargin(Sheet.TopMargin, 0.55); sheet.setMargin(Sheet.BottomMargin, 0.55);
        int finalRow = Math.max(firstRow, lastRow);
        sheet.getWorkbook().setPrintArea(sheet.getWorkbook().getSheetIndex(sheet), 0, Math.max(0, columns - 1), 0, finalRow);
    }

    private void addLogo(Workbook workbook, Sheet sheet) {
        WorkbookState workbookState = state(workbook);
        try {
            if (workbookState.logoPictureIndex == null) {
                try (InputStream input = ExcelExportService.class.getResourceAsStream("/brand/shape-it/wordmark-black.png")) {
                    if (input == null) return;
                    workbookState.logoPictureIndex = workbook.addPicture(input.readAllBytes(), Workbook.PICTURE_TYPE_PNG);
                }
            }
            ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
            anchor.setCol1(3); anchor.setRow1(0); anchor.setCol2(6); anchor.setRow2(4);
            anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
            sheet.createDrawingPatriarch().createPicture(anchor, workbookState.logoPictureIndex);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to embed export branding", exception);
        }
    }

    private void embedChart(Workbook workbook, Sheet sheet, BufferedImage image,
                            int firstColumn, int firstRow, int lastColumn, int lastRow) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            int pictureIndex = workbook.addPicture(output.toByteArray(), Workbook.PICTURE_TYPE_PNG);
            ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
            anchor.setCol1(firstColumn); anchor.setRow1(firstRow); anchor.setCol2(lastColumn); anchor.setRow2(lastRow);
            anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
            sheet.createDrawingPatriarch().createPicture(anchor, pictureIndex);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to embed export chart", exception);
        }
    }

    private int[] accent(Workbook workbook) {
        return accent(state(workbook).options);
    }

    static int[] accent(ExcelExportOptions options) {
        if (options.theme() == ExportTheme.CUSTOM) {
            String value = options.accent().substring(1);
            return new int[]{Integer.parseInt(value.substring(0, 2), 16), Integer.parseInt(value.substring(2, 4), 16),
                    Integer.parseInt(value.substring(4, 6), 16)};
        }
        return switch (options.theme()) {
            // Apache POI's DARK_GREEN token is the exact title/header colour used by the
            // supplied 1.0.0 Excel exporter. Keep that value as the original-theme source of truth.
            case WETHRIVE_ORIGINAL -> new int[]{0, 100, 0};
            case WORKBOOK_INSPIRED -> new int[]{78, 78, 78};
            case SHAPE_IT_MONOCHROME -> new int[]{25, 25, 25};
            case CUSTOM -> throw new IllegalStateException("Custom export accent was not resolved");
        };
    }

    private void setFontColour(Font font, int[] colour) {
        if (font instanceof XSSFFont xssfFont) {
            xssfFont.setColor(new XSSFColor(rgb(colour), null));
        } else if (colour[0] == 0 && colour[1] == 100 && colour[2] == 0) {
            font.setColor(IndexedColors.DARK_GREEN.getIndex());
        } else {
            font.setColor(IndexedColors.BLACK.getIndex());
        }
    }

    private static byte[] rgb(int[] colour) {
        return new byte[]{(byte) colour[0], (byte) colour[1], (byte) colour[2]};
    }

    private String exportFilterDescription(ExcelExportOptions options) {
        String range = (options.from() == null ? "earliest" : options.from().toString()) + " to "
                + (options.to() == null ? "latest" : options.to().toString());
        return "Date " + range + "; category "
                + (options.category() == null ? "all" : options.category());
    }

    private double luminance(int[] rgb) {
        double[] values = java.util.Arrays.stream(rgb).mapToDouble(value -> value / 255d)
                .map(value -> value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4)).toArray();
        return 0.2126 * values[0] + 0.7152 * values[1] + 0.0722 * values[2];
    }
    private void keyValue(Sheet sheet, int row, String label, BigDecimal value, Workbook workbook) { keyValue(sheet, row, label, value, workbook, false); }
    private void keyValue(Sheet sheet, int row, String label, BigDecimal value, Workbook workbook, boolean percentage) { Row data = sheet.createRow(row); text(data, 0, label); if (percentage) percent(data, 1, value, workbook); else money(data, 1, value, workbook); }
    void text(Row row, int column, String value) {
        String safe = value == null ? "" : value;
        if (hasDangerousFormulaPrefix(safe)) safe = "'" + safe;
        row.createCell(column).setCellValue(safe);
    }
    void date(Row row, int column, LocalDate value, Workbook workbook) {
        Cell cell = row.createCell(column);
        if (value != null) cell.setCellValue(value);
        cell.setCellStyle(cachedDataStyle(workbook, "date", "yyyy-mm-dd", false));
    }
    void time(Row row, int column, LocalTime value, Workbook workbook) {
        Cell cell = row.createCell(column);
        if (value != null) cell.setCellValue(LocalDateTime.of(LocalDate.of(1900, 1, 1), value));
        cell.setCellStyle(cachedDataStyle(workbook, "time", "hh:mm", false));
    }
    void timestamp(Row row, int column, Instant value, String timeZone, Workbook workbook) {
        Cell cell = row.createCell(column);
        if (value != null) cell.setCellValue(LocalDateTime.ofInstant(value, safeZone(timeZone)));
        cell.setCellStyle(cachedDataStyle(workbook, "timestamp", "yyyy-mm-dd hh:mm:ss", false));
    }
    private void number(Row row, int column, Number value, CellStyle style) {
        Cell cell = row.createCell(column); cell.setCellValue(value == null ? 0 : value.doubleValue());
        if (style != null) cell.setCellStyle(style);
    }
    private void money(Row row, int column, BigDecimal value, Workbook workbook) {
        money(row, column, value, workbook, currency(workbook));
    }
    private void money(Row row, int column, BigDecimal value, Workbook workbook, String currency) {
        number(row, column, value == null ? BigDecimal.ZERO : value, moneyStyle(workbook, currency, false));
    }
    private void setCurrency(Workbook workbook, String currency) { state(workbook).currency = normalizedCurrency(currency); }
    private void percent(Row row, int column, BigDecimal value, Workbook workbook) {
        number(row, column, value == null ? BigDecimal.ZERO : value,
                cachedDataStyle(workbook, "percent", "0.00%", false));
    }

    private void totalText(Row row, int column, String value, Workbook workbook) {
        text(row, column, value); row.getCell(column).setCellStyle(totalStyle(workbook));
    }
    private void totalMoney(Row row, int column, BigDecimal value, Workbook workbook) {
        number(row, column, value == null ? BigDecimal.ZERO : value, moneyStyle(workbook, currency(workbook), true));
    }
    private void totalNumber(Row row, int column, Number value, Workbook workbook) {
        number(row, column, value, totalStyle(workbook));
    }
    private void totalPercent(Row row, int column, BigDecimal value, Workbook workbook) {
        number(row, column, value == null ? BigDecimal.ZERO : value,
                cachedDataStyle(workbook, "total:percent", "0.00%", true));
    }
    private void textValue(Row row, int column, String value, Workbook workbook, boolean total) {
        if (total) totalText(row, column, value, workbook); else text(row, column, value);
    }
    private void moneyValue(Row row, int column, BigDecimal value, Workbook workbook, boolean total) {
        if (total) totalMoney(row, column, value, workbook); else money(row, column, value, workbook);
    }
    private void numberValue(Row row, int column, Number value, Workbook workbook, boolean total) {
        if (total) totalNumber(row, column, value, workbook); else number(row, column, value, null);
    }
    private void percentValue(Row row, int column, BigDecimal value, Workbook workbook, boolean total) {
        if (total) totalPercent(row, column, value, workbook); else percent(row, column, value, workbook);
    }

    private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() == 0) return BigDecimal.ZERO;
        return (numerator == null ? BigDecimal.ZERO : numerator).divide(denominator, 8, RoundingMode.HALF_UP);
    }

    private boolean hasDangerousFormulaPrefix(String value) {
        int offset = 0;
        while (offset < value.length()) {
            int codePoint = value.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)
                    && !Character.isISOControl(codePoint) && type != Character.FORMAT) break;
            offset += Character.charCount(codePoint);
        }
        return offset < value.length() && "=+-@".indexOf(value.charAt(offset)) >= 0;
    }

    private ZoneId safeZone(String value) {
        try {
            return ZoneId.of(value == null || value.isBlank() ? "UTC" : value);
        } catch (RuntimeException ignored) {
            return ZoneId.of("UTC");
        }
    }

    private CellStyle moneyStyle(Workbook workbook, String currency, boolean total) {
        String normalized = normalizedCurrency(currency);
        String prefix = "ZAR".equals(normalized) ? "R" : "\"" + normalized + "\"";
        boolean original = state(workbook).options.theme() == ExportTheme.WETHRIVE_ORIGINAL;
        return cachedDataStyle(workbook, (total ? "total:" : "") + "money:" + normalized,
                prefix + " #,##0.00;" + (original ? "[Red]-" : "-") + prefix + " #,##0.00", total);
    }

    private CellStyle totalStyle(Workbook workbook) {
        return cachedStyle(workbook, "total", style -> {
            style.setFont(boldFont(workbook));
            style.setBorderTop(BorderStyle.THIN);
            if (style instanceof XSSFCellStyle xssfStyle) {
                xssfStyle.setTopBorderColor(new XSSFColor(rgb(accent(workbook)), null));
            } else if (state(workbook).options.theme() == ExportTheme.WETHRIVE_ORIGINAL) {
                style.setTopBorderColor(IndexedColors.DARK_GREEN.getIndex());
            }
        });
    }

    private CellStyle cachedDataStyle(Workbook workbook, String key, String format, boolean bold) {
        return cachedStyle(workbook, key, style -> {
            style.setDataFormat(workbook.createDataFormat().getFormat(format));
            if (bold) style.setFont(boldFont(workbook));
        });
    }

    private Font boldFont(Workbook workbook) {
        WorkbookState workbookState = state(workbook);
        synchronized (workbookStates) {
            if (workbookState.boldFontIndex == null) {
                Font font = workbook.createFont(); font.setBold(true);
                workbookState.boldFontIndex = font.getIndex();
            }
            return workbook.getFontAt(workbookState.boldFontIndex);
        }
    }

    private CellStyle cachedStyle(Workbook workbook, String key, Consumer<CellStyle> initializer) {
        WorkbookState workbookState = state(workbook);
        synchronized (workbookStates) {
            Integer index = workbookState.styleIndexes.get(key);
            if (index == null) {
                CellStyle style = workbook.createCellStyle();
                initializer.accept(style);
                index = (int) style.getIndex();
                workbookState.styleIndexes.put(key, index);
            }
            return workbook.getCellStyleAt(index);
        }
    }

    private WorkbookState state(Workbook workbook) {
        synchronized (workbookStates) {
            return workbookStates.computeIfAbsent(workbook, ignored -> new WorkbookState());
        }
    }

    private String currency(Workbook workbook) {
        String value = state(workbook).currency;
        return value == null ? normalizedCurrency(properties.branding().defaultCurrency()) : value;
    }

    private String normalizedCurrency(String value) {
        String fallback = properties == null ? "ZAR" : properties.branding().defaultCurrency();
        String normalized = value == null || value.isBlank() ? fallback : value;
        return normalized.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
    }

    static String applicationVersion() {
        String implementationVersion = ExcelExportService.class.getPackage().getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank() ? "1.0.0" : implementationVersion;
    }

    private static final class WorkbookState {
        private final Map<String, Integer> styleIndexes = new HashMap<>();
        private Integer boldFontIndex;
        private Integer logoPictureIndex;
        private String currency;
        private long dataRows;
        private ExcelExportOptions options = ExcelExportOptions.defaults();
    }

    static final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
        private final int limit;

        BoundedByteArrayOutputStream(int limit) {
            super(Math.min(limit, 64 * 1024));
            this.limit = limit;
        }

        @Override
        public synchronized void write(int value) {
            ensureCapacityWithinLimit(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] values, int offset, int length) {
            ensureCapacityWithinLimit(length);
            super.write(values, offset, length);
        }

        private void ensureCapacityWithinLimit(int additionalBytes) {
            if ((long) count + additionalBytes > limit) {
                throw new ExportSizeLimitException();
            }
        }
    }

    private static final class ExportSizeLimitException extends RuntimeException {
        private ExportSizeLimitException() {
            super("Excel output exceeds the 64 MiB response limit; narrow the export scope or filters");
        }
    }

    private static boolean causedBy(Throwable value, Class<? extends Throwable> type) {
        for (Throwable current = value; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    @FunctionalInterface private interface WorkbookContent { void write(Workbook workbook); }
}
