package com.autoparkingplan.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** 시간 도우미. 저장은 UTC(마이크로초 단위, DB 정밀도와 같게), 기준일·화면은 Asia/Seoul. */
public final class Times {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private Times() {
    }

    /** 현재 시각 (마이크로초로 자름 — PostgreSQL·H2 timestamp 정밀도). 파이썬 utcnow(). */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public static LocalDate today(ZoneId zone) {
        return LocalDate.now(zone);
    }

    /** 파이썬 {@code datetime.isoformat()} (UTC aware): {@code 2026-11-06T03:04:05.123456+00:00}, 마이크로초가 0 이면 생략. */
    public static String isoformat(Instant t) {
        return isoformat(t.atOffset(ZoneOffset.UTC));
    }

    /** 파이썬 {@code datetime.isoformat()} (aware). 오프셋은 +HH:MM. */
    public static String isoformat(OffsetDateTime t) {
        StringBuilder sb = new StringBuilder(SECONDS.format(t));
        int micros = t.getNano() / 1000;
        if (micros != 0) {
            sb.append('.').append(String.format("%06d", micros));
        }
        int off = t.getOffset().getTotalSeconds();
        sb.append(off < 0 ? '-' : '+');
        off = Math.abs(off);
        sb.append(String.format("%02d:%02d", off / 3600, (off % 3600) / 60));
        if (off % 60 != 0) {
            sb.append(String.format(":%02d", off % 60));
        }
        return sb.toString();
    }

    /** 한국 시각 isoformat, 초 단위 (파이썬 {@code datetime.now(KST).replace(microsecond=0).isoformat()}). */
    public static String kstNowSeconds() {
        return isoformat(OffsetDateTime.now(KST).truncatedTo(ChronoUnit.SECONDS));
    }
}
