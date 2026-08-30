package solutions.shapeit.wethrive.finance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import solutions.shapeit.wethrive.common.domain.DomainEnums.FundingSourceType;
import solutions.shapeit.wethrive.common.model.SpaceOwnedEntity;

/**
 * A many-to-many financial link between an income source and a budget item. A non-zero planned
 * amount with a zero confirmed amount is a future funding plan, not cash that is available now.
 */
@Entity
@Table(name = "budget_funding_allocations")
@Getter @Setter @NoArgsConstructor
public class BudgetFundingAllocation extends SpaceOwnedEntity {
    @Column(nullable = false) private UUID budgetItemId;
    private UUID incomeEntryId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private FundingSourceType sourceType;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal plannedAmount;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal confirmedAllocatedAmount;
    private Instant allocatedAt;
    @Column(length = 60) private String timeZone;
    @Column(length = 2000) private String notes;
}
