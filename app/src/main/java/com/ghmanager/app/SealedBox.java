package com.ghmanager.app;

import java.security.SecureRandom;

/**
 * Pure-Java implementation of libsodium's {@code crypto_box_seal} (X25519 + XSalsa20-Poly1305 with a
 * BLAKE2b-derived nonce). GitHub requires every Actions secret to be encrypted this way with the
 * repository public key, and Android has no built-in primitive for it on API 24+.
 *
 * <p>The curve and cipher code is a port of the public-domain TweetNaCl.
 */
public final class SealedBox {
    private SealedBox() {
    }

    // ------------------------------------------------------------------ public API

    /** Encrypts {@code msg} for the 32-byte {@code recipientPk}. Output: epk(32) | mac(16) | ciphertext. */
    public static byte[] seal(byte[] msg, byte[] recipientPk) {
        byte[] esk = new byte[32];
        new SecureRandom().nextBytes(esk);
        return seal(msg, recipientPk, esk);
    }

    /** Deterministic variant (the ephemeral secret key is supplied); used by tests. */
    public static byte[] seal(byte[] msg, byte[] pk, byte[] esk) {
        if (pk.length != 32 || esk.length != 32) throw new IllegalArgumentException("key size");
        byte[] epk = scalarMultBase(esk);
        byte[] both = new byte[64];
        System.arraycopy(epk, 0, both, 0, 32);
        System.arraycopy(pk, 0, both, 32, 32);
        byte[] nonce = blake2b(both, 24);
        byte[] shared = scalarMult(esk, pk);
        byte[] key = hsalsa20(new byte[16], shared);
        byte[] box = secretBox(msg, nonce, key);
        byte[] out = new byte[32 + box.length];
        System.arraycopy(epk, 0, out, 0, 32);
        System.arraycopy(box, 0, out, 32, box.length);
        return out;
    }

    public static byte[] scalarMultBase(byte[] n) {
        byte[] base = new byte[32];
        base[0] = 9;
        return scalarMult(n, base);
    }

    // ------------------------------------------------------------------ secretbox

    private static byte[] secretBox(byte[] msg, byte[] nonce24, byte[] key32) {
        byte[] padded = new byte[32 + msg.length];
        System.arraycopy(msg, 0, padded, 32, msg.length);
        byte[] c = xsalsa20Xor(padded, nonce24, key32);
        byte[] polyKey = new byte[32];
        System.arraycopy(c, 0, polyKey, 0, 32);
        byte[] ct = new byte[msg.length];
        System.arraycopy(c, 32, ct, 0, msg.length);
        byte[] mac = poly1305(ct, polyKey);
        byte[] out = new byte[16 + ct.length];
        System.arraycopy(mac, 0, out, 0, 16);
        System.arraycopy(ct, 0, out, 16, ct.length);
        return out;
    }

    // ------------------------------------------------------------------ Salsa20 family

    private static final byte[] SIGMA = {'e', 'x', 'p', 'a', 'n', 'd', ' ', '3', '2', '-', 'b', 'y', 't', 'e', ' ', 'k'};

    private static int ld32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }

    private static void st32(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }

    private static int rotl(int u, int c) {
        return (u << c) | (u >>> (32 - c));
    }

    private static void core(byte[] out, byte[] in, byte[] k, byte[] c, boolean h) {
        int[] w = new int[16];
        int[] x = new int[16];
        int[] y = new int[16];
        int[] t = new int[4];
        for (int i = 0; i < 4; i++) {
            x[5 * i] = ld32(c, 4 * i);
            x[1 + i] = ld32(k, 4 * i);
            x[6 + i] = ld32(in, 4 * i);
            x[11 + i] = ld32(k, 16 + 4 * i);
        }
        System.arraycopy(x, 0, y, 0, 16);
        for (int i = 0; i < 20; i++) {
            for (int j = 0; j < 4; j++) {
                for (int m = 0; m < 4; m++) t[m] = x[(5 * j + 4 * m) % 16];
                t[1] ^= rotl(t[0] + t[3], 7);
                t[2] ^= rotl(t[1] + t[0], 9);
                t[3] ^= rotl(t[2] + t[1], 13);
                t[0] ^= rotl(t[3] + t[2], 18);
                for (int m = 0; m < 4; m++) w[4 * j + (j + m) % 4] = t[m];
            }
            System.arraycopy(w, 0, x, 0, 16);
        }
        if (h) {
            for (int i = 0; i < 16; i++) x[i] += y[i];
            for (int i = 0; i < 4; i++) {
                x[5 * i] -= ld32(c, 4 * i);
                x[6 + i] -= ld32(in, 4 * i);
            }
            for (int i = 0; i < 4; i++) {
                st32(out, 4 * i, x[5 * i]);
                st32(out, 16 + 4 * i, x[6 + i]);
            }
        } else {
            for (int i = 0; i < 16; i++) st32(out, 4 * i, x[i] + y[i]);
        }
    }

    static byte[] hsalsa20(byte[] in16, byte[] key32) {
        byte[] out = new byte[32];
        core(out, in16, key32, SIGMA, true);
        return out;
    }

    private static byte[] salsa20Xor(byte[] m, byte[] n8, byte[] k) {
        byte[] out = new byte[m.length];
        byte[] z = new byte[16];
        byte[] x = new byte[64];
        System.arraycopy(n8, 0, z, 0, 8);
        int pos = 0;
        while (pos < m.length) {
            core(x, z, k, SIGMA, false);
            int n = Math.min(64, m.length - pos);
            for (int i = 0; i < n; i++) out[pos + i] = (byte) (m[pos + i] ^ x[i]);
            int u = 1;
            for (int i = 8; i < 16; i++) {
                u += z[i] & 0xff;
                z[i] = (byte) u;
                u >>>= 8;
            }
            pos += n;
        }
        return out;
    }

    private static byte[] xsalsa20Xor(byte[] m, byte[] n24, byte[] k) {
        byte[] in16 = new byte[16];
        System.arraycopy(n24, 0, in16, 0, 16);
        byte[] sub = hsalsa20(in16, k);
        byte[] n8 = new byte[8];
        System.arraycopy(n24, 16, n8, 0, 8);
        return salsa20Xor(m, n8, sub);
    }

    // ------------------------------------------------------------------ Poly1305 (26-bit limbs)

    private static long le32(byte[] b, int o) {
        return ld32(b, o) & 0xffffffffL;
    }

    static byte[] poly1305(byte[] m, byte[] key) {
        final long r0 = le32(key, 0) & 0x3ffffffL;
        final long r1 = (le32(key, 3) >>> 2) & 0x3ffff03L;
        final long r2 = (le32(key, 6) >>> 4) & 0x3ffc0ffL;
        final long r3 = (le32(key, 9) >>> 6) & 0x3f03fffL;
        final long r4 = (le32(key, 12) >>> 8) & 0x00fffffL;
        final long s1 = r1 * 5, s2 = r2 * 5, s3 = r3 * 5, s4 = r4 * 5;
        long h0 = 0, h1 = 0, h2 = 0, h3 = 0, h4 = 0;

        int pos = 0;
        int len = m.length;
        byte[] blk = new byte[16];
        while (len > 0) {
            long hibit;
            if (len >= 16) {
                System.arraycopy(m, pos, blk, 0, 16);
                hibit = 1L << 24;
                pos += 16;
                len -= 16;
            } else {
                java.util.Arrays.fill(blk, (byte) 0);
                System.arraycopy(m, pos, blk, 0, len);
                blk[len] = 1;
                hibit = 0;
                len = 0;
            }
            h0 += le32(blk, 0) & 0x3ffffffL;
            h1 += (le32(blk, 3) >>> 2) & 0x3ffffffL;
            h2 += (le32(blk, 6) >>> 4) & 0x3ffffffL;
            h3 += (le32(blk, 9) >>> 6) & 0x3ffffffL;
            h4 += (le32(blk, 12) >>> 8) | hibit;

            long d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1;
            long d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2;
            long d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3;
            long d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4;
            long d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0;

            long c = d0 >>> 26;
            h0 = d0 & 0x3ffffffL;
            d1 += c;
            c = d1 >>> 26;
            h1 = d1 & 0x3ffffffL;
            d2 += c;
            c = d2 >>> 26;
            h2 = d2 & 0x3ffffffL;
            d3 += c;
            c = d3 >>> 26;
            h3 = d3 & 0x3ffffffL;
            d4 += c;
            c = d4 >>> 26;
            h4 = d4 & 0x3ffffffL;
            h0 += c * 5;
            c = h0 >>> 26;
            h0 &= 0x3ffffffL;
            h1 += c;
        }

        long c = h1 >>> 26;
        h1 &= 0x3ffffffL;
        h2 += c;
        c = h2 >>> 26;
        h2 &= 0x3ffffffL;
        h3 += c;
        c = h3 >>> 26;
        h3 &= 0x3ffffffL;
        h4 += c;
        c = h4 >>> 26;
        h4 &= 0x3ffffffL;
        h0 += c * 5;
        c = h0 >>> 26;
        h0 &= 0x3ffffffL;
        h1 += c;

        long g0 = h0 + 5;
        c = g0 >>> 26;
        g0 &= 0x3ffffffL;
        long g1 = h1 + c;
        c = g1 >>> 26;
        g1 &= 0x3ffffffL;
        long g2 = h2 + c;
        c = g2 >>> 26;
        g2 &= 0x3ffffffL;
        long g3 = h3 + c;
        c = g3 >>> 26;
        g3 &= 0x3ffffffL;
        long g4 = h4 + c - (1L << 26);

        long mask = (g4 >>> 63) - 1;
        g0 &= mask;
        g1 &= mask;
        g2 &= mask;
        g3 &= mask;
        g4 &= mask;
        mask = ~mask;
        h0 = (h0 & mask) | g0;
        h1 = (h1 & mask) | g1;
        h2 = (h2 & mask) | g2;
        h3 = (h3 & mask) | g3;
        h4 = (h4 & mask) | g4;

        h0 = (h0 | (h1 << 26)) & 0xffffffffL;
        h1 = ((h1 >>> 6) | (h2 << 20)) & 0xffffffffL;
        h2 = ((h2 >>> 12) | (h3 << 14)) & 0xffffffffL;
        h3 = ((h3 >>> 18) | (h4 << 8)) & 0xffffffffL;

        long f = h0 + le32(key, 16);
        h0 = f & 0xffffffffL;
        f = h1 + le32(key, 20) + (f >>> 32);
        h1 = f & 0xffffffffL;
        f = h2 + le32(key, 24) + (f >>> 32);
        h2 = f & 0xffffffffL;
        f = h3 + le32(key, 28) + (f >>> 32);
        h3 = f & 0xffffffffL;

        byte[] out = new byte[16];
        st32(out, 0, (int) h0);
        st32(out, 4, (int) h1);
        st32(out, 8, (int) h2);
        st32(out, 12, (int) h3);
        return out;
    }

    // ------------------------------------------------------------------ BLAKE2b

    private static final long[] B2_IV = {
            0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
            0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L};

    private static final byte[][] B2_SIGMA = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
            {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
            {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
            {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
            {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
            {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
            {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
            {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
            {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
            {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0}};

    private static void b2g(long[] v, int a, int b, int c, int d, long x, long y) {
        v[a] = v[a] + v[b] + x;
        v[d] = Long.rotateRight(v[d] ^ v[a], 32);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 24);
        v[a] = v[a] + v[b] + y;
        v[d] = Long.rotateRight(v[d] ^ v[a], 16);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 63);
    }

    private static void b2compress(long[] h, byte[] block, int off, long t, boolean last) {
        long[] m = new long[16];
        for (int i = 0; i < 16; i++) {
            long w = 0;
            for (int j = 7; j >= 0; j--) w = (w << 8) | (block[off + i * 8 + j] & 0xffL);
            m[i] = w;
        }
        long[] v = new long[16];
        System.arraycopy(h, 0, v, 0, 8);
        System.arraycopy(B2_IV, 0, v, 8, 8);
        v[12] ^= t;
        if (last) v[14] = ~v[14];
        for (int r = 0; r < 12; r++) {
            byte[] s = B2_SIGMA[r % 10];
            b2g(v, 0, 4, 8, 12, m[s[0]], m[s[1]]);
            b2g(v, 1, 5, 9, 13, m[s[2]], m[s[3]]);
            b2g(v, 2, 6, 10, 14, m[s[4]], m[s[5]]);
            b2g(v, 3, 7, 11, 15, m[s[6]], m[s[7]]);
            b2g(v, 0, 5, 10, 15, m[s[8]], m[s[9]]);
            b2g(v, 1, 6, 11, 12, m[s[10]], m[s[11]]);
            b2g(v, 2, 7, 8, 13, m[s[12]], m[s[13]]);
            b2g(v, 3, 4, 9, 14, m[s[14]], m[s[15]]);
        }
        for (int i = 0; i < 8; i++) h[i] ^= v[i] ^ v[i + 8];
    }

    static byte[] blake2b(byte[] in, int outLen) {
        long[] h = new long[8];
        System.arraycopy(B2_IV, 0, h, 0, 8);
        h[0] ^= 0x01010000L ^ outLen;
        int pos = 0;
        byte[] blk = new byte[128];
        while (in.length - pos > 128) {
            System.arraycopy(in, pos, blk, 0, 128);
            pos += 128;
            b2compress(h, blk, 0, pos, false);
        }
        java.util.Arrays.fill(blk, (byte) 0);
        int rest = in.length - pos;
        System.arraycopy(in, pos, blk, 0, rest);
        b2compress(h, blk, 0, in.length, true);
        byte[] out = new byte[outLen];
        for (int i = 0; i < outLen; i++) out[i] = (byte) (h[i >> 3] >>> (8 * (i & 7)));
        return out;
    }

    // ------------------------------------------------------------------ Curve25519

    private static final long[] C121665 = {0xDB41, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    private static void car25519(long[] o) {
        long c = 1;
        for (int i = 0; i < 16; i++) {
            long v = o[i] + c + 65535;
            c = v >> 16;
            o[i] = v - (c << 16);
        }
        o[0] += c - 1 + 37 * (c - 1);
    }

    private static void sel25519(long[] p, long[] q, long b) {
        long c = ~(b - 1);
        for (int i = 0; i < 16; i++) {
            long t = c & (p[i] ^ q[i]);
            p[i] ^= t;
            q[i] ^= t;
        }
    }

    private static void pack25519(byte[] o, long[] n) {
        long[] m = new long[16];
        long[] t = new long[16];
        System.arraycopy(n, 0, t, 0, 16);
        car25519(t);
        car25519(t);
        car25519(t);
        for (int j = 0; j < 2; j++) {
            m[0] = t[0] - 0xffed;
            for (int i = 1; i < 15; i++) {
                m[i] = t[i] - 0xffff - ((m[i - 1] >> 16) & 1);
                m[i - 1] &= 0xffff;
            }
            m[15] = t[15] - 0x7fff - ((m[14] >> 16) & 1);
            long b = (m[15] >> 16) & 1;
            m[14] &= 0xffff;
            sel25519(t, m, 1 - b);
        }
        for (int i = 0; i < 16; i++) {
            o[2 * i] = (byte) (t[i] & 0xff);
            o[2 * i + 1] = (byte) (t[i] >> 8);
        }
    }

    private static void unpack25519(long[] o, byte[] n) {
        for (int i = 0; i < 16; i++) o[i] = (n[2 * i] & 0xffL) + ((n[2 * i + 1] & 0xffL) << 8);
        o[15] &= 0x7fff;
    }

    private static void fadd(long[] o, long[] a, long[] b) {
        for (int i = 0; i < 16; i++) o[i] = a[i] + b[i];
    }

    private static void fsub(long[] o, long[] a, long[] b) {
        for (int i = 0; i < 16; i++) o[i] = a[i] - b[i];
    }

    private static void fmul(long[] o, long[] a, long[] b) {
        long[] t = new long[31];
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) t[i + j] += a[i] * b[j];
        }
        for (int i = 0; i < 15; i++) t[i] += 38 * t[i + 16];
        System.arraycopy(t, 0, o, 0, 16);
        car25519(o);
        car25519(o);
    }

    private static void fsq(long[] o, long[] a) {
        fmul(o, a, a);
    }

    private static void inv25519(long[] o, long[] in) {
        long[] c = new long[16];
        System.arraycopy(in, 0, c, 0, 16);
        for (int a = 253; a >= 0; a--) {
            fsq(c, c);
            if (a != 2 && a != 4) fmul(c, c, in);
        }
        System.arraycopy(c, 0, o, 0, 16);
    }

    public static byte[] scalarMult(byte[] n, byte[] p) {
        byte[] z = new byte[32];
        System.arraycopy(n, 0, z, 0, 32);
        z[31] = (byte) ((n[31] & 127) | 64);
        z[0] &= (byte) 248;
        long[] x = new long[16];
        unpack25519(x, p);
        long[] a = new long[16], b = new long[16], c = new long[16];
        long[] d = new long[16], e = new long[16], f = new long[16];
        for (int i = 0; i < 16; i++) b[i] = x[i];
        a[0] = 1;
        d[0] = 1;
        for (int i = 254; i >= 0; --i) {
            long r = (z[i >>> 3] >>> (i & 7)) & 1;
            sel25519(a, b, r);
            sel25519(c, d, r);
            fadd(e, a, c);
            fsub(a, a, c);
            fadd(c, b, d);
            fsub(b, b, d);
            fsq(d, e);
            fsq(f, a);
            fmul(a, c, a);
            fmul(c, b, e);
            fadd(e, a, c);
            fsub(a, a, c);
            fsq(b, a);
            fsub(c, d, f);
            fmul(a, c, C121665);
            fadd(a, a, d);
            fmul(c, c, a);
            fmul(a, d, f);
            fmul(d, b, x);
            fsq(b, e);
            sel25519(a, b, r);
            sel25519(c, d, r);
        }
        inv25519(c, c);
        fmul(a, a, c);
        byte[] q = new byte[32];
        pack25519(q, a);
        return q;
    }
}
