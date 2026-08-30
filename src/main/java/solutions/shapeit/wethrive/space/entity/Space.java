package solutions.shapeit.wethrive.space.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SpaceType;
import solutions.shapeit.wethrive.common.model.BaseEntity;

@Entity
@Table(name = "spaces")
@Getter
@Setter
@NoArgsConstructor
public class Space extends BaseEntity {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SpaceType type;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, unique = true, length = 160)
    private String slug;

    @Column(nullable = false)
    private UUID ownerUserId;

    @Column(nullable = false, length = 3)
    private String currencyCode;

    @Column(nullable = false, length = 35)
    private String locale;

    @Column(nullable = false, length = 60)
    private String timeZone;
}
