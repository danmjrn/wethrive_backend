package solutions.shapeit.wethrive.common.model;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@MappedSuperclass
@Getter
@Setter
public abstract class SpaceOwnedEntity extends BaseEntity {
    @Column(nullable = false, updatable = false)
    private UUID spaceId;

    @Column(nullable = false, updatable = false)
    private UUID createdByUserId;

    @Column(nullable = false)
    private UUID updatedByUserId;
}
