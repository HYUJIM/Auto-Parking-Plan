/**
 * shapely 2.2 / GEOS 3.14.1 과 같은 결과를 내는 기하 연산 (파이썬 주차 엔진과의 동등성용).
 *
 * <p>JTS 와 GEOS 는 같은 뿌리지만 버퍼 상수·단순화·마이터 교점, 합집합 순서, 선형 참조 길이 계산(hypot 대 sqrt),
 * 최대 내접원 정확해, make_valid 알고리즘, 다각형화 그래프의 순회 순서(HashSet)가 달라 정점 좌표·순서가 달라진다.
 * {@link com.autoparkingplan.parking.geom.Shapely} 가 GEOS 쪽 동작을 따르는 진입점이고,
 * {@code buffer}·{@code polygonize} 하위 패키지는 JTS 1.20 원본(EPL 2.0 / EDL 1.0)을 GEOS 판에 맞게 고친 것이다.
 * {@code src/test/.../GeosCompatTest} 가 shapely 결과와 WKB 바이트 단위로 비교한다.
 */
package com.autoparkingplan.parking.geom;
