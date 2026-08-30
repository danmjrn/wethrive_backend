package solutions.shapeit.wethrive.reminder.controller;

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
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.OccurrenceResponse;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.BudgetScheduleDeleteRequest;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.BudgetScheduleRequest;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.BudgetScheduleResponse;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.BudgetScheduleUpdateRequest;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.PauseRequest;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.PreferenceRequest;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.PreferenceResponse;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.PreferenceUpdateRequest;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.PeriodReviewRequest;
import solutions.shapeit.wethrive.reminder.dto.ReminderDtos.PeriodReviewResponse;
import solutions.shapeit.wethrive.reminder.service.ReminderService;

@RestController
@RequestMapping("/api/v1/reminders")
public class ReminderController {
    private final ReminderService service; private final CurrentUser currentUser;
    public ReminderController(ReminderService service, CurrentUser currentUser) { this.service = service; this.currentUser = currentUser; }
    @GetMapping("/preferences") public List<PreferenceResponse> list() { return service.list(currentUser.id()); }
    @PostMapping("/preferences") @ResponseStatus(HttpStatus.CREATED) public PreferenceResponse create(@Valid @RequestBody PreferenceRequest request) { return service.create(currentUser.id(), request); }
    @PutMapping("/preferences/{id}") public PreferenceResponse update(@PathVariable UUID id, @Valid @RequestBody PreferenceUpdateRequest request) { return service.update(currentUser.id(), id, request); }
    @DeleteMapping("/preferences/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID id) { service.delete(currentUser.id(), id); }
    @GetMapping("/budget-schedules")
    public List<BudgetScheduleResponse> listBudgetSchedules() {
        return service.listBudgetSchedules(currentUser.id());
    }
    @GetMapping("/period-reviews")
    public List<PeriodReviewResponse> listPeriodReviews() {
        return service.listPeriodReviews(currentUser.id());
    }
    @PostMapping("/period-reviews") @ResponseStatus(HttpStatus.CREATED)
    public PeriodReviewResponse reviewPeriod(@Valid @RequestBody PeriodReviewRequest request) {
        return service.reviewPeriod(currentUser.id(), request);
    }
    @PostMapping("/budget-schedules") @ResponseStatus(HttpStatus.CREATED)
    public BudgetScheduleResponse createBudgetSchedule(@Valid @RequestBody BudgetScheduleRequest request) {
        return service.createBudgetSchedule(currentUser.id(), request);
    }
    @PutMapping("/budget-schedules/{id}")
    public BudgetScheduleResponse updateBudgetSchedule(@PathVariable UUID id,
                                                        @Valid @RequestBody BudgetScheduleUpdateRequest request) {
        return service.updateBudgetSchedule(currentUser.id(), id, request);
    }
    @DeleteMapping("/budget-schedules/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBudgetSchedule(@PathVariable UUID id,
                                     @Valid @RequestBody BudgetScheduleDeleteRequest request) {
        service.deleteBudgetSchedule(currentUser.id(), id, request.version());
    }
    @PostMapping("/preferences/{id}/pause") public PreferenceResponse pause(@PathVariable UUID id, @Valid @RequestBody PauseRequest request) { return service.pause(currentUser.id(), id, request.until()); }
    @PostMapping("/preferences/{id}/resume") public PreferenceResponse resume(@PathVariable UUID id) { return service.resume(currentUser.id(), id); }
    @GetMapping("/upcoming") public List<OccurrenceResponse> upcoming() { return service.upcoming(currentUser.id()); }
    @PostMapping("/{id}/acknowledge") @ResponseStatus(HttpStatus.NO_CONTENT) public void acknowledge(@PathVariable UUID id) { service.acknowledge(currentUser.id(), id); }
    @PostMapping("/{id}/skip") @ResponseStatus(HttpStatus.NO_CONTENT) public void skip(@PathVariable UUID id) { service.skip(currentUser.id(), id); }
}
