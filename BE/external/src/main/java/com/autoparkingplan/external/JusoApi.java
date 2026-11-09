package com.autoparkingplan.external;

import java.util.List;

/**
 * 주소 검색 기능 (필지 서비스가 쓰는 {@link JusoClient} 의 메서드). 파이썬판은 덕 타이핑으로 가짜 객체를 끼워 넣었으므로
 * 자바판은 이 인터페이스로 바꿔 끼운다 (테스트용 가짜 구현).
 */
public interface JusoApi {
    /** 주소 검색. [] = 결과 없음. 인증·검색어·서버 오류는 UpstreamError(검색어 오류는 JusoQueryError). */
    List<AddressCandidate> search(String keyword, int page, int perPage);

    /** page=1, per_page=10. */
    default List<AddressCandidate> search(String keyword) {
        return search(keyword, 1, 10);
    }
}
