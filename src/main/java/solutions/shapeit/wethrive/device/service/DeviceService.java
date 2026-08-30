package solutions.shapeit.wethrive.device.service;

import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.device.dto.DeviceDtos.DeviceResponse;
import solutions.shapeit.wethrive.device.dto.DeviceDtos.OfflineGrantResponse;
import solutions.shapeit.wethrive.device.dto.DeviceDtos.UpdateDeviceRequest;
import solutions.shapeit.wethrive.device.dto.DeviceDtos.OfflineGrantVerificationResponse;
import solutions.shapeit.wethrive.device.dto.DeviceDtos.OfflineVerificationKeyResponse;
import solutions.shapeit.wethrive.device.entity.Device;
import solutions.shapeit.wethrive.device.repository.DeviceRepository;
import solutions.shapeit.wethrive.identity.repository.UserSessionRepository;
import solutions.shapeit.wethrive.notification.repository.PushSubscriptionRepository;

@Service
public class DeviceService {
    private final DeviceRepository devices;
    private final UserSessionRepository sessions;
    private final OfflineGrantService grants;
    private final PushSubscriptionRepository pushSubscriptions;
    private final Clock clock;

    public DeviceService(DeviceRepository devices, UserSessionRepository sessions, OfflineGrantService grants,
                         PushSubscriptionRepository pushSubscriptions, Clock clock) {
        this.devices = devices;
        this.sessions = sessions;
        this.grants = grants;
        this.pushSubscriptions = pushSubscriptions;
        this.clock = clock;
    }

    @Transactional
    public List<DeviceResponse> list(UUID userId) { return devices.findAllByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(userId).stream().map(this::map).toList(); }

    @Transactional
    public DeviceResponse update(UUID userId, UUID id, UpdateDeviceRequest request) {
        Device device = require(userId, id);
        if (device.getVersion() != request.version()) throw ApiException.conflict("The device was changed elsewhere");
        device.setDisplayName(request.displayName().trim());
        return map(devices.save(device));
    }

    @Transactional
    public void revoke(UUID userId, UUID id) {
        Device device = require(userId, id);
        Instant now = Instant.now(clock);
        device.setRevokedAt(now);
        device.setOfflineAccessEnabled(false);
        device.setOfflineGrantExpiresAt(null);
        devices.save(device);
        sessions.findAllByDeviceIdAndRevokedAtIsNull(id).forEach(session -> { session.setRevokedAt(now); sessions.save(session); });
        pushSubscriptions.findAllByDeviceIdAndRevokedAtIsNull(id).forEach(subscription -> {
            subscription.setEnabled(false);
            subscription.setRevokedAt(now);
            pushSubscriptions.save(subscription);
        });
    }

    @Transactional
    public OfflineGrantResponse grant(UUID userId, UUID id) {
        Device device = require(userId, id);
        OfflineGrantResponse grant = grants.issue(userId, id);
        device.setOfflineAccessEnabled(true);
        device.setOfflineGrantExpiresAt(grant.expiresAt());
        devices.save(device);
        return grant;
    }

    @Transactional
    public void revokeGrant(UUID userId, UUID id) {
        Device device = require(userId, id);
        device.setOfflineAccessEnabled(false);
        device.setOfflineGrantExpiresAt(null);
        devices.save(device);
    }

    public OfflineVerificationKeyResponse verificationKey() { return grants.verificationKey(); }

    @Transactional
    public OfflineGrantVerificationResponse verifyGrant(UUID userId, UUID id, String grant) {
        require(userId, id);
        return grants.verify(userId, id, grant);
    }

    private Device require(UUID userId, UUID id) { return devices.findByIdAndUserIdAndRevokedAtIsNull(id, userId).orElseThrow(() -> ApiException.notFound("Device")); }
    private DeviceResponse map(Device d) { return new DeviceResponse(d.getId(), d.getDisplayName(), d.getPlatform(), d.getBrowser(), d.getLastSeenAt(), d.isOfflineAccessEnabled(), d.getOfflineGrantExpiresAt(), d.getVersion()); }
}
