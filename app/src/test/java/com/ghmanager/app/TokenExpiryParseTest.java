package com.ghmanager.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TokenExpiryParseTest {
    private static final long DEC1 = 1796119200000L;

    @Test
    public void parsesGitHubHeaderWithUtc() {
        assertEquals(DEC1, TokenExpiry.parse("2026-12-01 10:00:00 UTC"));
    }

    @Test
    public void parsesWithoutSuffixAndWithSpaces() {
        assertEquals(DEC1, TokenExpiry.parse("2026-12-01 10:00:00"));
        assertEquals(DEC1, TokenExpiry.parse("  2026-12-01 10:00:00 UTC  "));
    }

    @Test
    public void invalidInputGivesZero() {
        assertEquals(0, TokenExpiry.parse(null));
        assertEquals(0, TokenExpiry.parse(""));
        assertEquals(0, TokenExpiry.parse("   "));
        assertEquals(0, TokenExpiry.parse("tomorrow"));
        assertEquals(0, TokenExpiry.parse("2026-13-45 99:99:99 UTC"));
    }

    @Test
    public void daysLeftRoundsUp() {
        long day = 24L * 3600L * 1000L;
        assertEquals(0, TokenExpiry.daysLeft(1000, 2000));
        assertEquals(1, TokenExpiry.daysLeft(DEC1, DEC1 - 1));
        assertEquals(1, TokenExpiry.daysLeft(DEC1, DEC1 - day));
        assertEquals(2, TokenExpiry.daysLeft(DEC1, DEC1 - day - 1));
    }

    @Test
    public void warnsInsideTheLastWeekOnly() {
        long day = 24L * 3600L * 1000L;
        assertTrue(TokenExpiry.warn(DEC1, DEC1 - 7 * day));
        assertTrue(TokenExpiry.warn(DEC1, DEC1 + day));
        assertFalse(TokenExpiry.warn(DEC1, DEC1 - 8 * day));
        assertFalse(TokenExpiry.warn(0, DEC1));
    }
}
