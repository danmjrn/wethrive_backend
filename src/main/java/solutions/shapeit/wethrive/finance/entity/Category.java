package solutions.shapeit.wethrive.finance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import solutions.shapeit.wethrive.common.model.SpaceOwnedEntity;

@Entity
@Table(name = "categories")
@Getter @Setter @NoArgsConstructor
public class Category extends SpaceOwnedEntity {
    @Column(nullable = false, length = 100) private String name;
    @Column(length = 60) private String icon;
    @Column(nullable = false) private int sortOrder;
    @Column(nullable = false) private boolean archived;
    @Column(nullable = false) private boolean systemDefault;
}
