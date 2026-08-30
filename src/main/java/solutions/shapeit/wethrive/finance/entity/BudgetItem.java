package solutions.shapeit.wethrive.finance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetItemType;
import solutions.shapeit.wethrive.common.model.SpaceOwnedEntity;

@Entity
@Table(name = "budget_items")
@Getter @Setter @NoArgsConstructor
public class BudgetItem extends SpaceOwnedEntity {
    @Column(nullable = false) private UUID budgetMonthId;
    @Column(nullable = false, length = 160) private String name;
    @Column(nullable = false) private UUID categoryId;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal plannedAmount;
    @Column(nullable = false) private boolean tracked;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private BudgetItemType itemType;
    @Column(nullable = false) private boolean recurring;
    @Column(nullable = false) private boolean rolloverEnabled;
    @Column(length = 2000) private String notes;
    @Column(nullable = false) private int sortOrder;
}
