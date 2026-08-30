package solutions.shapeit.wethrive.finance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetStatus;
import solutions.shapeit.wethrive.common.model.SpaceOwnedEntity;

@Entity
@Table(name = "budget_months")
@Getter @Setter @NoArgsConstructor
public class BudgetMonth extends SpaceOwnedEntity {
    @Column(nullable = false) private int year;
    @Column(nullable = false) private int month;
    @Column(nullable = false, length = 120) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private BudgetStatus status;
    @Column(length = 2000) private String notes;
}
