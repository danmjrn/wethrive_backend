package solutions.shapeit.wethrive.notification.controller;

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
import solutions.shapeit.wethrive.notification.dto.NotificationDtos.SubscriptionRequest;
import solutions.shapeit.wethrive.notification.dto.NotificationDtos.SubscriptionResponse;
import solutions.shapeit.wethrive.notification.dto.NotificationDtos.TestNotificationResponse;
import solutions.shapeit.wethrive.notification.service.PushSubscriptionService;

@RestController
@RequestMapping("/api/v1/notifications")
public class PushSubscriptionController {
    private final PushSubscriptionService service; private final CurrentUser currentUser;
    public PushSubscriptionController(PushSubscriptionService service, CurrentUser currentUser) { this.service = service; this.currentUser = currentUser; }
    @PostMapping("/subscriptions") @ResponseStatus(HttpStatus.CREATED) public SubscriptionResponse save(@Valid @RequestBody SubscriptionRequest request) { return service.save(currentUser.id(), request); }
    @GetMapping("/subscriptions") public List<SubscriptionResponse> list() { return service.list(currentUser.id()); }
    @DeleteMapping("/subscriptions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void revoke(@PathVariable UUID id) { service.revoke(currentUser.id(), id); }
    @PostMapping("/test") public TestNotificationResponse test() { return service.test(currentUser.id()); }
}
