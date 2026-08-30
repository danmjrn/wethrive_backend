package solutions.shapeit.wethrive.identity.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import solutions.shapeit.wethrive.identity.dto.SettingsDtos.SettingsRequest;
import solutions.shapeit.wethrive.identity.dto.SettingsDtos.SettingsResponse;
import solutions.shapeit.wethrive.identity.service.CurrentUser;
import solutions.shapeit.wethrive.identity.service.SettingsService;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {
    private final SettingsService service;
    private final CurrentUser currentUser;
    public SettingsController(SettingsService service, CurrentUser currentUser) { this.service = service; this.currentUser = currentUser; }
    @GetMapping public SettingsResponse get() { return service.get(currentUser.id()); }
    @PutMapping public SettingsResponse update(@Valid @RequestBody SettingsRequest request) { return service.update(currentUser.id(), request); }
}
