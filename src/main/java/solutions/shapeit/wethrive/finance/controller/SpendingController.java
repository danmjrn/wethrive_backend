package solutions.shapeit.wethrive.finance.controller;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.MoveSpendingRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.RefundRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.SpendingRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.SpendingResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.SpendingUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.RestoreSpendingRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.VersionRequest;
import solutions.shapeit.wethrive.finance.service.SpendingService;
import solutions.shapeit.wethrive.identity.service.CurrentUser;

@RestController
@RequestMapping("/api/v1/spending")
public class SpendingController {
    private final SpendingService service;
    private final CurrentUser currentUser;
    public SpendingController(SpendingService service, CurrentUser currentUser) { this.service = service; this.currentUser = currentUser; }
    @GetMapping public Page<SpendingResponse> list(@RequestParam(required = false) UUID spaceId,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "50") int size) {
        return service.list(currentUser.id(), spaceId, from, to, page, size);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public SpendingResponse create(@Valid @RequestBody SpendingRequest request) { return service.create(currentUser.id(), request); }
    @GetMapping("/{id}") public SpendingResponse get(@PathVariable UUID id) { return service.get(id, currentUser.id()); }
    @PutMapping("/{id}") public SpendingResponse update(@PathVariable UUID id, @Valid @RequestBody SpendingUpdateRequest request) { return service.update(id, currentUser.id(), request); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        service.delete(id, currentUser.id(), request);
    }
    @PostMapping("/{id}/restore") public SpendingResponse restore(@PathVariable UUID id,
            @Valid @RequestBody RestoreSpendingRequest request) { return service.restore(id, currentUser.id(), request); }
    @PostMapping("/{id}/refund") @ResponseStatus(HttpStatus.CREATED) public SpendingResponse refund(@PathVariable UUID id, @Valid @RequestBody RefundRequest request) { return service.refund(id, currentUser.id(), request); }
    @PostMapping("/{id}/move") public SpendingResponse move(@PathVariable UUID id, @Valid @RequestBody MoveSpendingRequest request) { return service.move(id, currentUser.id(), request); }
}
