package com.autoparkingplan.parking;

import java.util.List;

/**
 * 파이썬 {@code random.Random(seed)} (MT19937, CPython 과 같은 시드 처리·실수 생성) — 테스트의 고정 시드 무작위 대지를
 * 파이썬판과 같은 순서로 만들기 위해 옮겼다.
 */
final class PyRandom {
    private static final int N = 624;
    private static final int M = 397;
    private final int[] mt = new int[N];
    private int mti = N + 1;

    PyRandom(long seed) {
        long n = Math.abs(seed);
        int bits = 64 - Long.numberOfLeadingZeros(n);
        int keyused = bits == 0 ? 1 : (bits - 1) / 32 + 1;
        int[] key = new int[keyused];
        for (int i = 0; i < keyused; i++) {
            key[i] = (int) (n >>> (32 * i));
        }
        initByArray(key);
    }

    private void initGenrand(int s) {
        mt[0] = s;
        for (mti = 1; mti < N; mti++) {
            mt[mti] = 1812433253 * (mt[mti - 1] ^ (mt[mti - 1] >>> 30)) + mti;
        }
    }

    private void initByArray(int[] initKey) {
        initGenrand(19650218);
        int i = 1;
        int j = 0;
        int k = Math.max(N, initKey.length);
        for (; k > 0; k--) {
            mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1664525)) + initKey[j] + j;
            i++;
            j++;
            if (i >= N) {
                mt[0] = mt[N - 1];
                i = 1;
            }
            if (j >= initKey.length) {
                j = 0;
            }
        }
        for (k = N - 1; k > 0; k--) {
            mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1566083941)) - i;
            i++;
            if (i >= N) {
                mt[0] = mt[N - 1];
                i = 1;
            }
        }
        mt[0] = 0x80000000;
    }

    private int genrandUint32() {
        int y;
        if (mti >= N) {
            int kk;
            for (kk = 0; kk < N - M; kk++) {
                y = (mt[kk] & 0x80000000) | (mt[kk + 1] & 0x7fffffff);
                mt[kk] = mt[kk + M] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
            }
            for (; kk < N - 1; kk++) {
                y = (mt[kk] & 0x80000000) | (mt[kk + 1] & 0x7fffffff);
                mt[kk] = mt[kk + (M - N)] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
            }
            y = (mt[N - 1] & 0x80000000) | (mt[0] & 0x7fffffff);
            mt[N - 1] = mt[M - 1] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
            mti = 0;
        }
        y = mt[mti++];
        y ^= (y >>> 11);
        y ^= (y << 7) & 0x9d2c5680;
        y ^= (y << 15) & 0xefc60000;
        y ^= (y >>> 18);
        return y;
    }

    /** {@code random()}: [0, 1) 53비트. */
    double random() {
        long a = Integer.toUnsignedLong(genrandUint32()) >>> 5;
        long b = Integer.toUnsignedLong(genrandUint32()) >>> 6;
        return (a * 67108864.0 + b) * (1.0 / 9007199254740992.0);
    }

    double uniform(double a, double b) {
        return a + (b - a) * random();
    }

    private long getrandbits(int k) {
        return Integer.toUnsignedLong(genrandUint32()) >>> (32 - k);
    }

    private int randbelow(int n) {
        int k = 32 - Integer.numberOfLeadingZeros(n);
        long r = getrandbits(k);
        while (r >= n) {
            r = getrandbits(k);
        }
        return (int) r;
    }

    int randint(int a, int b) {
        return a + randbelow(b - a + 1);
    }

    <T> T choice(List<T> seq) {
        return seq.get(randbelow(seq.size()));
    }
}
