package solutions.shapeit.wethrive.export.service;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import solutions.shapeit.wethrive.common.config.ApplicationProperties;
import solutions.shapeit.wethrive.export.service.ExcelExportService.ExcelExportOptions;
import solutions.shapeit.wethrive.export.service.ExcelExportService.ExportScope;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetItemResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeDeductionResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptDeductionResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.SpendingResponse;
import solutions.shapeit.wethrive.finance.repository.BudgetMonthRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeDeductionRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeEntryRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptDeductionRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeReceiptRepository;
import solutions.shapeit.wethrive.finance.service.BudgetService;
import solutions.shapeit.wethrive.finance.service.SpendingService;
import solutions.shapeit.wethrive.report.dto.ReportDtos.AnnualReport;
import solutions.shapeit.wethrive.report.dto.ReportDtos.MonthlyReport;
import solutions.shapeit.wethrive.report.dto.ReportDtos.NamedAmount;
import solutions.shapeit.wethrive.report.dto.ReportDtos.PeriodRow;
import solutions.shapeit.wethrive.report.dto.ReportDtos.PeriodTotals;
import solutions.shapeit.wethrive.report.dto.ReportDtos.QuarterlyReport;
import solutions.shapeit.wethrive.report.service.ReportService;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.SpaceResponse;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;
import solutions.shapeit.wethrive.space.service.SpaceAccessService.Capability;
import solutions.shapeit.wethrive.space.service.SpaceService;

/**
 * Authoritative, in-memory PDF exports. All values come from finance/report services and every
 * entry point repeats the export authorization check. No temporary files or internal identifiers
 * are written to the document.
 */
@Service
public class PdfExportService {
    private static final int MAX_LEDGER_ROWS = 10_000;
    private static final int MAX_FINANCE_SOURCE_ROWS = 10_000;
    private static final int MAX_PDF_TABLE_ROWS = 10_000;
    private static final int MAX_PDF_BYTES = 32 * 1024 * 1024;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm z", Locale.ENGLISH);

    private final BudgetService budgets;
    private final SpendingService spending;
    private final ReportService reports;
    private final SpaceService spaces;
    private final SpaceAccessService access;
    private final BudgetMonthRepository budgetMonths;
    private final IncomeEntryRepository incomeEntries;
    private final IncomeReceiptRepository incomeReceipts;
    private final IncomeDeductionRepository incomeDeductions;
    private final IncomeReceiptDeductionRepository actualDeductions;
    private final ApplicationProperties properties;
    private final Clock clock;

    public PdfExportService(BudgetService budgets, SpendingService spending, ReportService reports,
                            SpaceService spaces, BudgetMonthRepository budgetMonths,
                            IncomeEntryRepository incomeEntries, IncomeReceiptRepository incomeReceipts,
                            IncomeDeductionRepository incomeDeductions,
                            IncomeReceiptDeductionRepository actualDeductions,
                            SpaceAccessService access,
                            ApplicationProperties properties, Clock clock) {
        this.budgets = budgets;
        this.spending = spending;
        this.reports = reports;
        this.spaces = spaces;
        this.access = access;
        this.budgetMonths = budgetMonths; this.incomeEntries = incomeEntries;
        this.incomeReceipts = incomeReceipts; this.incomeDeductions = incomeDeductions;
        this.actualDeductions = actualDeductions;
        this.properties = properties;
        this.clock = clock;
    }

    public PdfDocument allBudgets(UUID actorId, UUID requestedSpaceId) {
        return allBudgets(actorId, requestedSpaceId, ExcelExportOptions.defaults());
    }

    public PdfDocument allBudgets(UUID actorId, UUID requestedSpaceId, ExcelExportOptions options) {
        List<SpaceResponse> authorised = requestedSpaceId == null
                ? spaces.list(actorId).stream().filter(space -> access.can(space.id(), actorId, Capability.EXPORT)).toList()
                : List.of(requireExport(requestedSpaceId, actorId));
        preflightBudgetRows(authorised.stream().map(SpaceResponse::id).collect(Collectors.toSet()));
        authorised.forEach(space -> preflightFinanceHistory(space.id()));
        List<List<String>> rows = new ArrayList<>();
        for (SpaceResponse space : authorised) {
            for (BudgetResponse budget : budgets.listForExport(space.id(), actorId)) {
                rows.add(List.of(space.name(), budget.year() + "-" + String.format("%02d", budget.month()),
                        budget.name(), budget.status().name(), money(budget.summary().projectedNetIncome(), space.currencyCode()),
                        money(budget.summary().receivedNetIncome(), space.currencyCode()),
                        money(budget.summary().plannedSpending(), space.currencyCode()),
                        money(budget.summary().netSpending(), space.currencyCode()), budget.summary().status()));
            }
        }
        String context = requestedSpaceId == null ? "All accessible spaces" : authorised.getFirst().name();
        return render("Budget register", context, "All periods", null, writer -> {
            writer.note("This register contains only spaces for which the current member has export permission.");
            writer.table(List.of("Space", "Period", "Budget", "State", "Projected net", "Received net",
                    "Planned", "Actual", "Health"), rows, null);
        }, "wethrive-budgets.pdf", options);
    }

    public PdfDocument budget(UUID budgetId, UUID actorId) {
        return budget(budgetId, actorId, ExcelExportOptions.defaults());
    }

    public PdfDocument budget(UUID budgetId, UUID actorId, ExcelExportOptions options) {
        var budget = budgets.requireBudget(budgetId);
        access.require(budget.getSpaceId(), actorId, Capability.EXPORT);
        return monthlyDocument(actorId, budget.getSpaceId(), budget.getYear(), budget.getMonth(), "Budget",
                "wethrive-budget.pdf", options);
    }

    public PdfDocument budgetItem(UUID itemId, UUID actorId) {
        return budgetItem(itemId, actorId, ExcelExportOptions.defaults());
    }

    public PdfDocument budgetItem(UUID itemId, UUID actorId, ExcelExportOptions options) {
        BudgetItemResponse item = budgets.getItem(itemId, actorId);
        access.require(item.spaceId(), actorId, Capability.EXPORT);
        SpaceResponse space = spaces.get(item.spaceId(), actorId);
        BudgetResponse budget = budgets.get(item.budgetMonthId(), actorId);
        List<SpendingResponse> entries = pageAll(actorId, item.spaceId()).stream()
                .filter(value -> item.id().equals(value.budgetItemId())).toList();
        return render("Budget item", space.name(), period(budget.year(), budget.month()), space, writer -> {
            writer.heading(item.name());
            writer.kpis(List.of(
                    new Kpi("Planned budget", money(item.plannedAmount(), space.currencyCode())),
                    new Kpi("Confirmed funding", money(item.funding().confirmedAllocatedAmount(), space.currencyCode())),
                    new Kpi("Funding gap", money(item.funding().fundingGap(), space.currencyCode())),
                    new Kpi("Actual net spending", money(item.calculation().netSpending(), space.currencyCode())),
                    new Kpi("Remaining budget", money(item.calculation().remaining(), space.currencyCode())),
                    new Kpi("Unfunded spending", money(item.funding().unfundedSpending(), space.currencyCode()))));
            writer.table(List.of("Date", "Type", "Description", "Merchant", "Amount", "Member"),
                    spendingRows(entries, space), null);
        }, "wethrive-budget-item.pdf", options);
    }

    public PdfDocument spending(UUID actorId, UUID spaceId, UUID memberId, Instant from, Instant to) {
        return spending(actorId, spaceId, memberId, from, to, ExcelExportOptions.defaults());
    }

    public PdfDocument spending(UUID actorId, UUID spaceId, UUID memberId, Instant from, Instant to,
                                ExcelExportOptions options) {
        SpaceResponse space = spaceId == null ? null : requireExport(spaceId, actorId);
        Set<UUID> exportable = spaceId == null
                ? spaces.list(actorId).stream().filter(value -> access.can(value.id(), actorId, Capability.EXPORT))
                    .map(SpaceResponse::id).collect(Collectors.toSet())
                : Set.of(spaceId);
        List<SpendingResponse> candidates = new ArrayList<>();
        for (UUID exportableSpaceId : exportable.stream().sorted().toList()) {
            List<SpendingResponse> spaceEntries = pageAll(actorId, exportableSpaceId, from, to,
                    MAX_LEDGER_ROWS - candidates.size());
            requireWithinLimit(candidates.size() + (long) spaceEntries.size(), MAX_LEDGER_ROWS,
                    "PDF spending ledger");
            candidates.addAll(spaceEntries);
        }
        List<SpendingResponse> entries = candidates.stream()
                .filter(value -> exportable.contains(value.spaceId()))
                .filter(value -> memberId == null || memberId.equals(value.spentByUserId()))
                .filter(value -> from == null || !value.spentAt().isBefore(from))
                .filter(value -> to == null || value.spentAt().isBefore(to))
                .sorted(Comparator.comparing(SpendingResponse::spentAt).reversed()
                        .thenComparing(SpendingResponse::id))
                .toList();
        String range = (from == null ? "Earliest" : from.toString()) + " to " + (to == null ? "latest" : to.toString());
        return render("Spending ledger", space == null ? "All accessible spaces" : space.name(), range, space, writer -> {
            writer.table(List.of("Date", "Type", "Description", "Merchant", "Amount", "Time zone"),
                    spendingRows(entries, space), null);
        }, "wethrive-spending.pdf", options);
    }

    public PdfDocument monthly(UUID actorId, UUID spaceId, int year, int month) {
        return monthly(actorId, spaceId, year, month, ExcelExportOptions.defaults());
    }

    public PdfDocument monthly(UUID actorId, UUID spaceId, int year, int month, ExcelExportOptions options) {
        return monthlyDocument(actorId, spaceId, year, month, "Monthly report",
                "wethrive-monthly-report.pdf", options);
    }

    public PdfDocument quarterly(UUID actorId, UUID spaceId, int year, int quarter) {
        return quarterly(actorId, spaceId, year, quarter, ExcelExportOptions.defaults());
    }

    public PdfDocument quarterly(UUID actorId, UUID spaceId, int year, int quarter,
                                 ExcelExportOptions options) {
        SpaceResponse space = requireExport(spaceId, actorId);
        preflightBudgetRows(Set.of(spaceId));
        if (options.includeDetailedLedgers()) preflightFinanceHistory(spaceId);
        QuarterlyReport report = quarterlyReport(spaceId, year, quarter, actorId, options);
        int firstMonth = (quarter - 1) * 3 + 1;
        List<BudgetResponse> periodBudgets = selectBudgets(
                budgets.listForExport(spaceId, year, firstMonth, firstMonth + 2, actorId), options);
        return render("Quarterly report", space.name(), "Q" + quarter + " " + year, space, writer -> {
            if (options.hasReportSelection()) writer.note("Filters: " + exportFilterDescription(options));
            totals(writer, report.totals(), space.currencyCode());
            periodTable(writer, report.months(), space.currencyCode());
            if (options.includeCharts()) writer.chartHeading("Actual spending by category", 640, 280);
            else writer.heading("Actual spending by category");
            if (options.includeCharts()) {
                writer.chart(doughnut(report.categories(), space.currencyCode(), "Actual total", options), 640, 280,
                        "Actual spending by category chart");
            }
            namedTable(writer, report.categories(), space.currencyCode(), "Category");
            if (options.includeDetailedLedgers()) incomeAndDeductions(writer, periodBudgets, actorId, space, options);
        }, "wethrive-quarterly-report.pdf", options);
    }

    public PdfDocument annual(UUID actorId, UUID spaceId, int year) {
        return annual(actorId, spaceId, year, ExcelExportOptions.defaults());
    }

    public PdfDocument annual(UUID actorId, UUID spaceId, int year, ExcelExportOptions options) {
        return annualDocument(actorId, spaceId, year, false, options);
    }

    public PdfDocument full(UUID actorId, UUID spaceId, int year) {
        return full(actorId, spaceId, year, ExcelExportOptions.defaults());
    }

    public PdfDocument full(UUID actorId, UUID spaceId, int year, ExcelExportOptions options) {
        return annualDocument(actorId, spaceId, year, true, options);
    }

    private PdfDocument monthlyDocument(UUID actorId, UUID spaceId, int year, int month,
                                        String title, String filename, ExcelExportOptions options) {
        SpaceResponse space = requireExport(spaceId, actorId);
        preflightBudgetRows(Set.of(spaceId));
        if (options.includeDetailedLedgers()) preflightFinanceHistory(spaceId);
        List<BudgetResponse> periodBudgets = selectBudgets(
                budgets.listForExport(spaceId, year, month, month, actorId), options);
        MonthlyReport report = monthlyReport(spaceId, year, month, actorId, options);
        BudgetResponse budget = periodBudgets.isEmpty() ? null : periodBudgets.getFirst();
        return render(title, space.name(), period(year, month), space, writer -> {
            if (budget == null) writer.note("No budget exists for this period; totals are shown as zero.");
            if (options.hasReportSelection()) writer.note("Filters: " + exportFilterDescription(options));
            totals(writer, report.reportTotals(), space.currencyCode());
            List<NamedAmount> projected = report.charts() == null
                    ? report.totals().items().stream().map(value -> new NamedAmount(null, value.name(), value.plannedAmount())).toList()
                    : report.charts().projectedExpensesByCategory();
            if (options.includeCharts()) writer.chartHeading("Projected expenses by category", 640, 280);
            else writer.heading("Projected expenses by category");
            if (options.includeCharts()) {
                writer.chart(doughnut(projected, space.currencyCode(), options), 640, 280,
                        "Projected expenses chart");
            }
            namedTable(writer, projected, space.currencyCode(), "Category");
            if (options.includeCharts() && report.charts() != null) {
                writer.chartHeading("Funding and income availability", 680, 310);
                writer.chart(bars(report.charts().plannedFundingAndActual(), report.charts().incomeAvailability(),
                        space.currencyCode(), options), 680, 310, "Funding and income chart");
                namedTable(writer, report.charts().plannedFundingAndActual(), space.currencyCode(), "Measure");
                namedTable(writer, report.charts().incomeAvailability(), space.currencyCode(), "Income measure");
            }
            budgetItems(writer, report.totals().items(), space.currencyCode());
            if (options.includeDetailedLedgers() && budget != null) {
                incomeAndDeductions(writer, List.of(budget), actorId, space, options);
                funding(writer, report, space.currencyCode());
            }
        }, filename, options);
    }

    private PdfDocument annualDocument(UUID actorId, UUID spaceId, int year, boolean complete,
                                       ExcelExportOptions options) {
        SpaceResponse space = requireExport(spaceId, actorId);
        preflightBudgetRows(Set.of(spaceId));
        if (options.includeDetailedLedgers()) preflightFinanceHistory(spaceId);
        AnnualReport report = annualReport(spaceId, year, actorId, options);
        List<BudgetResponse> periodBudgets = selectBudgets(
                budgets.listForExport(spaceId, year, 1, 12, actorId), options);
        String title = complete ? "Complete financial report" : "Annual report";
        String filename = complete ? "wethrive-complete-report.pdf" : "wethrive-annual-report.pdf";
        return render(title, space.name(), String.valueOf(year), space, writer -> {
            if (options.hasReportSelection()) writer.note("Filters: " + exportFilterDescription(options));
            totals(writer, report.totals(), space.currencyCode());
            periodTable(writer, report.months(), space.currencyCode());
            if (options.includeCharts()) writer.chartHeading("Actual spending by category", 640, 280);
            else writer.heading("Actual spending by category");
            if (options.includeCharts()) {
                writer.chart(doughnut(report.categories(), space.currencyCode(), "Actual total", options), 640, 280,
                        "Actual spending by category chart");
            }
            namedTable(writer, report.categories(), space.currencyCode(), "Category");
            if (complete) {
                namedTable(writer, report.members(), space.currencyCode(), "Member");
            }
            if (options.includeDetailedLedgers()) {
                incomeAndDeductions(writer, periodBudgets, actorId, space, options);
            }
        }, filename, options);
    }

    private void totals(PdfWriter writer, PeriodTotals totals, String currency) {
        writer.heading("Key financial indicators");
        writer.kpis(List.of(
                new Kpi("Projected gross income", money(totals.projectedGrossIncome(), currency)),
                new Kpi("Projected deductions", money(totals.projectedDeductions(), currency)),
                new Kpi("Projected net income", money(totals.projectedNetIncome(), currency)),
                new Kpi("Received gross income", money(totals.receivedGrossIncome(), currency)),
                new Kpi("Realised deductions", money(totals.realizedDeductions(), currency)),
                new Kpi("Received net income", money(totals.receivedNetIncome(), currency)),
                new Kpi("Confirmed allocations", money(totals.confirmedAllocatedIncome(), currency)),
                new Kpi("Available to allocate", money(totals.unallocatedReceivedIncome(), currency)),
                new Kpi("Planned spending", money(totals.plannedSpending(), currency)),
                new Kpi("Actual net spending", money(totals.netSpending(), currency)),
                new Kpi("Funding gap", money(totals.fundingGap(), currency)),
                new Kpi("Remaining cash", money(totals.remaining(), currency))));
    }

    private void periodTable(PdfWriter writer, List<PeriodRow> periods, String currency) {
        writer.heading("Period summary");
        writer.table(List.of("Period", "Projected net", "Received net", "Planned", "Funded", "Actual", "Remaining", "Health"),
                periods.stream().map(value -> List.of(value.label(), money(value.totals().projectedNetIncome(), currency),
                        money(value.totals().receivedNetIncome(), currency), money(value.totals().plannedSpending(), currency),
                        money(value.totals().confirmedFundedBudget(), currency), money(value.totals().netSpending(), currency),
                        money(value.totals().remaining(), currency), value.totals().status())).toList(), null);
    }

    private void budgetItems(PdfWriter writer, List<BudgetItemResponse> items, String currency) {
        writer.heading("Budget-item summary");
        writer.table(List.of("Budget item", "Planned", "Planned funding", "Confirmed", "Gap", "Actual", "Remaining", "Status"),
                items.stream().map(value -> List.of(value.name(), money(value.plannedAmount(), currency),
                        money(value.funding().plannedFunding(), currency), money(value.funding().confirmedAllocatedAmount(), currency),
                        money(value.funding().fundingGap(), currency), money(value.calculation().netSpending(), currency),
                        money(value.calculation().remaining(), currency), value.funding().status().name())).toList(), null);
    }

    private void incomeAndDeductions(PdfWriter writer, List<BudgetResponse> periodBudgets, UUID actorId,
                                     SpaceResponse space, ExcelExportOptions options) {
        List<UUID> budgetIds = periodBudgets.stream().map(BudgetResponse::id).toList();
        if (!budgetIds.isEmpty() && incomeEntries != null) {
            requireWithinLimit(incomeEntries.countByBudgetMonthIdInAndDeletedAtIsNull(budgetIds),
                    MAX_FINANCE_SOURCE_ROWS, "PDF income schedule");
        }
        List<IncomeResponse> incomes = periodBudgets.stream()
                .flatMap(value -> budgets.income(value.id(), actorId).stream()).toList();
        requireWithinLimit(incomes.size(), MAX_FINANCE_SOURCE_ROWS, "PDF income schedule");
        Set<UUID> incomeIds = incomes.stream().map(IncomeResponse::id).collect(Collectors.toSet());
        Map<UUID, Integer> incomeOrder = new LinkedHashMap<>();
        for (int index = 0; index < incomes.size(); index++) incomeOrder.putIfAbsent(incomes.get(index).id(), index);
        writer.heading("Income schedule and availability");
        writer.table(List.of("Source", "Expected date", "Expected gross", "Projected deductions", "Projected net",
                        "Received net", "Available", "Status"),
                incomes.stream().map(value -> List.of(value.sourceName(), DATE.format(value.expectedDate()),
                        money(value.expectedAmount(), space.currencyCode()), money(value.availability().projectedDeductions(), space.currencyCode()),
                        money(value.availability().projectedNetIncome(), space.currencyCode()),
                        money(value.availability().receivedNetIncome(), space.currencyCode()),
                        money(value.availability().unallocatedReceivedIncome(), space.currencyCode()),
                        value.availability().status().name())).toList(), null);

        Map<UUID, String> incomeNames = incomes.stream().collect(Collectors.toMap(
                IncomeResponse::id, IncomeResponse::sourceName, (left, right) -> left, LinkedHashMap::new));
        List<IncomeDeductionResponse> plannedDeductions = incomeIds.isEmpty() ? List.of()
                : budgets.incomeDeductionHistoryForSpace(space.id(), actorId).stream()
                .filter(value -> incomeIds.contains(value.incomeEntryId()))
                .sorted(Comparator.comparingInt((IncomeDeductionResponse value) -> incomeOrder.get(value.incomeEntryId()))
                        .thenComparingInt(IncomeDeductionResponse::sortOrder)
                        .thenComparing(IncomeDeductionResponse::createdAt)
                        .thenComparing(IncomeDeductionResponse::id))
                .toList();
        List<List<String>> planned = plannedDeductions.stream().map(deduction -> List.of(
                incomeNames.getOrDefault(deduction.incomeEntryId(), "Archived income"), deduction.name(),
                deduction.deductionType().name(),
                deduction.percentageRate() == null ? "-" : deduction.percentageRate().multiply(new BigDecimal("100")) + "%",
                deduction.fixedAmount() == null ? "-" : money(deduction.fixedAmount(), space.currencyCode()),
                money(deduction.projectedAmount(), space.currencyCode()),
                deduction.deletedAt() == null ? "ACTIVE" : "REMOVED")).toList();
        writer.heading("Planned income deductions");
        writer.table(List.of("Income", "Deduction", "Method", "Rate", "Fixed amount", "Projected amount", "State"), planned, null);

        List<IncomeReceiptResponse> selectedReceipts = incomeIds.isEmpty() ? List.of()
                : budgets.receiptHistoryForSpace(space.id(), actorId).stream()
                .filter(value -> incomeIds.contains(value.incomeEntryId()))
                .filter(value -> options.reportFilter().includesDate(value.receivedAt()
                        .atZone(safeZone(value.timeZone())).toLocalDate()))
                .sorted(Comparator.comparingInt((IncomeReceiptResponse value) -> incomeOrder.get(value.incomeEntryId()))
                        .thenComparing(IncomeReceiptResponse::receivedAt)
                        .thenComparing(IncomeReceiptResponse::createdAt)
                        .thenComparing(IncomeReceiptResponse::id))
                .toList();
        Set<UUID> receiptIds = selectedReceipts.stream().map(IncomeReceiptResponse::id).collect(Collectors.toSet());
        Map<UUID, IncomeReceiptResponse> receiptsById = selectedReceipts.stream().collect(Collectors.toMap(
                IncomeReceiptResponse::id, value -> value, (left, right) -> left, LinkedHashMap::new));
        Map<UUID, Integer> receiptOrder = new LinkedHashMap<>();
        for (int index = 0; index < selectedReceipts.size(); index++) receiptOrder.putIfAbsent(selectedReceipts.get(index).id(), index);
        List<IncomeReceiptDeductionResponse> selectedActualDeductions = receiptIds.isEmpty() ? List.of()
                : budgets.receiptDeductionHistoryForSpace(space.id(), actorId).stream()
                .filter(value -> receiptIds.contains(value.incomeReceiptId()))
                .sorted(Comparator.comparingInt((IncomeReceiptDeductionResponse value) -> receiptOrder.get(value.incomeReceiptId()))
                        .thenComparing(IncomeReceiptDeductionResponse::createdAt)
                        .thenComparing(IncomeReceiptDeductionResponse::id))
                .toList();
        List<List<String>> receipts = new ArrayList<>();
        List<List<String>> realised = new ArrayList<>();
        for (IncomeReceiptResponse receipt : selectedReceipts) {
            receipts.add(List.of(incomeNames.getOrDefault(receipt.incomeEntryId(), "Archived income"),
                    TIMESTAMP.withZone(safeZone(receipt.timeZone())).format(receipt.receivedAt()),
                    money(receipt.amount(), space.currencyCode()), money(receipt.totalDeductions(), space.currencyCode()),
                    money(receipt.netAmount(), space.currencyCode()), receipt.deletedAt() == null ? "ACTIVE" : "REVERSED"));
        }
        for (IncomeReceiptDeductionResponse deduction : selectedActualDeductions) {
            IncomeReceiptResponse receipt = receiptsById.get(deduction.incomeReceiptId());
            realised.add(List.of(incomeNames.getOrDefault(receipt.incomeEntryId(), "Archived income"),
                    DATE.withZone(safeZone(receipt.timeZone())).format(receipt.receivedAt()), deduction.name(),
                    money(deduction.amount(), space.currencyCode()),
                    deduction.deletedAt() == null ? "ACTIVE" : "REVERSED"));
        }
        writer.heading("Income receipts");
        writer.table(List.of("Income", "Received at", "Gross", "Deductions", "Net", "State"), receipts, null);
        writer.heading("Actual receipt deductions");
        writer.table(List.of("Income", "Received date", "Deduction", "Amount", "State"), realised, null);
    }

    private void funding(PdfWriter writer, MonthlyReport report, String currency) {
        writer.heading("Funding summary");
        writer.table(List.of("Budget item", "Income source", "Source", "Planned funding", "Confirmed", "State"),
                report.incomeToBudgetMappings().stream().map(value -> List.of(value.budgetItemName(), value.incomeSource(),
                        value.sourceType().name(), money(value.plannedFunding(), currency),
                        money(value.confirmedAllocation(), currency), value.reversedAt() == null ? "ACTIVE" : "REVERSED")).toList(), null);
    }

    private void namedTable(PdfWriter writer, List<NamedAmount> values, String currency, String label) {
        writer.table(List.of(label, "Amount"), values.stream()
                .map(value -> List.of(value.name(), money(value.amount(), currency))).toList(), null);
    }

    private List<List<String>> spendingRows(List<SpendingResponse> entries, SpaceResponse space) {
        String currency = space == null ? properties.branding().defaultCurrency() : space.currencyCode();
        return entries.stream().map(value -> List.of(DATE.format(value.date()), value.transactionType().name(), value.title(),
                value.merchant() == null ? "" : value.merchant(), money(value.amount(), currency), value.timeZone())).toList();
    }

    private List<BudgetResponse> selectBudgets(List<BudgetResponse> candidates, ExcelExportOptions options) {
        if (options.scope() != ExportScope.SELECTED_BUDGETS) return candidates;
        Set<UUID> candidateIds = candidates.stream().map(BudgetResponse::id).collect(Collectors.toSet());
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

    private String exportFilterDescription(ExcelExportOptions options) {
        String range = (options.from() == null ? "earliest" : options.from().toString()) + " to "
                + (options.to() == null ? "latest" : options.to().toString());
        return "scope " + options.scope().name().replace('_', ' ') + "; date " + range
                + "; category " + (options.category() == null ? "all" : options.category());
    }

    private ZoneId safeZone(String value) {
        try {
            return ZoneId.of(value == null || value.isBlank() ? "UTC" : value);
        } catch (RuntimeException ignored) {
            return ZoneId.of("UTC");
        }
    }

    private SpaceResponse requireExport(UUID spaceId, UUID actorId) {
        access.require(spaceId, actorId, Capability.EXPORT);
        return spaces.get(spaceId, actorId);
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
            Page<SpendingResponse> page = spending.list(actorId, spaceId, from, to, pageNumber, 200);
            if (page.getTotalElements() > rowLimit
                    || result.size() + (long) page.getNumberOfElements() > rowLimit) {
                requireWithinLimit(MAX_LEDGER_ROWS + 1L, MAX_LEDGER_ROWS, "PDF spending ledger");
            }
            result.addAll(page.getContent());
            if (!page.hasNext()) break;
        }
        return result;
    }

    private PdfDocument render(String title, String context, String reportPeriod, SpaceResponse space,
                               Consumer<PdfWriter> body, String filename) {
        return render(title, context, reportPeriod, space, body, filename, ExcelExportOptions.defaults());
    }

    private PdfDocument render(String title, String context, String reportPeriod, SpaceResponse space,
                               Consumer<PdfWriter> body, String filename, ExcelExportOptions options) {
        try (PDDocument document = new PDDocument();
             BoundedByteArrayOutputStream output = new BoundedByteArrayOutputStream(MAX_PDF_BYTES)) {
            PdfWriter writer = new PdfWriter(document, title, context, reportPeriod, space, properties, clock,
                    options);
            body.accept(writer);
            writer.finish();
            document.save(output);
            return new PdfDocument(output.toByteArray(), safeFilename(filename));
        } catch (ExportSizeLimitException exception) {
            throw solutions.shapeit.wethrive.common.web.ApiException.badRequest(exception.getMessage());
        } catch (IOException exception) {
            if (causedBy(exception, ExportSizeLimitException.class)) {
                throw solutions.shapeit.wethrive.common.web.ApiException.badRequest(
                        "PDF output exceeds the 32 MiB response limit; narrow the export scope or filters");
            }
            throw new IllegalStateException("Unable to generate PDF export", exception);
        }
    }

    static String safeFilename(String value) {
        String safe = value == null ? "wethrive-export.pdf" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-").replaceAll("-+", "-")
                .replaceAll("^[.-]+|[.-]+$", "");
        if (safe.isBlank()) safe = "wethrive-export";
        if (!safe.endsWith(".pdf")) safe += ".pdf";
        return safe.length() <= 100 ? safe : safe.substring(0, 96) + ".pdf";
    }

    private void preflightBudgetRows(Set<UUID> spaceIds) {
        if (budgetMonths == null || spaceIds.isEmpty()) return;
        requireWithinLimit(budgetMonths.countBySpaceIdInAndDeletedAtIsNull(spaceIds),
                MAX_PDF_TABLE_ROWS, "PDF budget register");
    }

    private void preflightFinanceHistory(UUID spaceId) {
        if (incomeReceipts == null || incomeDeductions == null || actualDeductions == null) return;
        long rows = addPdfRows(0, incomeReceipts.countBySpaceId(spaceId));
        rows = addPdfRows(rows, incomeDeductions.countBySpaceId(spaceId));
        addPdfRows(rows, actualDeductions.countBySpaceId(spaceId));
    }

    private static long addPdfRows(long current, long additional) {
        if (additional < 0 || current > MAX_FINANCE_SOURCE_ROWS - additional) {
            throw solutions.shapeit.wethrive.common.web.ApiException.badRequest(
                    "PDF income and deduction history exceeds the 10,000-row export limit; "
                            + "select a smaller space or use the bounded Excel export");
        }
        return current + additional;
    }

    private static void requireWithinLimit(long rows, int limit, String label) {
        if (rows > limit) {
            throw solutions.shapeit.wethrive.common.web.ApiException.badRequest(label + " exceeds the "
                    + String.format(Locale.ROOT, "%,d", limit)
                    + "-row export limit; narrow the scope, budgets, period, member, or category");
        }
    }

    static final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
        private final int limit;

        BoundedByteArrayOutputStream(int limit) {
            super(Math.min(limit, 64 * 1024));
            this.limit = limit;
        }

        @Override
        public synchronized void write(int value) {
            ensureWithinLimit(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] values, int offset, int length) {
            ensureWithinLimit(length);
            super.write(values, offset, length);
        }

        private void ensureWithinLimit(int additionalBytes) {
            if ((long) count + additionalBytes > limit) throw new ExportSizeLimitException();
        }
    }

    private static final class ExportSizeLimitException extends RuntimeException {
        private ExportSizeLimitException() {
            super("PDF output exceeds the 32 MiB response limit; narrow the export scope or filters");
        }
    }

    private static boolean causedBy(Throwable value, Class<? extends Throwable> type) {
        for (Throwable current = value; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    private static String period(int year, int month) {
        return YearMonth.of(year, month).getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)
                + " " + year;
    }

    private static String money(BigDecimal value, String currency) {
        BigDecimal amount = value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_EVEN);
        return (currency == null || currency.isBlank() ? "ZAR" : currency.toUpperCase(Locale.ROOT)) + " "
                + String.format(Locale.ENGLISH, "%,.2f", amount);
    }

    private static int[] pdfAccent(ExcelExportOptions options) {
        return ExcelExportService.accent(options);
    }

    static BufferedImage doughnut(List<NamedAmount> source, String currency) {
        return doughnut(source, currency, "Projected total", ExcelExportOptions.defaults());
    }

    static BufferedImage doughnut(List<NamedAmount> source, String currency, String totalLabel) {
        return doughnut(source, currency, totalLabel, ExcelExportOptions.defaults());
    }

    static BufferedImage doughnut(List<NamedAmount> source, String currency, ExcelExportOptions options) {
        return doughnut(source, currency, "Projected total", options);
    }

    static BufferedImage doughnut(List<NamedAmount> source, String currency, String totalLabel,
                                  ExcelExportOptions options) {
        List<NamedAmount> values = groupedSegments(source);
        BufferedImage image = new BufferedImage(1280, 560, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = graphics(image);
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        BigDecimal total = values.stream().map(NamedAmount::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        int diameter = 430; int x = 55; int y = 55; double start = 90d;
        for (int index = 0; index < values.size(); index++) {
            BigDecimal amount = values.get(index).amount().max(BigDecimal.ZERO);
            double degrees = total.signum() == 0 ? 0 : amount.multiply(new BigDecimal("360"))
                    .divide(total, 6, RoundingMode.HALF_UP).doubleValue();
            graphics.setColor(chartColour(options, index));
            graphics.fill(new Arc2D.Double(x, y, diameter, diameter, start, -degrees, Arc2D.PIE));
            graphics.setColor(Color.WHITE); graphics.setStroke(new BasicStroke(3));
            graphics.draw(new Arc2D.Double(x, y, diameter, diameter, start, -degrees, Arc2D.PIE));
            start -= degrees;
        }
        graphics.setColor(Color.WHITE); graphics.fillOval(x + 125, y + 125, diameter - 250, diameter - 250);
        graphics.setColor(Color.BLACK); graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
        centred(graphics, totalLabel, x + diameter / 2, y + diameter / 2 - 8);
        graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, 21));
        centred(graphics, money(total, currency), x + diameter / 2, y + diameter / 2 + 25);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 19));
        for (int index = 0; index < values.size(); index++) {
            NamedAmount value = values.get(index); int top = 65 + index * 55;
            graphics.setColor(chartColour(options, index)); graphics.fillRect(570, top, 30, 30);
            graphics.setColor(Color.BLACK); graphics.drawRect(570, top, 30, 30);
            String percentage = total.signum() == 0 ? "0.0" : value.amount().multiply(new BigDecimal("100"))
                    .divide(total, 1, RoundingMode.HALF_UP).toPlainString();
            graphics.drawString(trim(value.name(), 31) + "  " + money(value.amount(), currency) + "  (" + percentage + "%)", 620, top + 23);
        }
        graphics.dispose();
        return image;
    }

    private static List<NamedAmount> groupedSegments(List<NamedAmount> source) {
        List<NamedAmount> values = source == null ? List.of() : source.stream()
                .filter(value -> value.amount() != null && value.amount().signum() > 0)
                .sorted(Comparator.comparing(NamedAmount::amount).reversed()).toList();
        BigDecimal total = values.stream().map(NamedAmount::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<NamedAmount> result = new ArrayList<>(); BigDecimal other = BigDecimal.ZERO;
        for (int index = 0; index < values.size(); index++) {
            NamedAmount value = values.get(index);
            boolean tiny = total.signum() > 0 && value.amount().divide(total, 6, RoundingMode.HALF_UP)
                    .compareTo(new BigDecimal("0.02")) < 0;
            if (index >= 7 || tiny) other = other.add(value.amount()); else result.add(value);
        }
        if (other.signum() > 0) result.add(new NamedAmount(null, "Other", other));
        return result;
    }

    static BufferedImage bars(List<NamedAmount> first, List<NamedAmount> second, String currency) {
        return bars(first, second, currency, ExcelExportOptions.defaults());
    }

    static BufferedImage bars(List<NamedAmount> first, List<NamedAmount> second, String currency,
                              ExcelExportOptions options) {
        List<NamedAmount> values = new ArrayList<>();
        if (first != null) values.addAll(first);
        if (second != null) values.addAll(second);
        BufferedImage image = new BufferedImage(1360, 620, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = graphics(image);
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        BigDecimal maximum = values.stream().map(NamedAmount::amount).filter(value -> value != null && value.signum() > 0)
                .max(Comparator.naturalOrder()).orElse(BigDecimal.ONE);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
        int x = 320; int y = 50; int height = 54; int gap = 18; int maxWidth = 930;
        for (int index = 0; index < values.size(); index++) {
            NamedAmount value = values.get(index); int top = y + index * (height + gap);
            int width = value.amount().signum() <= 0 ? 0 : value.amount().multiply(BigDecimal.valueOf(maxWidth))
                    .divide(maximum, 0, RoundingMode.HALF_UP).intValue();
            graphics.setColor(Color.BLACK); graphics.drawString(trim(value.name(), 28), 20, top + 34);
            graphics.setColor(chartColour(options, index)); graphics.fillRect(x, top, width, height);
            graphics.setColor(Color.BLACK); graphics.drawRect(x, top, width, height);
            graphics.drawString(money(value.amount(), currency), x + 12, top + 34);
        }
        graphics.dispose();
        return image;
    }

    private static Color chartColour(ExcelExportOptions options, int index) {
        if (options.theme() == ExcelExportService.ExportTheme.WETHRIVE_ORIGINAL) {
            // Exact 1.0.0 application tokens extend the original Excel dark-green header into
            // the chart types that were added later, while labels/tables preserve non-colour meaning.
            int[][] colours = {{0, 100, 0}, {109, 80, 170}, {164, 107, 24}, {177, 74, 58},
                    {35, 118, 74}, {97, 112, 103}, {135, 147, 139}, {205, 232, 111}};
            int[] colour = colours[Math.floorMod(index, colours.length)];
            return new Color(colour[0], colour[1], colour[2]);
        }
        if (options.theme() == ExcelExportService.ExportTheme.SHAPE_IT_MONOCHROME
                || options.theme() == ExcelExportService.ExportTheme.WORKBOOK_INSPIRED) {
            int shade = 28 + Math.min(196, Math.floorMod(index, 8) * 28);
            return new Color(shade, shade, shade);
        }
        int[] base = pdfAccent(options);
        double factor = switch (Math.floorMod(index, 5)) {
            case 1 -> 0.25;
            case 2 -> -0.20;
            case 3 -> 0.45;
            case 4 -> -0.35;
            default -> 0;
        };
        return new Color(adjust(base[0], factor), adjust(base[1], factor), adjust(base[2], factor));
    }

    private static int adjust(int value, double factor) {
        double adjusted = factor >= 0 ? value + (255 - value) * factor : value * (1 + factor);
        return Math.max(0, Math.min(255, (int) Math.round(adjusted)));
    }

    private static Graphics2D graphics(BufferedImage image) {
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return graphics;
    }

    private static void centred(Graphics2D graphics, String value, int x, int y) {
        graphics.drawString(value, x - graphics.getFontMetrics().stringWidth(value) / 2, y);
    }

    private static String trim(String value, int length) {
        if (value == null) return "";
        return value.length() <= length ? value : value.substring(0, Math.max(1, length - 1)) + "...";
    }

    public record PdfDocument(byte[] bytes, String filename) {}
    private record Kpi(String label, String value) {}

    private static final class PdfWriter {
        private static final PDRectangle LANDSCAPE_A4 = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
        private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private static final float MARGIN = 38f;
        private static final float HEADER_BOTTOM = 500f;
        private final PDDocument document;
        private final String title;
        private final String context;
        private final String reportPeriod;
        private final SpaceResponse space;
        private final ApplicationProperties properties;
        private final Clock clock;
        private final int[] accent;
        private final Color headerText;
        private final Color titleColour;
        private final Color textColour;
        private final Color softSurface;
        private final Color alternateSurface;
        private final Color lineColour;
        private PDPage page;
        private PDPageContentStream content;
        private PDImageXObject logo;
        private float y;
        private int pageNumber;
        private int tableRows;

        private PdfWriter(PDDocument document, String title, String context, String reportPeriod,
                          SpaceResponse space, ApplicationProperties properties, Clock clock,
                          ExcelExportOptions options) throws IOException {
            this.document = document; this.title = title; this.context = context; this.reportPeriod = reportPeriod;
            this.space = space; this.properties = properties; this.clock = clock;
            this.accent = pdfAccent(options);
            this.headerText = isLight(accent) ? Color.BLACK : Color.WHITE;
            this.titleColour = isLight(accent) ? Color.BLACK : new Color(accent[0], accent[1], accent[2]);
            if (options.theme() == ExcelExportService.ExportTheme.WETHRIVE_ORIGINAL) {
                this.textColour = new Color(22, 35, 27);
                this.softSurface = new Color(220, 235, 220);
                this.alternateSurface = new Color(247, 245, 237);
                this.lineColour = new Color(223, 228, 220);
            } else {
                this.textColour = new Color(20, 20, 20);
                this.softSurface = new Color(238, 238, 238);
                this.alternateSurface = new Color(245, 245, 245);
                this.lineColour = new Color(205, 205, 205);
            }
            try (InputStream input = PdfExportService.class.getResourceAsStream("/brand/shape-it/wordmark-black.png")) {
                if (input != null) logo = PDImageXObject.createFromByteArray(document, IOUtils.toByteArray(input), "shape-it-wordmark");
            }
            newPage();
        }

        private void newPage() throws IOException {
            closePage();
            page = new PDPage(LANDSCAPE_A4); document.addPage(page); pageNumber++;
            content = new PDPageContentStream(document, page);
            content.setNonStrokingColor(titleColour);
            text(BOLD, 22, MARGIN, 548, properties.branding().name());
            text(BOLD, 16, MARGIN, 523, title);
            text(REGULAR, 9, MARGIN, 507, context + " | " + reportPeriod);
            if (logo != null) content.drawImage(logo, 655, 505, 145, 53);
            else text(BOLD, 11, 650, 540, properties.branding().parentCompany());
            y = HEADER_BOTTOM - 18;
        }

        private void closePage() throws IOException {
            if (content == null) return;
            content.setStrokingColor(lineColour); content.moveTo(MARGIN, 26); content.lineTo(804, 26); content.stroke();
            content.setNonStrokingColor(textColour);
            text(REGULAR, 8, MARGIN, 14, "Powered by " + properties.branding().parentCompany());
            text(REGULAR, 8, 330, 14, properties.branding().name() + " " + ExcelExportService.applicationVersion());
            text(REGULAR, 8, 705, 14, "Page " + pageNumber);
            content.close(); content = null;
        }

        private void finish() throws IOException { closePage(); }

        private void heading(String value) {
            io(() -> {
                // Keep a heading with at least the first table/no-data block.
                ensure(54); content.setNonStrokingColor(titleColour);
                text(BOLD, 13, MARGIN, y, value); y -= 22;
            });
        }

        private void chartHeading(String value, int pixelWidth, int pixelHeight) {
            io(() -> {
                float chartHeight = 680f * pixelHeight / pixelWidth;
                ensure(37 + chartHeight); content.setNonStrokingColor(titleColour);
                text(BOLD, 13, MARGIN, y, value); y -= 22;
            });
        }

        private void note(String value) {
            io(() -> {
                ensure(34); content.setNonStrokingColor(softSurface); content.addRect(MARGIN, y - 22, 766, 29); content.fill();
                content.setNonStrokingColor(textColour); text(REGULAR, 9, MARGIN + 8, y - 10, value); y -= 39;
            });
        }

        private void kpis(List<Kpi> values) {
            io(() -> {
                int columns = 4; float width = 187f; float height = 48f;
                for (int index = 0; index < values.size(); index++) {
                    if (index % columns == 0) ensure(height + 10);
                    int column = index % columns; float x = MARGIN + column * (width + 6);
                    content.setNonStrokingColor(index % 2 == 0 ? softSurface : alternateSurface); content.addRect(x, y - height, width, height); content.fill();
                    content.setNonStrokingColor(textColour); text(REGULAR, 8, x + 8, y - 15, values.get(index).label());
                    text(BOLD, 11, x + 8, y - 34, values.get(index).value());
                    if (column == columns - 1 || index == values.size() - 1) y -= height + 7;
                }
                y -= 5;
            });
        }

        private void chart(BufferedImage image, int pixelWidth, int pixelHeight, String accessibleName) {
            io(() -> {
                float width = 680f; float height = width * pixelHeight / pixelWidth;
                ensure(height + 15); PDImageXObject chart = LosslessFactory.createFromImage(document, image);
                content.drawImage(chart, MARGIN, y - height, width, height);
                text(REGULAR, 7, MARGIN, y - height - 9, accessibleName + "; the following table provides the same values.");
                y -= height + 20;
            });
        }

        private void table(List<String> headers, List<List<String>> rows, List<Float> requestedWidths) {
            int requestedRows = rows == null ? 0 : rows.size();
            requireWithinLimit((long) tableRows + requestedRows, MAX_PDF_TABLE_ROWS, "PDF tables");
            tableRows += requestedRows;
            io(() -> {
                if (rows == null || rows.isEmpty()) {
                    ensure(24); text(REGULAR, 9, MARGIN, y, "No data for this section."); y -= 22; return;
                }
                float[] widths = widths(headers.size(), requestedWidths);
                tableHeader(headers, widths);
                int rowIndex = 0;
                for (List<String> row : rows) {
                    float height = rowHeight(row, widths);
                    if (y - height < 42) { newPage(); tableHeader(headers, widths); }
                    if (rowIndex++ % 2 == 1) {
                        content.setNonStrokingColor(alternateSurface); content.addRect(MARGIN, y - height, 766, height); content.fill();
                    }
                    content.setNonStrokingColor(textColour); drawCells(row, widths, height, REGULAR, 7.4f);
                    y -= height;
                }
                y -= 12;
            });
        }

        private void tableHeader(List<String> headers, float[] widths) throws IOException {
            ensure(24); content.setNonStrokingColor(new Color(accent[0], accent[1], accent[2]));
            content.addRect(MARGIN, y - 22, 766, 22); content.fill();
            content.setNonStrokingColor(headerText); drawCells(headers, widths, 22, BOLD, 7.4f); y -= 22;
        }

        private void drawCells(List<String> values, float[] widths, float height, PDType1Font font, float size) throws IOException {
            float x = MARGIN;
            for (int index = 0; index < widths.length; index++) {
                String value = index < values.size() ? values.get(index) : "";
                List<String> lines = wrap(value, font, size, widths[index] - 8, 2);
                for (int line = 0; line < lines.size(); line++) text(font, size, x + 4, y - 11 - line * 9, lines.get(line));
                content.setStrokingColor(lineColour); content.moveTo(x + widths[index], y); content.lineTo(x + widths[index], y - height); content.stroke();
                x += widths[index];
            }
            content.setStrokingColor(lineColour); content.moveTo(MARGIN, y - height); content.lineTo(MARGIN + 766, y - height); content.stroke();
        }

        private float rowHeight(List<String> row, float[] widths) throws IOException {
            int lines = 1;
            for (int index = 0; index < widths.length; index++) {
                lines = Math.max(lines, wrap(index < row.size() ? row.get(index) : "", REGULAR, 7.4f, widths[index] - 8, 2).size());
            }
            return 9 + lines * 9;
        }

        private float[] widths(int count, List<Float> requested) {
            if (requested != null && requested.size() == count) {
                float total = requested.stream().reduce(0f, Float::sum); float[] result = new float[count];
                for (int index = 0; index < count; index++) result[index] = requested.get(index) * 766 / total;
                return result;
            }
            float[] result = new float[count];
            for (int index = 0; index < count; index++) result[index] = 766f / count;
            if (count > 1) { result[0] *= 1.35f; float overflow = result[0] - 766f / count;
                for (int index = 1; index < count; index++) result[index] -= overflow / (count - 1); }
            return result;
        }

        private void ensure(float required) throws IOException { if (y - required < 42) newPage(); }

        private void text(PDType1Font font, float size, float x, float baseline, String value) throws IOException {
            String safe = pdfText(value); content.beginText(); content.setFont(font, size);
            content.newLineAtOffset(x, baseline); content.showText(safe); content.endText();
        }

        private List<String> wrap(String value, PDType1Font font, float size, float width, int maximumLines) throws IOException {
            String clean = pdfText(value); if (clean.isBlank()) return List.of("");
            List<String> result = new ArrayList<>(); StringBuilder line = new StringBuilder();
            for (String word : clean.split("\\s+")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (font.getStringWidth(candidate) / 1000f * size <= width || line.isEmpty()) line = new StringBuilder(candidate);
                else { result.add(line.toString()); line = new StringBuilder(word); if (result.size() == maximumLines - 1) break; }
            }
            if (result.size() < maximumLines && !line.isEmpty()) result.add(line.toString());
            if (result.size() == maximumLines && font.getStringWidth(result.getLast()) / 1000f * size > width) {
                String last = result.getLast(); while (last.length() > 3 && font.getStringWidth(last + "...") / 1000f * size > width) last = last.substring(0, last.length() - 1);
                result.set(result.size() - 1, last + "...");
            }
            return result;
        }

        private static String pdfText(String value) {
            if (value == null) return "";
            String normal = Normalizer.normalize(value, Normalizer.Form.NFKD)
                    .replace('\u2013', '-').replace('\u2014', '-').replace('\u2019', '\'');
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < normal.length(); index++) {
                char character = normal.charAt(index);
                if (Character.getType(character) == Character.NON_SPACING_MARK) continue;
                if (character >= 32 && character <= 126) result.append(character);
                else if (!Character.isISOControl(character)) result.append('?');
            }
            return result.toString().replaceAll("\\s+", " ").trim();
        }

        private void io(IoAction action) {
            try { action.run(); } catch (IOException exception) { throw new IllegalStateException("Unable to write PDF content", exception); }
        }

        @FunctionalInterface private interface IoAction { void run() throws IOException; }
    }

    private static boolean isLight(int[] rgb) {
        double[] values = java.util.Arrays.stream(rgb).mapToDouble(value -> value / 255d)
                .map(value -> value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4))
                .toArray();
        return 0.2126 * values[0] + 0.7152 * values[1] + 0.0722 * values[2] > 0.54;
    }
}
