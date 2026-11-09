package com.autoparkingplan.server.support;

import java.util.List;

import com.autoparkingplan.external.AddressCandidate;
import com.autoparkingplan.external.JusoApi;

/** 주소 검색 가짜 (파이썬 tests/conftest.py StubJuso): 지번·도로명 주소에 검색어가 들어 있는 후보. */
public class StubJuso implements JusoApi {
    private final List<AddressCandidate> cands;

    public StubJuso(List<AddressCandidate> cands) {
        this.cands = cands;
    }

    @Override
    public List<AddressCandidate> search(String keyword, int page, int perPage) {
        return cands.stream()
                .filter(c -> c.jibunAddress().contains(keyword) || (c.roadAddress() != null ? c.roadAddress() : "").contains(keyword)).toList();
    }
}
