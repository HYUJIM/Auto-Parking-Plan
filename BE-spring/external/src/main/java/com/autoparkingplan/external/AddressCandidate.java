package com.autoparkingplan.external;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 도로명주소 검색 API 결과 1건 (파이썬 {@code app.external.types.AddressCandidate}).
 *
 * @param pnu     19자리: 법정동코드10 + 산여부1(1=일반, 2=산) + 본번4 + 부번4
 * @param admCode 법정동코드 10자리
 */
public record AddressCandidate(
        @JsonProperty("road_address") String roadAddress,
        @JsonProperty("jibun_address") String jibunAddress,
        @JsonProperty("pnu") String pnu,
        @JsonProperty("adm_code") String admCode,
        @JsonProperty("sido") String sido,
        @JsonProperty("sigungu") String sigungu,
        @JsonProperty("emd") String emd,
        @JsonProperty("zip_code") String zipCode,
        @JsonProperty("building_name") String buildingName,
        @JsonProperty("source") String source,
        @JsonProperty("fetched_at") OffsetDateTime fetchedAt) {

    /** 파이썬 기본값과 같다: zip_code·building_name·fetched_at = None, source = "juso.go.kr". */
    public AddressCandidate(String roadAddress, String jibunAddress, String pnu, String admCode, String sido, String sigungu,
            String emd) {
        this(roadAddress, jibunAddress, pnu, admCode, sido, sigungu, emd, null, null, "juso.go.kr", null);
    }
}
