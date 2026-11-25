package com.ghmanager.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RateGateTest {
    @Test
    public void blocksUntilDeadline() {
        RateGate g = new RateGate();
        assertFalse(g.blocked(1000));
        g.block(5000, 1000);
        assertTrue(g.blocked(5999));
        assertFalse(g.blocked(6000));
        assertEquals(5, g.seconds(1000));
        assertEquals(1, g.seconds(5500));
    }

    @Test
    public void keepsLongerDeadline() {
        RateGate g = new RateGate();
        g.block(5000, 1000);
        g.block(1000, 1000);
        assertEquals(5000L, g.remainingMs(1000));
        g.clear();
        assertFalse(g.blocked(1000));
    }

    @Test
    public void detectsRateLimitErrors() {
        assertTrue(RateGate.isRateLimited(403, 0, "API rate limit exceeded"));
        assertFalse(RateGate.isRateLimited(403, 0, "Resource not accessible"));
        assertTrue(RateGate.isRateLimited(429, 0, ""));
        assertTrue(RateGate.isRateLimited(422, 3000, ""));
        assertEquals(60000L, RateGate.fallbackWaitMs(0));
        assertEquals(3000L, RateGate.fallbackWaitMs(3000));
    }
}
