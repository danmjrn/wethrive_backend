package solutions.shapeit.wethrive.report.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetItemResponse;
import solutions.shapeit.wethrive.finance.service.BudgetService;
import solutions.shapeit.wethrive.identity.service.CurrentUser;
import solutions.shapeit.wethrive.report.dto.ReportDtos.AnnualReport;
import solutions.shapeit.wethrive.report.dto.ReportDtos.IncomeBudgetMapping;
import solutions.shapeit.wethrive.report.dto.ReportDtos.IncomeScheduleRow;
import solutions.shapeit.wethrive.report.dto.ReportDtos.MonthlyReport;
import solutions.shapeit.wethrive.report.dto.ReportDtos.NamedAmount;
import solutions.shapeit.wethrive.report.dto.ReportDtos.QuarterlyReport;
import solutions.shapeit.wethrive.report.dto.ReportDtos.ReportFilter;
import solutions.shapeit.wethrive.report.service.ReportService;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {
    private final ReportService reports;
    private final BudgetService budgets;
    private final CurrentUser currentUser;
    public ReportController(ReportService reports, BudgetService budgets, CurrentUser currentUser) { this.reports = reports; this.budgets = budgets; this.currentUser = currentUser; }
    @GetMapping("/monthly") public MonthlyReport monthly(@RequestParam UUID spaceId, @RequestParam int year,
            @RequestParam int month, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) String category,
            @RequestParam(required = false) List<UUID> budgetIds) {
        return reports.monthly(spaceId, year, month, currentUser.id(), filter(from, to, category, budgetIds));
    }
    @GetMapping("/quarterly") public QuarterlyReport quarterly(@RequestParam UUID spaceId, @RequestParam int year,
            @RequestParam int quarter, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) String category,
            @RequestParam(required = false) List<UUID> budgetIds) {
        return reports.quarterly(spaceId, year, quarter, currentUser.id(), filter(from, to, category, budgetIds));
    }
    @GetMapping("/annual") public AnnualReport annual(@RequestParam UUID spaceId, @RequestParam int year,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) List<UUID> budgetIds) {
        return reports.annual(spaceId, year, currentUser.id(), filter(from, to, category, budgetIds));
    }
    @GetMapping("/categories") public List<NamedAmount> categories(@RequestParam UUID spaceId, @RequestParam int year, @RequestParam int month) { return reports.categories(spaceId, year, month, currentUser.id()); }
    @GetMapping("/members") public List<NamedAmount> members(@RequestParam UUID spaceId, @RequestParam int year, @RequestParam int month) { return reports.members(spaceId, year, month, currentUser.id()); }
    @GetMapping("/income-schedule") public List<IncomeScheduleRow> incomeSchedule(@RequestParam UUID spaceId, @RequestParam int year, @RequestParam int month) { return reports.incomeSchedule(spaceId, year, month, currentUser.id()); }
    @GetMapping("/income-to-budget-mapping") public List<IncomeBudgetMapping> incomeToBudgetMappings(@RequestParam UUID spaceId, @RequestParam int year, @RequestParam int month) { return reports.incomeToBudgetMappings(spaceId, year, month, currentUser.id()); }
    @GetMapping("/budget-items/{id}") public BudgetItemResponse item(@PathVariable UUID id) { return budgets.getItem(id, currentUser.id()); }
    private ReportFilter filter(LocalDate from, LocalDate to, String category, List<UUID> budgetIds) {
        return new ReportFilter(from, to, category, budgetIds);
    }
}
