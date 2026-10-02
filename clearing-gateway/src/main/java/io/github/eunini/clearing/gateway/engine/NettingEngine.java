package io.github.eunini.clearing.gateway.engine;

/**
 * The netting and liquidity-saving engine. Both operations are pure functions
 * of their input, so callers may retry them freely.
 */
public interface NettingEngine {

    EngineModels.NettingResult net(EngineModels.NettingRequest request);

    EngineModels.LsmResult resolve(EngineModels.LsmRequest request);
}
