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
import solutions.shapeit.wethrive.space.dto.SpaceDtos.ChangeRoleRequest;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.MemberResponse;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.TransferOwnershipRequest;
import solutions.shapeit.wethrive.space.service.MembershipService;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}")
public class MembershipController {
    private final MembershipService service;
    private final CurrentUser currentUser;

    public MembershipController(MembershipService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/members") public List<MemberResponse> members(@PathVariable UUID spaceId) {
        return service.list(spaceId, currentUser.id());
    }
    @PutMapping("/members/{memberId}") public MemberResponse role(@PathVariable UUID spaceId, @PathVariable UUID memberId,
                                                                  @Valid @RequestBody ChangeRoleRequest request) {
        return service.changeRole(spaceId, memberId, currentUser.id(), request);
    }
    @DeleteMapping("/members/{memberId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID spaceId, @PathVariable UUID memberId) { service.remove(spaceId, memberId, currentUser.id()); }
    @PostMapping("/leave") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable UUID spaceId) { service.leave(spaceId, currentUser.id()); }
    @PostMapping("/transfer-ownership") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void transfer(@PathVariable UUID spaceId, @Valid @RequestBody TransferOwnershipRequest request) {
        service.transfer(spaceId, request.newOwnerMembershipId(), currentUser.id());
    }
}
