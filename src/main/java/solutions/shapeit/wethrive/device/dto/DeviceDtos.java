package solutions.shapeit.wethrive.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import solutions.shapeit.wethrive.common.domain.DomainEnums.Role;

public final class DeviceDtos {
    private DeviceDtos() {}
    public record DeviceResponse(UUID id, String displayName, String platform, String browser, Instant lastSeenAt,
                                 boolean offlineAccessEnabled, Instant offlineGrantExpiresAt, long version) {}
    public record UpdateDeviceRequest(@NotBlank @Size(max = 120) String displayName, @NotNull long version) {}
    public record OfflineGrantResponse(String grant, String algorithm, String keyId, UUID userId, UUID deviceId,
                                       Instant issuedAt, Instant expiresAt, long permissionVersion,
                                       String authorizationFingerprint, List<UUID> authorizedSpaceIds,
                                       List<OfflineSpaceAuthorization> spaceAuthorizations, int grantVersion) {}
    public record OfflineVerificationKeyResponse(String algorithm, String namedCurve, String format,
                                                 String keyId, String publicKey, int grantVersion) {}
    public record OfflineGrantVerificationRequest(@NotBlank @Size(max = 16_384) String grant) {}
    public record OfflineGrantVerificationResponse(boolean valid, String algorithm, String keyId, UUID userId,
                                                    UUID deviceId, Instant issuedAt, Instant expiresAt,
                                                    long permissionVersion, String authorizationFingerprint,
                                                    List<UUID> authorizedSpaceIds,
                                                    List<OfflineSpaceAuthorization> spaceAuthorizations,
                                                    int grantVersion) {}
    public record OfflineSpaceAuthorization(UUID spaceId, Role role, long membershipVersion,
                                            List<String> capabilities) {}
}
