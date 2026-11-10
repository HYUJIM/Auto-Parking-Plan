# APP (Auto Parking Plan)

주소 하나로 그 땅에 적용되는 건축·주차 법규를 근거와 함께 확인하고, 주차장을 지을 수 있는 부지라면 주차장 배치안을 지도 위에 보여주는 웹 서비스입니다. (1차 지원 지역: 대구광역시 수성구)

| 폴더 | 내용 |
| --- | --- |
| [`FE/`](FE/) | 프론트엔드 — 초기화면(주소 입력) → 이용화면(지도 + 대지·법규 패널), 디자인 키트 `/kit`. 네이버 지도 위에 대지 경계·접도 도로 표시 (React 19 + Vite + TypeScript). 1차는 목업 데이터·FE 자체 개발 API 로 동작하며 BE 연동은 다음 단계 |
| [`BE/`](BE/) | 백엔드 + AI — 법규 서버, 검토 엔진, 쉬운 설명(프론티어 LLM), 주차장 배치 시뮬레이션, 모의 공공 API·종단 검증 도구, AWS 배포 설정 (Spring Boot, Java 21) |

실행 방법과 서비스 설명은 [`FE/README.md`](FE/README.md), [`BE/README.md`](BE/README.md) 를 보세요.

```bash
cd FE && npm install && npm run dev   # http://localhost:5173
```

```bash
cd BE
```
