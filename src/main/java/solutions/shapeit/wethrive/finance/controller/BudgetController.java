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
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetCopyRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetFundingSummary;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.BudgetUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.FundingAllocationResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.VersionRequest;
import solutions.shapeit.wethrive.finance.service.BudgetService;
import solutions.shapeit.wethrive.identity.service.CurrentUser;

@RestController
@RequestMapping("/api/v1")
public class BudgetController {
    private final BudgetService service;
    private final CurrentUser currentUser;
    public BudgetController(BudgetService service, CurrentUser currentUser) { this.service = service; this.currentUser = currentUser; }
    @GetMapping("/spaces/{spaceId}/budgets") public List<BudgetResponse> list(@PathVariable UUID spaceId) { return service.list(spaceId, currentUser.id()); }
    @PostMapping("/spaces/{spaceId}/budgets") @ResponseStatus(HttpStatus.CREATED)
    public BudgetResponse create(@PathVariable UUID spaceId, @Valid @RequestBody BudgetRequest request) { return service.create(spaceId, currentUser.id(), request); }
    @GetMapping("/budgets/{id}") public BudgetResponse get(@PathVariable UUID id) { return service.get(id, currentUser.id()); }
    @PutMapping("/budgets/{id}") public BudgetResponse update(@PathVariable UUID id, @Valid @RequestBody BudgetUpdateRequest request) { return service.update(id, currentUser.id(), request); }
    @DeleteMapping("/budgets/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) { service.delete(id, currentUser.id(), request); }
    @PostMapping("/budgets/{id}/close")
    public BudgetResponse close(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) { return service.close(id, currentUser.id(), request); }
    @PostMapping("/budgets/{id}/reopen")
    public BudgetResponse reopen(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) { return service.reopen(id, currentUser.id(), request); }
    @PostMapping("/budgets/{id}/copy") @ResponseStatus(HttpStatus.CREATED)
    public BudgetResponse copy(@PathVariable UUID id, @Valid @RequestBody BudgetCopyRequest request) { return service.copy(id, currentUser.id(), request); }
    @GetMapping("/budgets/{id}/funding-summary") public BudgetFundingSummary fundingSummary(@PathVariable UUID id) { return service.budgetFundingSummary(id, currentUser.id()); }
    @GetMapping("/spaces/{spaceId}/funding-allocation-history")
    public List<FundingAllocationResponse> fundingHistory(@PathVariable UUID spaceId) {
        return service.fundingAllocationHistory(spaceId, currentUser.id());
    }
}
