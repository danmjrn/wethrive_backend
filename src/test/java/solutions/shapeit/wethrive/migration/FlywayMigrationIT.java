package solutions.shapeit.wethrive.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class FlywayMigrationIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine")
            .withDatabaseName("wethrive").withUsername("wethrive").withPassword("test-password");

    @Test
    void baselineAndFinanceUpgradePreserveLegacyActualIncomeExactlyOnce() throws Exception {
        Flyway baseline = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("1").load();
        assertThat(baseline.migrate().targetSchemaVersion).hasToString("1");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("""
                    insert into app_users(id,email,normalized_email,password_hash,display_name,created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000001','owner@example.test','owner@example.test','hash','Owner',now(),now(),0);
                    insert into spaces(id,type,name,slug,owner_user_id,currency_code,locale,time_zone,created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000002','PERSONAL','Personal','personal-test','00000000-0000-0000-0000-000000000001','ZAR','en-ZA','Africa/Johannesburg',now(),now(),0);
                    insert into income_types(id,space_id,created_by_user_id,updated_by_user_id,name,archived,system_default,sort_order,created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','Salary',false,false,0,now(),now(),0);
                    insert into budget_months(id,space_id,created_by_user_id,updated_by_user_id,year,month,name,status,created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001',2026,7,'July','ACTIVE',now(),now(),0);
                    alter table income_entries disable trigger user;
                    insert into income_entries(id,space_id,created_by_user_id,updated_by_user_id,budget_month_id,source_name,income_type_id,
                        expected_amount,actual_amount,tithe_enabled,tithe_rate,recurring,sort_order,created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001',
                        '00000000-0000-0000-0000-000000000003','Salary','00000000-0000-0000-0000-000000000004',10000.00,1234.56,false,0.1000,false,0,now(),now(),7);
                    alter table income_entries enable trigger user;
                    """);
        }

        Flyway flyway = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("2").load();
        var result = flyway.migrate();
        assertThat(result.success).isTrue();
        assertThat(result.targetSchemaVersion).hasToString("2");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            assertThat(count(statement, "reference_category_definitions")).isEqualTo(21);
            assertThat(count(statement, "reference_income_type_definitions")).isEqualTo(7);
            assertThat(count(statement, "app_users")).isEqualTo(1);
            assertThat(count(statement, "budget_months")).isEqualTo(1);
            assertThat(count(statement, "income_receipts")).isEqualTo(1);
            assertThat(count(statement, "budget_funding_allocations")).isZero();
            statement.execute("""
                    insert into categories(id,space_id,created_by_user_id,updated_by_user_id,name,sort_order,archived,system_default,created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000006','00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','Transport',1,false,false,now(),now(),0);
                    insert into budget_items(id,space_id,created_by_user_id,updated_by_user_id,budget_month_id,name,category_id,planned_amount,tracked,item_type,recurring,rollover_enabled,sort_order,created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000007','00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000003','Work transport','00000000-0000-0000-0000-000000000006',2000.00,true,'PLANNED',false,false,0,now(),now(),0);
                    insert into budget_funding_allocations(id,budget_item_id,income_entry_id,space_id,source_type,planned_amount,confirmed_allocated_amount,created_by_user_id,updated_by_user_id,created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000008','00000000-0000-0000-0000-000000000007','00000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000002','INCOME_ENTRY',500.00,0.00,'00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001',now(),now(),0);
                    """);
            assertThat(count(statement, "budget_funding_allocations")).isEqualTo(1);
            try (var rows = statement.executeQuery("""
                    select id, income_entry_id, amount, time_zone, version
                    from income_receipts
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("id")).isEqualTo("00000000-0000-0000-0000-000000000005");
                assertThat(rows.getString("income_entry_id")).isEqualTo("00000000-0000-0000-0000-000000000005");
                assertThat(rows.getBigDecimal("amount")).isEqualByComparingTo("1234.56");
                assertThat(rows.getString("time_zone")).isEqualTo("Africa/Johannesburg");
                assertThat(rows.getLong("version")).isEqualTo(7);
            }
            try (var rows = statement.executeQuery("select actual_amount, expected_date, time_zone from income_entries")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBigDecimal("actual_amount")).isNull();
                assertThat(rows.getDate("expected_date").toLocalDate()).hasToString("2026-07-01");
                assertThat(rows.getString("time_zone")).isEqualTo("Africa/Johannesburg");
            }
        }
    }

    private long count(java.sql.Statement statement, String table) throws Exception {
        try (var rows = statement.executeQuery("select count(*) from " + table)) { rows.next(); return rows.getLong(1); }
    }
}
