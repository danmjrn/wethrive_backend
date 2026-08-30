package solutions.shapeit.wethrive.space.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import solutions.shapeit.wethrive.identity.service.CurrentUser;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.InvitationRequest;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.InvitationResponse;
import solutions.shapeit.wethrive.space.service.InvitationService;

@RestController
@RequestMapping("/api/v1")
public class InvitationController {
    private final InvitationService service;
    private final CurrentUser currentUser;

    public InvitationController(InvitationService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PostMapping("/spaces/{spaceId}/invitations") @ResponseStatus(HttpStatus.CREATED)
    public InvitationResponse create(@PathVariable UUID spaceId, @Valid @RequestBody InvitationRequest request) {
        return service.create(spaceId, currentUser.id(), request);
    }
    @GetMapping("/spaces/{spaceId}/invitations") public List<InvitationResponse> list(@PathVariable UUID spaceId) {
        return service.list(spaceId, currentUser.id());
    }
    @DeleteMapping("/spaces/{spaceId}/invitations/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID spaceId, @PathVariable UUID id) { service.revoke(spaceId, id, currentUser.id()); }
    @PostMapping("/spaces/{spaceId}/invitations/{id}/resend")
    public InvitationResponse resend(@PathVariable UUID spaceId, @PathVariable UUID id) {
        return service.resend(spaceId, id, currentUser.id());
    }
    @PostMapping("/invitations/{token}/accept") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void accept(@PathVariable String token) { service.accept(token, currentUser.id()); }
    @PostMapping("/invitations/{token}/decline") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@PathVariable String token) { service.decline(token, currentUser.id()); }
}
