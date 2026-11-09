package com.autoparkingplan.server.review;

import org.locationtech.jts.geom.Geometry;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.server.security.Sanitize;

import tools.jackson.databind.JsonNode;

/** GeoJSON(Polygon·MultiPolygon) → JTS (shapely.geometry.shape 와 같이 고리를 닫는다). */
final class GeoJson {
    private GeoJson() {
    }

    static Geometry toGeometry(JsonNode geom) {
        String type = Json.text(geom, "type");
        if (!"Polygon".equals(type) && !"MultiPolygon".equals(type)) {
            throw new IllegalArgumentException("지원하지 않는 도형: " + type);
        }
        return Sanitize.toGeometry(type, geom.get("coordinates"));
    }
}
