package com.autoparkingplan.server.support;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.external.CadastralParcel;
import com.autoparkingplan.external.LandCharacteristics;
import com.autoparkingplan.external.LandUseDesignation;
import com.autoparkingplan.external.UpstreamError;
import com.autoparkingplan.external.VWorldApi;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** VWorldClient 와 같은 메서드를 가진 테스트용 가짜 (파이썬 tests/conftest.py StubVWorld). */
public class StubVWorld implements VWorldApi {
    public static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 6, 3, 0, 0, 0, ZoneOffset.UTC);

    /**
     * 가짜 필지 하나.
     *
     * @param designations (kind, name, conflict)
     */
    public record StubParcel(String address, ObjectNode geometry, Double area, List<String[]> designations, boolean landUseError, String jibun,
            String category) {
        public StubParcel(String address, ObjectNode geometry, Double area, List<String[]> designations) {
            this(address, geometry, area, designations, false, null, "대");
        }
    }

    public final Map<String, StubParcel> parcels;

    public StubVWorld(Map<String, StubParcel> parcels) {
        this.parcels = parcels;
    }

    /** 파이썬 {@code square(lng, lat, dx=0.0004, dy=0.0003)}: MultiPolygon 사각형. */
    public static ObjectNode square(double lng, double lat) {
        return square(lng, lat, 0.0004, 0.0003);
    }

    public static ObjectNode square(double lng, double lat, double dx, double dy) {
        ObjectNode g = Json.object();
        g.put("type", "MultiPolygon");
        ArrayNode ring = g.putArray("coordinates").addArray().addArray();
        double[][] pts = {{lng, lat}, {lng + dx, lat}, {lng + dx, lat + dy}, {lng, lat + dy}, {lng, lat}};
        for (double[] p : pts) {
            ring.addArray().add(p[0]).add(p[1]);
        }
        return g;
    }

    @Override
    public Optional<CadastralParcel> getParcel(String pnu) {
        StubParcel p = parcels.get(pnu);
        if (p == null) {
            return Optional.empty();
        }
        ObjectNode raw = Json.object();
        raw.put("is_example", true);
        return Optional.of(new CadastralParcel(pnu, p.jibun(), p.address(), p.geometry().deepCopy(), null, "stub:cadastral", NOW, raw));
    }

    @Override
    public Optional<CadastralParcel> getParcelAt(double lng, double lat) {
        return Optional.empty();
    }

    @Override
    public List<LandUseDesignation> getLandUse(String pnu) {
        StubParcel p = parcels.get(pnu);
        if (p.landUseError()) {
            throw new UpstreamError("vworld:getLandUseAttr", "server error");
        }
        List<LandUseDesignation> out = new ArrayList<>();
        for (String[] d : p.designations()) {
            out.add(new LandUseDesignation(d[0], d[1], null, d[2], null, "stub:landuse", NOW));
        }
        return out;
    }

    @Override
    public Optional<LandCharacteristics> getLandCharacteristics(String pnu) {
        StubParcel p = parcels.get(pnu);
        return Optional.of(new LandCharacteristics(pnu, p.area(), p.category() != null ? p.category() : "대", null, null, "stub:landchar", NOW));
    }
}
