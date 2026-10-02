package io.github.eunini.clearing.gateway.cycle;

import io.github.eunini.clearing.gateway.engine.EngineModels;
import java.util.List;
import java.util.Optional;

/** Exposes the package-private reconciliation check to tests in other packages. */
public final class CycleServiceAccess {

    private CycleServiceAccess() {}

    public static Optional<String> reconcile(List<EngineModels.Obligation> obligations, EngineModels.NettingResult r) {
        return CycleService.reconcile(obligations, r);
    }
}
