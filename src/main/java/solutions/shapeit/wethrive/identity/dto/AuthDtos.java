package solutions.shapeit.wethrive.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(@Email @NotBlank @Size(max = 320) String email,
                                  @NotBlank @Size(min = 12, max = 72) String password,
                                  @NotBlank @Size(max = 120) String displayName,
                                  @Size(max = 4096) String invitationToken) {}
    public record BootstrapRequest(@Email @NotBlank @Size(max = 320) String email,
                                   @NotBlank @Size(min = 12, max = 72) String password,
                                   @NotBlank @Size(max = 120) String displayName,
                                   @NotBlank @Size(max = 4096) String bootstrapToken,
                                   UUID deviceId, @Size(max = 120) String deviceName) {}
    public record LoginRequest(@Email @NotBlank @Size(max = 320) String email,
                               @NotBlank @Size(max = 72) String password,
                               UUID deviceId, @Size(max = 120) String deviceName,
                               @Size(max = 80) String platform, @Size(max = 80) String browser) {}
    public record TokenRequest(@NotBlank @Size(max = 4096) String token) {}
    public record ForgotPasswordRequest(@Email @NotBlank @Size(max = 320) String email) {}
    public record ResetPasswordRequest(@NotBlank @Size(max = 4096) String token,
                                       @NotBlank @Size(min = 12, max = 72) String newPassword) {}
    public record ChangePasswordRequest(@NotBlank @Size(max = 72) String currentPassword,
                                        @NotBlank @Size(min = 12, max = 72) String newPassword) {}
    public record DeleteAccountRequest(@NotBlank @Size(max = 72) String password) {}
    public record UpdateProfileRequest(@NotBlank @Size(max = 120) String displayName,
                                       @Size(max = 500) String avatarReference, @NotNull long version) {}
    public record UserResponse(UUID id, String email, String displayName, String avatarReference,
                               boolean emailVerified, Instant createdAt, long version) {}
    public record RegistrationResponse(String message, String verificationToken) {}
    public record AuthResponse(UserResponse user, UUID sessionId, UUID deviceId, Instant accessExpiresAt, Instant refreshExpiresAt) {}
    public record SessionResponse(UUID id, UUID deviceId, String deviceName, Instant issuedAt,
                                  Instant expiresAt, Instant lastUsedAt, boolean current) {}
    public record MessageResponse(String message) {}
}
