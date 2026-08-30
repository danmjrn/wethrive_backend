package solutions.shapeit.wethrive.identity.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import solutions.shapeit.wethrive.common.config.ApplicationProperties;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.AuthResponse;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.BootstrapRequest;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.ChangePasswordRequest;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.DeleteAccountRequest;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.ForgotPasswordRequest;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.LoginRequest;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.MessageResponse;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.RegisterRequest;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.RegistrationResponse;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.ResetPasswordRequest;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.SessionResponse;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.TokenRequest;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.UserResponse;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.UpdateProfileRequest;
import solutions.shapeit.wethrive.identity.security.SessionAuthenticationFilter;
import solutions.shapeit.wethrive.identity.service.AuthService;
import solutions.shapeit.wethrive.identity.service.CurrentUser;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service;
    private final CurrentUser currentUser;
    private final ApplicationProperties properties;

    public AuthController(AuthService service, CurrentUser currentUser, ApplicationProperties properties) {
        this.service = service;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    @GetMapping("/csrf") public Map<String, String> csrf(CsrfToken token) { return Map.of("headerName", token.getHeaderName(), "token", token.getToken()); }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public RegistrationResponse register(@Valid @RequestBody RegisterRequest request) { return service.register(request); }
    @PostMapping("/bootstrap") @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse bootstrap(@Valid @RequestBody BootstrapRequest request, HttpServletRequest http, HttpServletResponse response) {
        return issue(service.bootstrap(request, http.getHeader(HttpHeaders.USER_AGENT)), response);
    }
    @PostMapping("/login") public AuthResponse login(@Valid @RequestBody LoginRequest request,
                                                      HttpServletRequest http, HttpServletResponse response) {
        return issue(service.login(request, http.getHeader(HttpHeaders.USER_AGENT)), response);
    }
    @PostMapping("/refresh") public AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        return issue(service.refresh(cookie(request, SessionAuthenticationFilter.REFRESH_COOKIE)), response);
    }
    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        service.logout(cookie(request, SessionAuthenticationFilter.ACCESS_COOKIE), cookie(request, SessionAuthenticationFilter.REFRESH_COOKIE));
        clearCookies(response);
    }
    @PostMapping("/verify-email") public MessageResponse verify(@Valid @RequestBody TokenRequest request) {
        service.verifyEmail(request.token());
        return new MessageResponse("Email verified");
    }
    @PostMapping("/forgot-password") public MessageResponse forgot(@Valid @RequestBody ForgotPasswordRequest request) {
        service.forgotPassword(request.email());
        return new MessageResponse("If the account exists, password reset instructions will be sent");
    }
    @PostMapping("/reset-password") public MessageResponse reset(@Valid @RequestBody ResetPasswordRequest request) {
        service.resetPassword(request.token(), request.newPassword());
        return new MessageResponse("Password reset; sign in again");
    }
    @PostMapping("/change-password") public MessageResponse change(@Valid @RequestBody ChangePasswordRequest request) {
        service.changePassword(currentUser.id(), request);
        return new MessageResponse("Password changed; sign in again");
    }
    @GetMapping("/me") public UserResponse me() { return service.current(currentUser.id()); }
    @GetMapping("/session") public AuthResponse session(HttpServletRequest request) {
        return service.currentSession(currentUser.id(), cookie(request, SessionAuthenticationFilter.ACCESS_COOKIE));
    }
    @PutMapping("/me") public UserResponse updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return service.updateProfile(currentUser.id(), request);
    }
    @GetMapping("/sessions") public List<SessionResponse> sessions(HttpServletRequest request) {
        return service.sessions(currentUser.id(), cookie(request, SessionAuthenticationFilter.ACCESS_COOKIE));
    }
    @DeleteMapping("/sessions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id) { service.revokeSession(currentUser.id(), id); }
    @DeleteMapping("/sessions/others") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeOthers(HttpServletRequest request) {
        service.revokeOtherSessions(currentUser.id(), cookie(request, SessionAuthenticationFilter.ACCESS_COOKIE));
    }
    @DeleteMapping("/account") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@Valid @RequestBody DeleteAccountRequest request, HttpServletResponse response) {
        service.deleteAccount(currentUser.id(), request.password());
        clearCookies(response);
    }

    private AuthResponse issue(AuthService.SessionIssue issue, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(SessionAuthenticationFilter.ACCESS_COOKIE, issue.accessToken(),
                properties.security().accessSessionTtl(), "/").toString());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(SessionAuthenticationFilter.REFRESH_COOKIE, issue.refreshToken(),
                properties.security().refreshSessionTtl(), "/api/v1/auth").toString());
        return issue.response();
    }

    private ResponseCookie cookie(String name, String value, Duration maxAge, String path) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(properties.security().secureCookies())
                .sameSite(properties.security().sameSite()).path(path).maxAge(maxAge).build();
    }

    private void clearCookies(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(SessionAuthenticationFilter.ACCESS_COOKIE, "", Duration.ZERO, "/").toString());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(SessionAuthenticationFilter.REFRESH_COOKIE, "", Duration.ZERO, "/api/v1/auth").toString());
    }

    private String cookie(HttpServletRequest request, String name) { return SessionAuthenticationFilter.cookie(request, name); }
}
