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
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetItemRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetItemResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetItemUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.FundingAllocationRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.FundingAllocationResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.FundingAllocationUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.FundingSummary;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeStateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.VersionRequest;
import solutions.shapeit.wethrive.finance.service.BudgetService;
import solutions.shapeit.wethrive.identity.service.CurrentUser;

@RestController
@RequestMapping("/api/v1")
public class BudgetItemController {
    private final BudgetService service; private final CurrentUser currentUser;
    public BudgetItemController(BudgetService service, CurrentUser currentUser) { this.service = service; this.currentUser = currentUser; }
    @GetMapping("/budgets/{budgetId}/items") public List<BudgetItemResponse> list(@PathVariable UUID budgetId) { return service.items(budgetId, currentUser.id()); }
    @PostMapping("/budgets/{budgetId}/items") @ResponseStatus(HttpStatus.CREATED)
    public BudgetItemResponse create(@PathVariable UUID budgetId, @Valid @RequestBody BudgetItemRequest request) { return service.createItem(budgetId, currentUser.id(), request); }
    @GetMapping("/budget-items/{id}") public BudgetItemResponse get(@PathVariable UUID id) { return service.getItem(id, currentUser.id()); }
    @PutMapping("/budget-items/{id}") public BudgetItemResponse update(@PathVariable UUID id, @Valid @RequestBody BudgetItemUpdateRequest request) { return service.updateItem(id, currentUser.id(), request); }
    @DeleteMapping("/budget-items/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        service.deleteItem(id, currentUser.id(), request);
    }

    @GetMapping("/budget-items/{itemId}/funding-allocations") public List<FundingAllocationResponse> fundingAllocations(@PathVariable UUID itemId) { return service.fundingAllocations(itemId, currentUser.id()); }
    @PostMapping("/budget-items/{itemId}/funding-allocations") @ResponseStatus(HttpStatus.CREATED)
    public FundingAllocationResponse createFundingAllocation(@PathVariable UUID itemId, @Valid @RequestBody FundingAllocationRequest request) { return service.createFundingAllocation(itemId, currentUser.id(), request); }
    @PutMapping("/funding-allocations/{id}") public FundingAllocationResponse updateFundingAllocation(@PathVariable UUID id, @Valid @RequestBody FundingAllocationUpdateRequest request) { return service.updateFundingAllocation(id, currentUser.id(), request); }
    @DeleteMapping("/funding-allocations/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reverseFundingAllocation(@PathVariable UUID id, @Valid @RequestBody IncomeStateRequest request) { service.reverseFundingAllocation(id, currentUser.id(), request); }
    @GetMapping("/budget-items/{itemId}/funding-summary") public FundingSummary fundingSummary(@PathVariable UUID itemId) { return service.fundingSummary(itemId, currentUser.id()); }
}
