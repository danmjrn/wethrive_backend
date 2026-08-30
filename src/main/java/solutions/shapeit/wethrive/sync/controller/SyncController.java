package solutions.shapeit.wethrive.sync.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import solutions.shapeit.wethrive.identity.service.CurrentUser;
import solutions.shapeit.wethrive.sync.dto.SyncDtos.FullResyncRequest;
import solutions.shapeit.wethrive.sync.dto.SyncDtos.FullResyncResponse;
import solutions.shapeit.wethrive.sync.dto.SyncDtos.PullResponse;
import solutions.shapeit.wethrive.sync.dto.SyncDtos.PushRequest;
import solutions.shapeit.wethrive.sync.dto.SyncDtos.PushResponse;
import solutions.shapeit.wethrive.sync.service.SyncService;

@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {
    private final SyncService service; private final CurrentUser currentUser;
    public SyncController(SyncService service, CurrentUser currentUser) { this.service = service; this.currentUser = currentUser; }
    @PostMapping("/push") public PushResponse push(@Valid @RequestBody PushRequest request) { return service.push(currentUser.id(), request.operations()); }
    @GetMapping("/pull") public PullResponse pull(@RequestParam UUID deviceId, @RequestParam(defaultValue = "0") long cursor) { return service.pull(currentUser.id(), deviceId, cursor); }
    @PostMapping("/full-resync") public FullResyncResponse full(@Valid @RequestBody FullResyncRequest request) { return service.fullResync(currentUser.id(), request.deviceId()); }
}
