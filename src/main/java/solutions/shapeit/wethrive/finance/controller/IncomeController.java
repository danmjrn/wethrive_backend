package solutions.shapeit.wethrive.finance.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.FundingAllocationResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeAvailability;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeDeductionRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeDeductionResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeDeductionUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptDeductionRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptDeductionResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptDeductionUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeReceiptUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeStateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.VersionRequest;
import solutions.shapeit.wethrive.finance.service.BudgetService;
import solutions.shapeit.wethrive.identity.service.CurrentUser;

@RestController
@RequestMapping("/api/v1")
public class IncomeController {
    private final BudgetService service; private final CurrentUser currentUser;
    public IncomeController(BudgetService service, CurrentUser currentUser) { this.service = service; this.currentUser = currentUser; }

    @GetMapping("/budgets/{budgetId}/income") public List<IncomeResponse> list(@PathVariable UUID budgetId) { return service.income(budgetId, currentUser.id()); }
    @PostMapping("/budgets/{budgetId}/income") @ResponseStatus(HttpStatus.CREATED)
    public IncomeResponse create(@PathVariable UUID budgetId, @Valid @RequestBody IncomeRequest request) { return service.createIncome(budgetId, currentUser.id(), request); }
    @PutMapping("/income/{id}") public IncomeResponse update(@PathVariable UUID id, @Valid @RequestBody IncomeUpdateRequest request) { return service.updateIncome(id, currentUser.id(), request); }
    @DeleteMapping("/income/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) { service.deleteIncome(id, currentUser.id(), request); }
    @PostMapping("/income/{id}/cancel") public IncomeResponse cancel(@PathVariable UUID id, @Valid @RequestBody IncomeStateRequest request) { return service.cancelIncome(id, currentUser.id(), request); }
    @PostMapping("/income/{id}/restore") public IncomeResponse restore(@PathVariable UUID id, @Valid @RequestBody IncomeStateRequest request) { return service.restoreIncome(id, currentUser.id(), request); }

    @GetMapping("/income/{incomeId}/deductions") public List<IncomeDeductionResponse> deductions(@PathVariable UUID incomeId) { return service.incomeDeductions(incomeId, currentUser.id()); }
    @GetMapping("/income/{incomeId}/deduction-history") public List<IncomeDeductionResponse> deductionHistory(@PathVariable UUID incomeId) { return service.incomeDeductionHistory(incomeId, currentUser.id()); }
    @PostMapping("/income/{incomeId}/deductions") @ResponseStatus(HttpStatus.CREATED)
    public IncomeDeductionResponse createDeduction(@PathVariable UUID incomeId, @Valid @RequestBody IncomeDeductionRequest request) { return service.createIncomeDeduction(incomeId, currentUser.id(), request); }
    @PutMapping("/income-deductions/{id}") public IncomeDeductionResponse updateDeduction(@PathVariable UUID id, @Valid @RequestBody IncomeDeductionUpdateRequest request) { return service.updateIncomeDeduction(id, currentUser.id(), request); }
    @DeleteMapping("/income-deductions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDeduction(@PathVariable UUID id, @Valid @RequestBody IncomeStateRequest request) { service.deleteIncomeDeduction(id, currentUser.id(), request); }

    @GetMapping("/income/{incomeId}/receipts") public List<IncomeReceiptResponse> receipts(@PathVariable UUID incomeId) { return service.receipts(incomeId, currentUser.id()); }
    @GetMapping("/income/{incomeId}/receipt-history") public List<IncomeReceiptResponse> receiptHistory(@PathVariable UUID incomeId) { return service.receiptHistory(incomeId, currentUser.id()); }
    @PostMapping("/income/{incomeId}/receipts") @ResponseStatus(HttpStatus.CREATED)
    public IncomeReceiptResponse createReceipt(@PathVariable UUID incomeId, @Valid @RequestBody IncomeReceiptRequest request) { return service.createReceipt(incomeId, currentUser.id(), request); }
    @PutMapping("/income-receipts/{id}") public IncomeReceiptResponse updateReceipt(@PathVariable UUID id, @Valid @RequestBody IncomeReceiptUpdateRequest request) { return service.updateReceipt(id, currentUser.id(), request); }
    @DeleteMapping("/income-receipts/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteReceipt(@PathVariable UUID id, @Valid @RequestBody IncomeStateRequest request) { service.deleteReceipt(id, currentUser.id(), request); }

    @GetMapping("/income-receipts/{receiptId}/deductions") public List<IncomeReceiptDeductionResponse> receiptDeductions(@PathVariable UUID receiptId) { return service.receiptDeductions(receiptId, currentUser.id()); }
    @GetMapping("/income-receipts/{receiptId}/deduction-history") public List<IncomeReceiptDeductionResponse> receiptDeductionHistory(@PathVariable UUID receiptId) { return service.receiptDeductionHistory(receiptId, currentUser.id()); }
    @PostMapping("/income-receipts/{receiptId}/deductions") @ResponseStatus(HttpStatus.CREATED)
    public IncomeReceiptDeductionResponse createReceiptDeduction(@PathVariable UUID receiptId, @Valid @RequestBody IncomeReceiptDeductionRequest request) { return service.createReceiptDeduction(receiptId, currentUser.id(), request); }
    @PutMapping("/income-receipt-deductions/{id}") public IncomeReceiptDeductionResponse updateReceiptDeduction(@PathVariable UUID id, @Valid @RequestBody IncomeReceiptDeductionUpdateRequest request) { return service.updateReceiptDeduction(id, currentUser.id(), request); }
    @DeleteMapping("/income-receipt-deductions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteReceiptDeduction(@PathVariable UUID id, @Valid @RequestBody IncomeStateRequest request) { service.deleteReceiptDeduction(id, currentUser.id(), request); }

    @GetMapping("/income/{incomeId}/availability") public IncomeAvailability availability(@PathVariable UUID incomeId) { return service.incomeAvailability(incomeId, currentUser.id()); }
    @GetMapping("/income/{incomeId}/funding-allocations") public List<FundingAllocationResponse> fundingAllocations(@PathVariable UUID incomeId) { return service.incomeFundingAllocations(incomeId, currentUser.id()); }
}
