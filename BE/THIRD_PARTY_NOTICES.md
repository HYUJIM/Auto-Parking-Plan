# 제3자 코드 고지

스프링판은 파이썬판(shapely/GEOS, pyproj/PROJ)과 같은 배치 결과를 내기 위해 아래 오픈소스의 알고리즘을 자바로 옮기거나 일부 파일을 고쳐 포함한다.
라이브러리 의존성(Maven Central 에서 받는 jar)은 각 jar 의 라이선스를 따르며 여기에는 적지 않는다.

> **배포 전 확인 필요:** GEOS 는 LGPL-2.1 이다. 아래 'GEOS 이식' 파일이 GEOS 소스의 파생물로 판단되면, 이 파일들(또는 `parking` 모듈)을
> 배포할 때 LGPL-2.1 조건(소스 공개·고지 등)이 적용될 수 있다. GEOS 의 해당 알고리즘은 대부분 JTS(EPL-2.0/EDL-1.0)에서 옮겨진 것이라
> JTS 판을 기준으로 다시 정리하는 방법도 있다. 서비스 배포 방식(서버에서만 실행 / 바이너리 배포)에 맞게 법무 검토를 받는 것을 권한다.

## JTS Topology Suite — EPL-2.0 또는 EDL-1.0 (선택)

Copyright (c) Vivid Solutions, Martin Davis 및 JTS 기여자. <https://github.com/locationtech/jts>

JTS 원본 파일을 가져와 GEOS 와 같은 결과가 나오도록 고친 파일 (각 파일 머리에 원본 라이선스 표기 유지):

- `parking/src/main/java/com/autoparkingplan/parking/geom/buffer/*` (BufferOp, BufferBuilder, BufferCurveSetBuilder, BufferInputLineSimplifier, BufferSubgraph, OffsetCurveBuilder, OffsetSegmentGenerator, OffsetSegmentString, RightmostEdgeFinder, SubgraphDepthLocater)
- `parking/src/main/java/com/autoparkingplan/parking/geom/polygonize/*` (Polygonizer, PolygonizeGraph, PolygonizeEdge, PolygonizeDirectedEdge, EdgeRing, HoleAssigner)

## GEOS — LGPL-2.1

Copyright (c) GEOS 기여자 (저작권자 목록은 GEOS 저장소의 `COPYING`). <https://libgeos.org>

GEOS 3.14.1 의 동작(상수·순서·정밀도)에 맞춰 옮긴 파일:

- `parking/src/main/java/com/autoparkingplan/parking/geom/MakeValid.java`
- `parking/src/main/java/com/autoparkingplan/parking/geom/MaximumInscribedCircle.java`
- `parking/src/main/java/com/autoparkingplan/parking/geom/MinimumRotatedRectangle.java`
- `parking/src/main/java/com/autoparkingplan/parking/geom/RepeatedPointRemover.java`
- 위 JTS 파생 파일 중 GEOS 판 차이를 반영한 부분 (파일 머리 주석에 표시)

## PROJ — MIT

Copyright (c) PROJ 기여자 (저작권자 목록은 PROJ 저장소의 `COPYING` 파일). <https://proj.org>

- `parking/src/main/java/com/autoparkingplan/parking/CoordinateTransformer.java` — PROJ 9.8 `tmerc`(Poder/Engsager exact) 계산식

MIT 라이선스:

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files
(the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge,
publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH
THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
