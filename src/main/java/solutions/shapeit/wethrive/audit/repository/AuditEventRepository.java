package solutions.shapeit.wethrive.audit.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import solutions.shapeit.wethrive.audit.entity.AuditEvent;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {}
