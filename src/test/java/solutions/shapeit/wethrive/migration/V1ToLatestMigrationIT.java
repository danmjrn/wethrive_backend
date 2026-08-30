package solutions.shapeit.wethrive.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class V1ToLatestMigrationIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine")
            .withDatabaseName("wethrive_v1_to_latest")
            .withUsername("wethrive")
            .withPassword("test-password");

    @Test
    void representativeV1DataMigratesWithoutATargetCapToTheLatestSchema() throws Exception {
        Flyway v1 = flywayAtTarget("1");
        assertThat(v1.migrate().targetSchemaVersion).hasToString("1");
        seedRepresentativeV1Data();

        try (var connection = connection(); var statement = connection.createStatement()) {
            assertThat(columnExists(statement, "user_settings", "budget_item_view")).isFalse();
            assertThat(tableExists(statement, "income_receipts")).isFalse();
            assertThat(tableExists(statement, "income_deductions")).isFalse();
            assertThat(tableExists(statement, "budget_reminder_schedules")).isFalse();
        }

        Flyway latest = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load();
        var result = latest.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(7);
        assertThat(result.targetSchemaVersion).hasToString("8");

        try (var connection = connection(); var statement = connection.createStatement()) {
            assertThat(appliedVersions(statement)).containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
            assertThat(columnExists(statement, "user_settings", "budget_item_view")).isTrue();
            assertThat(columnExists(statement, "user_settings", "palette")).isTrue();
            assertThat(columnExists(statement, "spending_entries", "refund_for_spending_entry_id")).isTrue();
            assertThat(columnExists(statement, "reminder_occurrences", "budget_reminder_schedule_id")).isTrue();
            assertThat(count(statement, "budget_reminder_schedules")).isZero();
            assertThat(count(statement, "reminder_period_reviews")).isZero();

            try (var row = statement.executeQuery("""
                    select budget_item_view, palette
                      from user_settings
                     where id = '00000000-0000-0000-0000-000000000003'
                    """)) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("budget_item_view")).isEqualTo("CARDS");
                assertThat(row.getString("palette")).isEqualTo("ORIGINAL");
            }

            try (var row = statement.executeQuery("""
                    select expected_date, time_zone, actual_amount
                      from income_entries
                     where id = '00000000-0000-0000-0000-000000000006'
                    """)) {
                assertThat(row.next()).isTrue();
                assertThat(row.getDate("expected_date").toLocalDate()).hasToString("2026-07-01");
                assertThat(row.getString("time_zone")).isEqualTo("Africa/Johannesburg");
                assertThat(row.getBigDecimal("actual_amount")).isNull();
            }

            assertThat(count(statement, "income_receipts")).isEqualTo(1);
            assertThat(count(statement, "income_deductions")).isEqualTo(1);
            assertThat(count(statement, "income_receipt_deductions")).isEqualTo(1);
            try (var row = statement.executeQuery("""
                    select r.amount, d.actual_amount
                      from income_receipts r
                      join income_receipt_deductions d on d.income_receipt_id = r.id
                     where r.id = '00000000-0000-0000-0000-000000000006'
                    """)) {
                assertThat(row.next()).isTrue();
                assertThat(row.getBigDecimal("amount")).isEqualByComparingTo("1234.56");
                assertThat(row.getBigDecimal("actual_amount")).isEqualByComparingTo("123.46");
            }

            statement.execute("""
                    update user_settings
                       set budget_item_view = 'ROWS'
                     where id = '00000000-0000-0000-0000-000000000003'
                    """);
            assertThatThrownBy(() -> statement.execute("""
                    update user_settings
                       set budget_item_view = 'GRID'
                     where id = '00000000-0000-0000-0000-000000000003'
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_user_settings_budget_item_view");
            assertThatThrownBy(() -> statement.execute("""
                    update user_settings
                       set budget_item_view = null
                     where id = '00000000-0000-0000-0000-000000000003'
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("budget_item_view");

            seedPostMigrationSettings(statement);
            try (var rows = statement.executeQuery("""
                    select id, budget_item_view
                      from user_settings
                     order by id
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("budget_item_view")).isEqualTo("ROWS");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("budget_item_view")).isEqualTo("CARDS");
                assertThat(rows.next()).isFalse();
            }
        }

        assertThat(latest.migrate().migrationsExecuted).isZero();
    }

    private Flyway flywayAtTarget(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(target)
                .load();
    }

    private java.sql.Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void seedRepresentativeV1Data() throws Exception {
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("""
                    insert into app_users(id,email,normalized_email,password_hash,display_name,created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000001','owner@example.test','owner@example.test','hash','Owner',now(),now(),0);
                    insert into spaces(id,type,name,slug,owner_user_id,currency_code,locale,time_zone,created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000002','PERSONAL','Personal','v1-to-latest-test',
                            '00000000-0000-0000-0000-000000000001','ZAR','en-ZA','Africa/Johannesburg',now(),now(),0);
                    insert into user_settings(id,user_id,currency_code,locale,time_zone,week_start_day,
                        default_tithe_enabled,default_tithe_rate,warning_threshold,critical_threshold,theme,
                        notifications_enabled,detailed_notifications_enabled,default_space_id,auto_lock_minutes,
                        onboarding_complete,created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000001',
                        'ZAR','en-ZA','Africa/Johannesburg',1,true,0.1000,0.7500,0.9000,'SYSTEM',true,false,
                        '00000000-0000-0000-0000-000000000002',5,true,now(),now(),4);
                    insert into income_types(id,space_id,created_by_user_id,updated_by_user_id,name,archived,system_default,sort_order,created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000002',
                        '00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001',
                        'Salary',false,false,0,now(),now(),0);
                    insert into budget_months(id,space_id,created_by_user_id,updated_by_user_id,year,month,name,status,created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000002',
                        '00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001',
                        2026,7,'July','ACTIVE',now(),now(),0);
                    alter table income_entries disable trigger user;
                    insert into income_entries(id,space_id,created_by_user_id,updated_by_user_id,budget_month_id,source_name,income_type_id,
                        expected_amount,actual_amount,tithe_enabled,tithe_rate,recurring,sort_order,created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000006','00000000-0000-0000-0000-000000000002',
                        '00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001',
                        '00000000-0000-0000-0000-000000000005','Salary','00000000-0000-0000-0000-000000000004',
                        10000.00,1234.56,true,0.1000,false,0,now(),now(),7);
                    alter table income_entries enable trigger user;
                    insert into reminder_preferences(
                        id,user_id,space_id,reminder_type,enabled,frequency_type,local_time,time_zone,
                        day_of_month,repeat_until_completed,next_run_at,created_at,updated_at,version)
                    values ('00000000-0000-0000-0000-000000000007','00000000-0000-0000-0000-000000000001',
                        '00000000-0000-0000-0000-000000000002','MONTHLY_BUDGET_SETUP',true,'MONTHLY',time '09:00',
                        'Africa/Johannesburg',25,true,'2026-06-25T07:00:00Z',now(),now(),2);
                    """);
        }
    }

    private void seedPostMigrationSettings(java.sql.Statement statement) throws SQLException {
        statement.execute("""
                insert into app_users(id,email,normalized_email,password_hash,display_name,created_at,updated_at,version)
                values ('00000000-0000-0000-0000-000000000008','new@example.test','new@example.test','hash','New',now(),now(),0);
                insert into user_settings(id,user_id,currency_code,locale,time_zone,created_at,updated_at,version)
                values ('00000000-0000-0000-0000-000000000009','00000000-0000-0000-0000-000000000008',
                        'ZAR','en-ZA','Africa/Johannesburg',now(),now(),0);
                """);
    }

    private boolean columnExists(java.sql.Statement statement, String table, String column) throws SQLException {
        try (var rows = statement.executeQuery("""
                select exists (
                    select 1
                      from information_schema.columns
                     where table_schema = current_schema()
                       and table_name = '%s'
                       and column_name = '%s'
                )
                """.formatted(table, column))) {
            rows.next();
            return rows.getBoolean(1);
        }
    }

    private boolean tableExists(java.sql.Statement statement, String table) throws SQLException {
        try (var rows = statement.executeQuery("""
                select exists (
                    select 1
                      from information_schema.tables
                     where table_schema = current_schema()
                       and table_name = '%s'
                )
                """.formatted(table))) {
            rows.next();
            return rows.getBoolean(1);
        }
    }

    private ArrayList<String> appliedVersions(java.sql.Statement statement) throws SQLException {
        var versions = new ArrayList<String>();
        try (var rows = statement.executeQuery("""
                select version
                  from flyway_schema_history
                 where success = true and version is not null
                 order by installed_rank
                """)) {
            while (rows.next()) {
                versions.add(rows.getString(1));
            }
        }
        return versions;
    }

    private long count(java.sql.Statement statement, String table) throws SQLException {
        try (var rows = statement.executeQuery("select count(*) from " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
