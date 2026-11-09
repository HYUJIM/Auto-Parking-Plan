package com.autoparkingplan.server.parcels;

import java.util.List;

import com.autoparkingplan.common.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 검토 엔진 입력: 필지 스냅샷 (외부 API 캐시에서 만든 불변 값) — 파이썬 app.parcels.snapshot.ParcelSnapshot.
 *
 * @param jurisdictionCode 시·군·구 5자리 (PNU 앞 5자리)
 * @param designations     null = 조회 실패/미확인, 빈 목록 = 지정 없음
 * @param notices          PARCEL_PARTIAL, DESIGNATION_UNAVAILABLE ({code, message})
 */
public record ParcelSnapshot(String pnu, String roadAddress, String jibunAddress, String jurisdictionCode, String sido, String sigungu,
        boolean supported, Double landAreaM2, String landCategory, List<Designation> designations, JsonNode geometry, JsonNode centroid,
        String roadSide, Long officialLandPrice, List<ObjectNode> sources, List<ObjectNode> notices, List<ObjectNode> buildings,
        boolean isExample) {

    /** 용도지역·지구·구역 하나 (kind: zoning|district|zone|other). */
    public record Designation(String kind, String name, String conflict, String code) {
        public ObjectNode toJson(boolean withCode) {
            ObjectNode o = Json.object();
            o.put("kind", kind);
            o.put("name", name);
            o.put("conflict", conflict == null ? "unknown" : conflict);
            if (withCode) {
                o.put("code", code);
            }
            return o;
        }
    }

    /** review_result.schema 의 parcel 객체. */
    public ObjectNode toResultParcel() {
        ObjectNode o = Json.object();
        o.put("pnu", pnu);
        o.put("road_address", roadAddress);
        o.put("jibun_address", jibunAddress);
        ObjectNode j = o.putObject("jurisdiction");
        j.put("sido", sido);
        j.put("sigungu", sigungu);
        j.put("supported", supported);
        o.put("land_area_m2", landAreaM2);
        o.put("land_category", landCategory);
        if (designations == null) {
            o.putNull("designations");
        } else {
            ArrayNode a = o.putArray("designations");
            designations.forEach(d -> a.add(d.toJson(false)));
        }
        ObjectNode loc = o.putObject("location");
        loc.set("centroid", centroid == null ? Json.nodes().nullNode() : centroid);
        loc.set("geometry", geometry == null ? Json.nodes().nullNode() : geometry);
        ArrayNode src = o.putArray("sources");
        sources.forEach(src::add);
        return o;
    }
}
