package com.autoparkingplan.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.UUID;

/** UUID 도우미: 파이썬 {@code uuid.uuid5} 호환, 시간순 UUIDv7. */
public final class Uuids {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static long lastMs = -1;
    private static int counter;

    private Uuids() {
    }

    /**
     * 시간순 UUID (RFC 9562 v7, 같은 밀리초 안에서는 12비트 카운터로 단조 증가).
     *
     * <p>DB 행 id 로 쓰면 id 순서 = 만든 순서가 된다. 자식 목록(규칙 값·근거·원장 사실 등)을 id 순으로 읽어
     * DB 종류·실행 계획과 관계없이 저장한 순서로 돌려받는다 (파이썬판은 DB 가 돌려준 순서를 그대로 썼다).
     */
    public static UUID v7() {
        long ms;
        int seq;
        synchronized (Uuids.class) {
            long now = System.currentTimeMillis();
            if (now > lastMs) {
                lastMs = now;
                counter = RANDOM.nextInt(1 << 10); // 시작값은 무작위(하위 절반)로, 증가 여유를 남긴다
            } else if (++counter >= (1 << 12)) {
                lastMs++; // 카운터가 넘치면 다음 밀리초로 넘어간다
                counter = 0;
            }
            ms = lastMs;
            seq = counter;
        }
        long msb = (ms << 16) | (0x7L << 12) | seq;
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    public static UUID uuid5(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            ByteBuffer ns = ByteBuffer.allocate(16).putLong(namespace.getMostSignificantBits()).putLong(namespace.getLeastSignificantBits());
            sha1.update(ns.array());
            sha1.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] h = sha1.digest();
            h[6] = (byte) ((h[6] & 0x0f) | 0x50); // 버전 5
            h[8] = (byte) ((h[8] & 0x3f) | 0x80); // RFC 4122 변형
            ByteBuffer b = ByteBuffer.wrap(h, 0, 16);
            return new UUID(b.getLong(), b.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
