package solutions.shapeit.wethrive.identity.controller;

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
import solutions.shapeit.wethrive.identity.dto.AuthDtos.ApplicationInvitationRequest;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.ApplicationInvitationResponse;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.TokenRequest;
import solutions.shapeit.wethrive.identity.service.ApplicationInvitationService;
import solutions.shapeit.wethrive.identity.service.CurrentUser;

@RestController
@RequestMapping("/api/v1")
public class ApplicationAdminController {
    private final ApplicationInvitationService invitations;
    private final CurrentUser currentUser;

    public ApplicationAdminController(ApplicationInvitationService invitations, CurrentUser currentUser) {
        this.invitations = invitations;
        this.currentUser = currentUser;
    }

    @GetMapping("/admin/invitations")
    public List<ApplicationInvitationResponse> list() { return invitations.list(currentUser.id()); }

    @PostMapping("/admin/invitations") @ResponseStatus(HttpStatus.CREATED)
    public ApplicationInvitationResponse create(@Valid @RequestBody ApplicationInvitationRequest request) {
        return invitations.create(currentUser.id(), request.email());
    }

    @DeleteMapping("/admin/invitations/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id) { invitations.revoke(currentUser.id(), id); }

    @PostMapping("/application-invitations/accept") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void accept(@Valid @RequestBody TokenRequest request) { invitations.accept(currentUser.id(), request.token()); }

    @PostMapping("/application-invitations/decline") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@Valid @RequestBody TokenRequest request) { invitations.decline(currentUser.id(), request.token()); }
}
