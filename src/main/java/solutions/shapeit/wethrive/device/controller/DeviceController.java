package solutions.shapeit.wethrive.device.controller;

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
import solutions.shapeit.wethrive.device.dto.DeviceDtos.DeviceResponse;
import solutions.shapeit.wethrive.device.dto.DeviceDtos.OfflineGrantResponse;
import solutions.shapeit.wethrive.device.dto.DeviceDtos.UpdateDeviceRequest;
import solutions.shapeit.wethrive.device.dto.DeviceDtos.OfflineGrantVerificationRequest;
import solutions.shapeit.wethrive.device.dto.DeviceDtos.OfflineGrantVerificationResponse;
import solutions.shapeit.wethrive.device.dto.DeviceDtos.OfflineVerificationKeyResponse;
import solutions.shapeit.wethrive.device.service.DeviceService;
import solutions.shapeit.wethrive.identity.service.CurrentUser;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {
    private final DeviceService service;
    private final CurrentUser currentUser;
    public DeviceController(DeviceService service, CurrentUser currentUser) { this.service = service; this.currentUser = currentUser; }
    @GetMapping public List<DeviceResponse> list() { return service.list(currentUser.id()); }
    @PutMapping("/{id}") public DeviceResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateDeviceRequest request) { return service.update(currentUser.id(), id, request); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void revoke(@PathVariable UUID id) { service.revoke(currentUser.id(), id); }
    @PostMapping("/{id}/offline-grant") public OfflineGrantResponse grant(@PathVariable UUID id) { return service.grant(currentUser.id(), id); }
    @DeleteMapping("/{id}/offline-grant") @ResponseStatus(HttpStatus.NO_CONTENT) public void revokeGrant(@PathVariable UUID id) { service.revokeGrant(currentUser.id(), id); }
    @GetMapping("/offline-verification-key") public OfflineVerificationKeyResponse verificationKey() { return service.verificationKey(); }
    @PostMapping("/{id}/offline-grant/verify")
    public OfflineGrantVerificationResponse verify(@PathVariable UUID id,
            @Valid @RequestBody OfflineGrantVerificationRequest request) {
        return service.verifyGrant(currentUser.id(), id, request.grant());
    }
}
