package io.github.eunini.clearing.gateway.support;

import io.github.eunini.clearing.gateway.engine.NettingEngine;
import io.github.eunini.clearing.gateway.participant.ParticipantDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Boots the full application against PostgreSQL. By default a Testcontainers
 * PostgreSQL 16 instance is started once per JVM; set {@code TEST_DB_URL}
 * (plus {@code TEST_DB_USER} / {@code TEST_DB_PASSWORD}) to use an existing
 * database instead. Background jobs are disabled; tests drive cut-off, LSM
 * and outbox publishing explicitly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"clearing.scheduling.enabled=false", "spring.datasource.hikari.maximum-pool-size=20"})
@Import(IntegrationTestBase.EngineConfig.class)
public abstract class IntegrationTestBase {

    private static final PostgreSQLContainer<?> POSTGRES;

    static {
        if (System.getenv("TEST_DB_URL") == null) {
            POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
            POSTGRES.start();
        } else {
            POSTGRES = null;
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (POSTGRES != null) {
            registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&reWriteBatchedInserts=true");
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> System.getenv("TEST_DB_URL"));
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USER", "clearing"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", "clearing"));
        }
    }

    @TestConfiguration
    public static class EngineConfig {
        @Bean
        @Primary
        public FakeNettingEngine fakeNettingEngine() {
            return new FakeNettingEngine();
        }
    }

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected TestRestTemplate http;

    @Autowired
    protected ParticipantDirectory participants;

    @Autowired
    protected FakeNettingEngine engine;

    @Autowired
    protected NettingEngine nettingEngine;

    @BeforeEach
    void resetDatabase() {
        jdbc.sql("""
                truncate payment_event, participant_notification, outbox_event, settlement_instruction,
                    cycle_position, cycle_currency_position, lsm_run, end_to_end_ref, payment, inbound_message,
                    clearing_cycle restart identity cascade""").update();
        jdbc.sql("update participant_position set position = 0, queued_count = 0").update();
        jdbc.sql("""
                update participant p set active = true, net_debit_cap = s.cap from (
                    select id, case row_number() over (partition by country order by id)
                        when 1 then 2000000000 when 2 then 800000000 else 300000000 end as cap
                    from participant) s
                where s.id = p.id""").update();
        jdbc.sql("insert into clearing_cycle (business_date, status) values (current_date, 'OPEN')").update();
        participants.reload();
        engine.down.set(false);
        engine.corruptNextResult.set(false);
    }

    protected long position(String bic) {
        return jdbc.sql("""
                        select pp.position from participant_position pp join participant p on p.id = pp.participant_id
                        where p.bic = :b""").param("b", bic).query(Long.class).single();
    }
}
