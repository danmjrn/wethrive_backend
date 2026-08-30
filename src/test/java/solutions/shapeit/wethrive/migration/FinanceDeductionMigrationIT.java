package solutions.shapeit.wethrive.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class FinanceDeductionMigrationIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine")
            .withDatabaseName("wethrive_deductions").withUsername("wethrive").withPassword("test-password");

    @Test
    void v3MigratesLegacyTitheAcrossPartialReceiptsExactlyOnce() throws Exception {
        Flyway throughV2 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("2").load();
        assertThat(throughV2.migrate().targetSchemaVersion).hasToString("2");
        seedLegacySalary();

        Flyway throughV3 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("3").load();
        assertThat(throughV3.migrate().targetSchemaVersion).hasToString("3");

        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            assertThat(count(statement, "income_deductions")).isEqualTo(1);
            assertThat(count(statement, "income_receipt_deductions")).isEqualTo(2);
            try (var row = statement.executeQuery("""
                    select id, deduction_type, percentage_rate, fixed_amount, legacy_tithe
                      from income_deductions
                    """)) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("id")).isEqualTo("00000000-0000-0000-0000-000000000005");
                assertThat(row.getString("deduction_type")).isEqualTo("PERCENTAGE");
                assertThat(row.getBigDecimal("percentage_rate")).isEqualByComparingTo("0.1000");
                assertThat(row.getBigDecimal("fixed_amount")).isNull();
                assertThat(row.getBoolean("legacy_tithe")).isTrue();
            }
            try (var rows = statement.executeQuery("""
                    select income_receipt_id, id, actual_amount
                      from income_receipt_deductions
                     order by income_receipt_id
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("income_receipt_id")).isEqualTo("00000000-0000-0000-0000-0000000000a1");
                assertThat(rows.getString("id")).isEqualTo(rows.getString("income_receipt_id"));
                assertThat(rows.getBigDecimal("actual_amount")).isEqualByComparingTo("400.00");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("income_receipt_id")).isEqualTo("00000000-0000-0000-0000-0000000000a2");
                assertThat(rows.getString("id")).isEqualTo(rows.getString("income_receipt_id"));
                assertThat(rows.getBigDecimal("actual_amount")).isEqualByComparingTo("600.00");
            }
            try (var total = statement.executeQuery("select sum(actual_amount) from income_receipt_deductions where deleted_at is null")) {
                assertThat(total.next()).isTrue();
                assertThat(total.getBigDecimal(1)).isEqualByComparingTo("1000.00");
            }

            statement.execute("""
                    insert into income_deductions(
                        id,space_id,income_entry_id,name,deduction_type,fixed_amount,sort_order,legacy_tithe,
                        created_by_user_id,updated_by_user_id,created_at,updated_at,version)
                    values (
                        '00000000-0000-0000-0000-000000000006','00000000-0000-0000-0000-000000000002',
                        '00000000-0000-0000-0000-000000000005','Medical aid','FIXED',1000.00,1,false,
                        '00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001',now(),now(),0)
                    """);
            assertThatThrownBy(() -> statement.execute("""
                    insert into income_deductions(
                        id,space_id,income_entry_id,name,deduction_type,fixed_amount,sort_order,legacy_tithe,
                        created_by_user_id,updated_by_user_id,created_at,updated_at,version)
                    values (
                        '00000000-0000-0000-0000-000000000007','00000000-0000-0000-0000-000000000002',
                        '00000000-0000-0000-0000-000000000005','Too much','FIXED',9000.01,2,false,
                        '00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001',now(),now(),0)
                    """)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("planned deductions cannot exceed expected gross income");
        }

        assertThat(throughV3.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            assertThat(count(statement, "income_deductions")).isEqualTo(2);
            assertThat(count(statement, "income_receipt_deductions")).isEqualTo(2);
        }
    }

    private void seedLegacySalary() throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("""
                    insert into app_users(id,email,normalized_email,password_hash,display_name,created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000001','owner@example.test','owner@example.test','hash','Owner',now(),now(),0);
                    insert into spaces(id,type,name,slug,owner_user_id,currency_code,locale,time_zone,created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000002','PERSONAL','Personal','personal-deduction-test','00000000-0000-0000-0000-000000000001','ZAR','en-ZA','Africa/Johannesburg',now(),now(),0);
                    insert into income_types(id,space_id,created_by_user_id,updated_by_user_id,name,archived,system_default,sort_order,created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','Salary',false,false,0,now(),now(),0);
                    insert into budget_months(id,space_id,created_by_user_id,updated_by_user_id,year,month,name,status,created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001',2026,7,'July','ACTIVE',now(),now(),0);
                    insert into income_entries(
                        id,space_id,created_by_user_id,updated_by_user_id,budget_month_id,source_name,income_type_id,
                        expected_amount,tithe_enabled,tithe_rate,recurring,sort_order,expected_date,time_zone,
                        created_at,updated_at,version)
                    values (
                        '00000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000002',
                        '00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001',
                        '00000000-0000-0000-0000-000000000003','Salary','00000000-0000-0000-0000-000000000004',
                        10000.00,true,0.1000,false,0,date '2026-06-25','Africa/Johannesburg',now(),now(),0);
                    insert into income_receipts(
                        id,space_id,income_entry_id,amount,received_at,time_zone,recorded_by_user_id,
                        created_by_user_id,updated_by_user_id,created_at,updated_at,version)
                    values
                        ('00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-000000000002',
                         '00000000-0000-0000-0000-000000000005',4000.00,'2026-06-20T08:00:00Z','Africa/Johannesburg',
                         '00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001',
                         '00000000-0000-0000-0000-000000000001',now(),now(),0),
                        ('00000000-0000-0000-0000-0000000000a2','00000000-0000-0000-0000-000000000002',
                         '00000000-0000-0000-0000-000000000005',6000.00,'2026-07-20T08:00:00Z','Africa/Johannesburg',
                         '00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001',
                         '00000000-0000-0000-0000-000000000001',now(),now(),0);
                    """);
        }
    }

    private long count(java.sql.Statement statement, String table) throws Exception {
        try (var rows = statement.executeQuery("select count(*) from " + table)) {
            rows.next(); return rows.getLong(1);
        }
    }
}
