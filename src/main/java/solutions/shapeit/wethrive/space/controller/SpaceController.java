package solutions.shapeit.wethrive.space.controller;

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
import solutions.shapeit.wethrive.identity.service.CurrentUser;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.CreateSpaceRequest;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.SpaceResponse;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.UpdateSpaceRequest;
import solutions.shapeit.wethrive.space.service.SpaceService;

@RestController
@RequestMapping("/api/v1/spaces")
public class SpaceController {
    private final SpaceService service;
    private final CurrentUser currentUser;

    public SpaceController(SpaceService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping public List<SpaceResponse> list() { return service.list(currentUser.id()); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public SpaceResponse create(@Valid @RequestBody CreateSpaceRequest request) { return service.createHousehold(currentUser.id(), request); }
    @GetMapping("/{id}") public SpaceResponse get(@PathVariable UUID id) { return service.get(id, currentUser.id()); }
    @PutMapping("/{id}") public SpaceResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateSpaceRequest request) {
        return service.update(id, currentUser.id(), request);
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { service.delete(id, currentUser.id()); }
}
