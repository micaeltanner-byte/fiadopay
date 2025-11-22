package edu.ucsal.fiadopay.service;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

@Component
public class DeliveryMetrics {
    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong successes = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public void incAttempt(){ attempts.incrementAndGet(); }
    public void incSuccess(){ successes.incrementAndGet(); }
    public void incFailure(){ failures.incrementAndGet(); }

    public long getAttempts(){ return attempts.get(); }
    public long getSuccesses(){ return successes.get(); }
    public long getFailures(){ return failures.get(); }
}
