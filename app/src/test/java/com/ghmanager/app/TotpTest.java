package com.ghmanager.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.Test;

public class TotpTest {
    private static final byte[] KEY = "12345678901234567890".getBytes();

    private static Totp.Signer signer() {
        return m -> {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(KEY, "HmacSHA1"));
            return mac.doFinal(m);
        };
    }

    @Test
    public void rfcVector() throws Exception {
        assertEquals("287082", Totp.code(signer(), 1L));
        assertEquals("359152", Totp.code(signer(), 2L));
    }

    @Test
    public void base32RoundTrip() {
        assertEquals(new String(KEY), new String(Totp.fromBase32(Totp.base32(KEY))));
    }

    @Test
    public void replayRejected() throws Exception {
        long now = 59_000L;
        String c = Totp.code(signer(), Totp.step(now));
        long hit = Totp.match(signer(), c, now, -1L);
        assertTrue(hit >= 0);
        assertEquals(-1L, Totp.match(signer(), c, now, hit));
    }

    @Test
    public void recoveryNormalize() {
        assertEquals("ABCDE12345", RecoveryCodes.normalize("abcde-12345"));
    }
}
