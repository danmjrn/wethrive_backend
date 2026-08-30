package solutions.shapeit.wethrive.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import solutions.shapeit.wethrive.finance.service.ReferenceDataService;

/**
 * Proves the V7 palette/category work and V8 legacy-refund guards from the V6 schema.
 *
 * @author Daniel Jr Nkulu
 */
@Testcontainers
class PublicReleaseDomainMigrationIT {
    private static final String OWNER = "20000000-0000-0000-0000-000000000001";
    private static final String FIRST_SPACE = "20000000-0000-0000-0000-000000000002";
    private static final String SECOND_SPACE = "20000000-0000-0000-0000-000000000003";
    private static final String ORIGINAL_EXPENSE = "20000000-0000-0000-0000-000000000010";
    private static final String LEGACY_REFUND = "20000000-0000-0000-0000-000000000011";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine")
            .withDatabaseName("wethrive_public_release_domain")
            .withUsername("wethrive")
            .withPassword("test-password");

    @Test
    void publicReleaseMigrationsBackfillDefaultsAndHardenRefundLinkage() throws Exception {
        Flyway throughV6 = flyway("6");
        assertThat(throughV6.migrate().targetSchemaVersion).hasToString("6");
        seedV6Data();

        Flyway latest = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load();
        var result = latest.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(2);
        assertThat(result.targetSchemaVersion).hasToString("8");

        try (Connection connection = connection(); var statement = connection.createStatement()) {
            assertThat(referenceCategoryNames(statement)).containsExactlyElementsOf(
                    ReferenceDataService.DEFAULT_CATEGORIES);
            assertThat(count(statement, "categories", "space_id = '" + SECOND_SPACE + "'"))
                    .isEqualTo(ReferenceDataService.DEFAULT_CATEGORIES.size());
            assertThat(count(statement, "categories", "space_id = '" + FIRST_SPACE + "'"))
                    .isEqualTo(ReferenceDataService.DEFAULT_CATEGORIES.size() + 1L);
            assertThat(count(statement, "categories", "space_id = '" + FIRST_SPACE
                    + "' and lower(name) = 'rent'"))
                    .isEqualTo(1);
            try (var row = statement.executeQuery("""
                    select name, archived, system_default
                      from categories
                     where space_id = '%s' and lower(name) = 'rent'
                    """.formatted(FIRST_SPACE))) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("name")).isEqualTo("rent");
                assertThat(row.getBoolean("archived")).isTrue();
                assertThat(row.getBoolean("system_default")).isFalse();
            }

            try (var row = statement.executeQuery("select palette from user_settings")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("palette")).isEqualTo("ORIGINAL");
            }
            statement.execute("update user_settings set palette = 'MONOCHROME'");
            assertThatThrownBy(() -> statement.execute("update user_settings set palette = 'SEPIA'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_user_settings_palette");

            try (var row = statement.executeQuery("""
                    select refund_for_spending_entry_id
                      from spending_entries
                     where id = '%s'
                    """.formatted(LEGACY_REFUND))) {
                assertThat(row.next()).isTrue();
                assertThat(row.getObject(1)).isNull();
            }

            assertThatThrownBy(() -> statement.execute(
                    linkedRefundInsert("20000000-0000-0000-0000-000000000014", "100.00")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("active refunds cannot exceed active expenses");
            assertThatThrownBy(() -> statement.execute(unlinkedRefundInsert(
                    "20000000-0000-0000-0000-000000000015", "10.00")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("must identify their original expense");

            statement.execute(linkedRefundInsert("20000000-0000-0000-0000-000000000012", "25.00"));
            assertThatThrownBy(() -> statement.execute(
                    linkedRefundInsert("20000000-0000-0000-0000-000000000013", "80.00")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("active refunds cannot exceed");
            assertThatThrownBy(() -> statement.execute("""
                    update spending_entries set amount = 30.00 where id = '%s'
                    """.formatted(ORIGINAL_EXPENSE)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("active refunds cannot exceed active expenses");
            assertThatThrownBy(() -> statement.execute("""
                    update spending_entries set deleted_at = now() where id = '%s'
                    """.formatted(ORIGINAL_EXPENSE)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("active linked refunds");

            statement.execute("""
                    insert into budget_items(id,space_id,created_by_user_id,updated_by_user_id,budget_month_id,
                        name,category_id,planned_amount,tracked,item_type,recurring,rollover_enabled,sort_order,
                        created_at,updated_at,version)
                    values ('20000000-0000-0000-0000-000000000016','%s','%s','%s',
                        '20000000-0000-0000-0000-000000000008','Other',
                        '20000000-0000-0000-0000-000000000006',0.00,true,'PLANNED',false,false,2,now(),now(),0)
                    """.formatted(FIRST_SPACE, OWNER, OWNER));
            assertThatThrownBy(() -> statement.execute("""
                    update spending_entries
                       set budget_item_id = '20000000-0000-0000-0000-000000000016'
                     where id = '%s'
                    """.formatted(ORIGINAL_EXPENSE)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("leave active refunds uncovered");

            statement.execute("""
                    update spending_entries set title = 'Legacy refund (reviewed)', amount = 20.00
                     where id = '%s'
                    """.formatted(LEGACY_REFUND));
            assertThatThrownBy(() -> statement.execute("""
                    update spending_entries set amount = 21.00 where id = '%s'
                    """.formatted(LEGACY_REFUND)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("cannot be increased before reconciliation");
            assertThatThrownBy(() -> statement.execute("""
                    update spending_entries
                       set budget_item_id = '20000000-0000-0000-0000-000000000016'
                     where id = '%s'
                    """.formatted(LEGACY_REFUND)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("cannot be moved before reconciliation");
            statement.execute("update spending_entries set deleted_at = now() where id = '" + LEGACY_REFUND + "'");
            assertThatThrownBy(() -> statement.execute("""
                    update spending_entries set deleted_at = null where id = '%s'
                    """.formatted(LEGACY_REFUND)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("cannot be restored before reconciliation");
            assertThat(count(statement, "spending_entries", "id = '" + LEGACY_REFUND + "' and deleted_at is not null"))
                    .isEqualTo(1);

            long beforeRepeat = count(statement, "categories", "true");
            statement.execute("select wethrive_backfill_standard_categories()");
            assertThat(count(statement, "categories", "true")).isEqualTo(beforeRepeat);
        }

        assertThat(latest.migrate().migrationsExecuted).isZero();
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(target)
                .load();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void seedV6Data() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("""
                    insert into app_users(id,email,normalized_email,password_hash,display_name,created_at,updated_at,version)
                    values ('%s','owner@example.test','owner@example.test','hash','Owner',now(),now(),0);
                    insert into spaces(id,type,name,slug,owner_user_id,currency_code,locale,time_zone,created_at,updated_at,version)
                    values
                      ('%s','PERSONAL','First','first-v7','%s','ZAR','en-ZA','Africa/Johannesburg',now(),now(),0),
                      ('%s','HOUSEHOLD','Second','second-v7','%s','ZAR','en-ZA','Africa/Johannesburg',now(),now(),0);
                    insert into user_settings(id,user_id,currency_code,locale,time_zone,created_at,updated_at,version)
                    values ('20000000-0000-0000-0000-000000000004','%s','ZAR','en-ZA','Africa/Johannesburg',now(),now(),0);
                    insert into categories(id,space_id,created_by_user_id,updated_by_user_id,name,sort_order,archived,system_default,created_at,updated_at,version)
                    values
                      ('20000000-0000-0000-0000-000000000005','%s','%s','%s','rent',501,true,false,now(),now(),0),
                      ('20000000-0000-0000-0000-000000000006','%s','%s','%s','Groceries',502,false,false,now(),now(),0),
                      ('20000000-0000-0000-0000-000000000007','%s','%s','%s','My Custom',503,false,false,now(),now(),0);
                    insert into budget_months(id,space_id,created_by_user_id,updated_by_user_id,year,month,name,status,created_at,updated_at,version)
                    values ('20000000-0000-0000-0000-000000000008','%s','%s','%s',2026,8,'August','ACTIVE',now(),now(),0);
                    insert into budget_items(id,space_id,created_by_user_id,updated_by_user_id,budget_month_id,name,category_id,planned_amount,tracked,item_type,recurring,rollover_enabled,sort_order,created_at,updated_at,version)
                    values ('20000000-0000-0000-0000-000000000009','%s','%s','%s','20000000-0000-0000-0000-000000000008','Groceries','20000000-0000-0000-0000-000000000006',500.00,true,'PLANNED',false,false,1,now(),now(),0);
                    insert into spending_entries(id,space_id,created_by_user_id,updated_by_user_id,budget_item_id,transaction_type,title,amount,spent_at,user_selected_date,user_selected_time,time_zone,spent_by_user_id,created_at,updated_at,version)
                    values
                      ('%s','%s','%s','%s','20000000-0000-0000-0000-000000000009','EXPENSE','Groceries',100.00,now(),date '2026-08-20',time '11:00','Africa/Johannesburg','%s',now(),now(),0),
                      ('%s','%s','%s','%s','20000000-0000-0000-0000-000000000009','REFUND','Legacy refund',25.00,now(),date '2026-08-20',time '12:00','Africa/Johannesburg','%s',now(),now(),0);
                    """.formatted(
                    OWNER, FIRST_SPACE, OWNER, SECOND_SPACE, OWNER, OWNER,
                    FIRST_SPACE, OWNER, OWNER, FIRST_SPACE, OWNER, OWNER, FIRST_SPACE, OWNER, OWNER,
                    FIRST_SPACE, OWNER, OWNER, FIRST_SPACE, OWNER, OWNER,
                    ORIGINAL_EXPENSE, FIRST_SPACE, OWNER, OWNER, OWNER,
                    LEGACY_REFUND, FIRST_SPACE, OWNER, OWNER, OWNER));
        }
    }

    private String linkedRefundInsert(String id, String amount) {
        return """
                insert into spending_entries(id,space_id,created_by_user_id,updated_by_user_id,budget_item_id,
                    transaction_type,title,amount,spent_at,user_selected_date,user_selected_time,time_zone,
                    spent_by_user_id,refund_for_spending_entry_id,created_at,updated_at,version)
                values ('%s','%s','%s','%s',
                    '20000000-0000-0000-0000-000000000009','REFUND','Excess refund',%s,now(),
                    date '2026-08-20',time '13:00','Africa/Johannesburg','%s','%s',now(),now(),0)
                """.formatted(id, FIRST_SPACE, OWNER, OWNER, amount, OWNER, ORIGINAL_EXPENSE);
    }

    private String unlinkedRefundInsert(String id, String amount) {
        return """
                insert into spending_entries(id,space_id,created_by_user_id,updated_by_user_id,budget_item_id,
                    transaction_type,title,amount,spent_at,user_selected_date,user_selected_time,time_zone,
                    spent_by_user_id,created_at,updated_at,version)
                values ('%s','%s','%s','%s',
                    '20000000-0000-0000-0000-000000000009','REFUND','Unlinked refund',%s,now(),
                    date '2026-08-20',time '13:00','Africa/Johannesburg','%s',now(),now(),0)
                """.formatted(id, FIRST_SPACE, OWNER, OWNER, amount, OWNER);
    }

    private List<String> referenceCategoryNames(java.sql.Statement statement) throws SQLException {
        List<String> names = new ArrayList<>();
        try (var rows = statement.executeQuery("""
                select name from reference_category_definitions order by sort_order
                """)) {
            while (rows.next()) names.add(rows.getString(1));
        }
        return names;
    }

    private long count(java.sql.Statement statement, String table, String where) throws SQLException {
        try (var rows = statement.executeQuery("select count(*) from " + table + " where " + where)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
