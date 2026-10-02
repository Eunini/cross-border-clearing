package io.github.eunini.clearing.gateway.config;

import io.github.eunini.clearing.gateway.cycle.CycleService;
import io.github.eunini.clearing.gateway.fx.FxRateService;
import io.github.eunini.clearing.gateway.lsm.LsmService;
import io.github.eunini.clearing.gateway.outbox.OutboxPublisher;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Background jobs: FX ticks, outbox publishing, LSM runs and cycle cut-offs.
 * Disabled with {@code clearing.scheduling.enabled=false} (tests drive these
 * steps explicitly). Each job runs with a fixed delay, so a slow run is never
 * overlapped by the next one.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "clearing.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class BackgroundJobs implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(BackgroundJobs.class);

    private final ClearingProperties props;
    private final FxRateService fx;
    private final OutboxPublisher outbox;
    private final LsmService lsm;
    private final CycleService cycles;

    public BackgroundJobs(ClearingProperties props, FxRateService fx, OutboxPublisher outbox, LsmService lsm,
                          CycleService cycles) {
        this.props = props;
        this.fx = fx;
        this.outbox = outbox;
        this.lsm = lsm;
        this.cycles = cycles;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        var s = props.scheduling();
        registrar.addFixedDelayTask(guard("fx", fx::tick), props.fx().tick());
        registrar.addFixedDelayTask(guard("outbox", () -> {
            while (outbox.publishBatch(s.outboxBatchSize()) == s.outboxBatchSize()) {
                // drain while full batches keep coming
            }
        }), s.outboxInterval());
        registrar.addFixedDelayTask(guard("lsm", lsm::runOnce), s.lsmInterval());
        if (!s.cycleInterval().isZero()) {
            registrar.addFixedDelayTask(guard("cycle", cycles::scheduledTick), s.cycleInterval());
        } else {
            registrar.addFixedDelayTask(guard("cycle-pending", cycles::processPending), Duration.ofSeconds(5));
        }
    }

    private static Runnable guard(String name, Runnable job) {
        return () -> {
            try {
                job.run();
            } catch (RuntimeException e) {
                log.warn("Background job {} failed: {}", name, e.toString());
            }
        };
    }
}
