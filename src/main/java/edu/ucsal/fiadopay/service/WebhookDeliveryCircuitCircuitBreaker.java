package edu.ucsal.fiadopay.service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class WebhookDeliveryCircuitCircuitBreaker {

    private static class State {
        int failures;
        long trippedUntil; // epoch ms
    }

    private final Map<String, State> map = new ConcurrentHashMap<>();
    private final int failureThreshold = 5;
    private final long baseCooldownMs = 60_000L; // 1 minute

    public boolean allowRequest(String target){
        var s = map.get(target);
        if (s==null) return true;
        if (s.trippedUntil <= Instant.now().toEpochMilli()) return true;
        return false;
    }

    public void recordSuccess(String target){
        map.remove(target);
    }

    public void recordFailure(String target){
        var s = map.computeIfAbsent(target, k -> new State());
        s.failures++;
        if (s.failures >= failureThreshold){
            long cooldown = baseCooldownMs * s.failures; // increasing cooldown
            s.trippedUntil = Instant.now().toEpochMilli() + cooldown;
        }
    }

    public long getCooldownMs(String target){
        var s = map.get(target);
        if (s==null) return baseCooldownMs;
        if (s.trippedUntil <= Instant.now().toEpochMilli()) return 0L;
        return s.trippedUntil - Instant.now().toEpochMilli();
    }
}
