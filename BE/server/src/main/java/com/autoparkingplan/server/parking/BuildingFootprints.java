package com.autoparkingplan.server.parking;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autoparkingplan.external.BuildingFootprint;
import com.autoparkingplan.external.UpstreamError;
import com.autoparkingplan.external.VWorldApi;

/**
 * 배치 계산용 기존 건물 윤곽선 조회 (브이월드 '도로명주소 건물') + 짧은 메모리 캐시.
 *
 * <p>같은 필지를 '건물 유지'·'철거 가정'으로 번갈아 계산하거나 화면을 다시 열 때 브이월드를 거듭 부르지 않으려고 필지·경계 상자별로
 * {@link #TTL} 동안 기억한다. 실패는 기억하지 않는다 (다음 요청이 다시 시도한다). 조회는 예외를 던지지 않고 실패를 결과로 돌려준다 —
 * 건물 정보가 없어도 배치 계산은 계속한다.
 */
public final class BuildingFootprints {
    private static final Logger log = LoggerFactory.getLogger(BuildingFootprints.class);
    public static final String SOURCE = "브이월드 도로명주소 건물";
    static final Duration TTL = Duration.ofHours(24);
    static final int MAX_ENTRIES = 512;

    /** 조회 결과. ok=false 이면 items 는 비어 있다. */
    public record Lookup(boolean ok, List<BuildingFootprint> items) {
        static Lookup failed() {
            return new Lookup(false, List.of());
        }
    }

    private record Entry(List<BuildingFootprint> items, long expiresAtNs) {
    }

    private final VWorldApi vworld;
    private final LongSupplier clock;
    private final Map<String, Entry> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public BuildingFootprints(VWorldApi vworld) {
        this(vworld, System::nanoTime);
    }

    BuildingFootprints(VWorldApi vworld, LongSupplier clock) {
        this.vworld = vworld;
        this.clock = clock;
    }

    /** 브이월드 키가 없으면 건물을 조회하지 않는다 (배치 응답에 existing_buildings 블록도 없다). */
    public boolean enabled() {
        return vworld != null;
    }

    /** 경계 상자 [최소경도, 최소위도, 최대경도, 최대위도] 와 겹치는 건물. */
    public Lookup lookup(String pnu, double[] bbox) {
        if (vworld == null || bbox == null || bbox.length != 4) {
            return Lookup.failed();
        }
        String key = pnu + "|" + bbox[0] + "," + bbox[1] + "," + bbox[2] + "," + bbox[3];
        long now = clock.getAsLong();
        synchronized (cache) {
            Entry e = cache.get(key);
            if (e != null && now - e.expiresAtNs() < 0) {
                return new Lookup(true, e.items());
            }
        }
        List<BuildingFootprint> items;
        try {
            items = List.copyOf(vworld.getBuildingFootprints(bbox[0], bbox[1], bbox[2], bbox[3]));
        } catch (UpstreamError | IllegalArgumentException e) {
            log.warn("건물 윤곽선 조회 실패 (배치는 건물 없이 계산): {}", e.getMessage());
            return Lookup.failed();
        } catch (RuntimeException e) {
            log.warn("건물 윤곽선 조회 중 오류 (배치는 건물 없이 계산): {}", e.toString());
            return Lookup.failed();
        }
        synchronized (cache) {
            cache.put(key, new Entry(items, now + TTL.toNanos()));
        }
        return new Lookup(true, items);
    }
}
