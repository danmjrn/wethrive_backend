package solutions.shapeit.wethrive.device.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import solutions.shapeit.wethrive.device.entity.Device;

public interface DeviceRepository extends JpaRepository<Device, UUID> {
    List<Device> findAllByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(UUID userId);
    Optional<Device> findByIdAndUserIdAndRevokedAtIsNull(UUID id, UUID userId);
}
