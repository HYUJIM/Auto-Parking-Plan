package com.autoparkingplan.parking;

/** GeoJSON 을 다각형(Polygon/MultiPolygon)으로 해석할 수 없음. 파이썬 {@code geo.GeoJSONError(ValueError)}. */
public class GeoJSONError extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public GeoJSONError(String message) {
        super(message);
    }

    public GeoJSONError(String message, Throwable cause) {
        super(message, cause);
    }
}
