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
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.CategoryRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.CategoryResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.CategoryUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeTypeRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeTypeResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeTypeUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.VersionRequest;
import solutions.shapeit.wethrive.finance.service.ReferenceFinanceService;
import solutions.shapeit.wethrive.identity.service.CurrentUser;

@RestController
@RequestMapping("/api/v1")
public class ReferenceFinanceController {
    private final ReferenceFinanceService service;
    private final CurrentUser currentUser;
    public ReferenceFinanceController(ReferenceFinanceService service, CurrentUser currentUser) { this.service = service; this.currentUser = currentUser; }

    @GetMapping("/spaces/{spaceId}/categories") public List<CategoryResponse> categories(@PathVariable UUID spaceId) { return service.categories(spaceId, currentUser.id()); }
    @PostMapping("/spaces/{spaceId}/categories") @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse category(@PathVariable UUID spaceId, @Valid @RequestBody CategoryRequest request) { return service.createCategory(spaceId, currentUser.id(), request); }
    @PutMapping("/categories/{id}") public CategoryResponse category(@PathVariable UUID id, @Valid @RequestBody CategoryUpdateRequest request) { return service.updateCategory(id, currentUser.id(), request); }
    @DeleteMapping("/categories/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveCategory(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) { service.archiveCategory(id, currentUser.id(), request); }

    @GetMapping("/spaces/{spaceId}/income-types") public List<IncomeTypeResponse> incomeTypes(@PathVariable UUID spaceId) { return service.incomeTypes(spaceId, currentUser.id()); }
    @PostMapping("/spaces/{spaceId}/income-types") @ResponseStatus(HttpStatus.CREATED)
    public IncomeTypeResponse incomeType(@PathVariable UUID spaceId, @Valid @RequestBody IncomeTypeRequest request) { return service.createIncomeType(spaceId, currentUser.id(), request); }
    @PutMapping("/income-types/{id}") public IncomeTypeResponse incomeType(@PathVariable UUID id, @Valid @RequestBody IncomeTypeUpdateRequest request) { return service.updateIncomeType(id, currentUser.id(), request); }
    @DeleteMapping("/income-types/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveIncomeType(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) { service.archiveIncomeType(id, currentUser.id(), request); }
}
