package solutions.shapeit.wethrive.sync.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import solutions.shapeit.wethrive.sync.entity.SyncOperation;

public interface SyncOperationRepository extends JpaRepository<SyncOperation, UUID> {}
