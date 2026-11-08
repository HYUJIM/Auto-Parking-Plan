# 법규 원문 수집 기록 (law.go.kr 공개 HTML, 개발용)

- 생성: `scripts/fetch_lawgo_sources.py` · 실행 시작 2026-11-06T12:22:45+09:00 · 검토 기준일 **2026-11-06**
- 수집기: `app/legal/lawgo_html.py` (bs4 + 정규식, AI 미사용) · User-Agent `APP-legal-collector/0.1 (dev)` · 순차 요청, 요청 간 1.2초 이상
- 형식: `app-legal-source@1` (`app/legal/parsed.py`) · 원문 페이지는 받은 바이트 그대로 `raw/` 에 보관
- DRF Open API(`/DRF/lawService.do`)는 OC 인증값이 없어 사용하지 않음(OC=test → '사용자 정보 검증에 실패하였습니다.'). 클라이언트·파서는 `app/legal/lawgo_drf.py` 에 있으나 실제 응답 표본이 없어 가이드 필드 목록으로 만든 표본으로만 시험함.

## 텍스트 규칙

- `text` 는 조 전체: 첫 줄이 화면 그대로의 `제N조(제목)` + ①항 본문이고, 이어서 항·호·목이 원문 순서대로 한 줄씩 (화면의 `<p>` 1개 = 1줄).
- 정규화는 다음만 적용: HTML 표시 규칙의 공백 처리(태그 사이 연속 공백 → 한 칸, 줄 앞뒤 공백 제거), `<br>`→줄바꿈, 빈 문단 제거, `\r\n`→`\n`, NBSP→공백, 줄 끝 공백 제거, 빈 줄 3줄 이상 → 1줄.
- **개정 연혁 주석은 포함한다.** `<개정 2011. 4. 14., …>`, `<신설 …>`, `[전문개정 …]`, `(개정 2008. 12. 30 조례 제4006호)` 등 화면에 보이는 주석을 그대로 남겼다. 규칙 추출 시 주석을 떼려면 파서에서 별도로 처리해야 한다.
- 링크·약칭(「」, ㆍ, ·, ①②, `1.`, `가.`)은 원문 문자 그대로. 화면 아이콘·숨은 요소(display:none)·체크박스는 제외.
- 조문 안에 표가 **이미지**로 실린 곳은 그 자리에 `[[IMAGE flSeq=…]]` 한 줄을 넣었다(원문에 없는 유일한 추가 줄). 이미지 내용은 text 에 없다 — 아래 '누락(본문 이미지)' 참고. 구조 파서는 `^\[\[IMAGE ` 줄을 '내용 미수집' 표시로 다뤄야 한다.
- 별표가 HWP/PDF/이미지(문서뷰어 이미지 변환)로만 제공되면 전사하지 않고 아래 '누락'에 기록했다. law.go.kr 에 HTML 텍스트판이 없었다.
- `v_current_version=confirmed` 는 연혁 목록에서 '시행일 ≤ 기준일 중 가장 늦은 버전'이고 본문 표제 시행일도 기준일 이전일 때만. `v_version_header=confirmed` 는 표제에서 시행일·공포번호·공포일을 모두 읽었고 페이지 숨은 값(ancYd/ancNo/efYd)과 일치할 때만.

## 번들 목록

| 우선 | key | 제명 | 화면 표제 | 시행일 | 일련번호 | 기준일 시행본 | 조문/별표 수 | 파일 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| P1·P3 | `kr.law.nkpa` | 국토의 계획 및 이용에 관한 법률 | [시행 2026. 7. 1.] [법률 제21447호, 2026. 3. 5., 타법개정] | 2026-07-01 | 284013 | 예 (confirmed) | 3 | `kr.law.nkpa__2026-07-01.json` |
| P1·P3 | `kr.decree.nkpa` | 국토의 계획 및 이용에 관한 법률 시행령 | [시행 2026. 9. 18.] [대통령령 제36587호, 2026. 8. 18., 타법개정] | 2026-09-18 | 288857 | 예 (confirmed) | 3 | `kr.decree.nkpa__2026-09-18.json` |
| P1·P3 | `daegu.ord.urban_planning` | 대구광역시 도시계획 조례 | [시행 2026. 5. 11.] [대구광역시조례 제6487호, 2026. 5. 11., 일부개정] | 2026-05-11 | 2128915 | 예 (confirmed) | 12 | `daegu.ord.urban_planning__2026-05-11.json` |
| P2 | `kr.law.parking` | 주차장법 | [시행 2026. 8. 28.] [법률 제21413호, 2026. 2. 27., 일부개정] | 2026-08-28 | 283743 | 예 (confirmed) | 2 | `kr.law.parking__2026-08-28.json` |
| P2 | `kr.rule.parking` | 주차장법 시행규칙 | [시행 2026. 7. 27.] [국토교통부령 제1604호, 2026. 7. 27., 일부개정] | 2026-07-27 | 288421 | 예 (confirmed) | 3 | `kr.rule.parking__2026-07-27.json` |
| P4 | `kr.decree.parking` | 주차장법 시행령 | [시행 2026. 8. 28.] [대통령령 제36623호, 2026. 8. 25., 일부개정] | 2026-08-28 | 288729 | 예 (confirmed) | 1 | `kr.decree.parking__2026-08-28.json` |
| P4 | `daegu.ord.parking` | 대구광역시 주차장 설치 및 관리 조례 | [시행 2025. 12. 10.] [대구광역시조례 제6420호, 2025. 12. 10., 일부개정] | 2025-12-10 | 2091151 | 예 (confirmed) | 3 | `daegu.ord.parking__2025-12-10.json` |
| P4 | `suseong.ord.parking` | 대구광역시 수성구 주차장 설치 및 관리 조례 | [시행 2025. 6. 30.] [대구광역시수성구조례 제1837호, 2025. 6. 30., 일부개정] | 2025-06-30 | 2047061 | 예 (confirmed) | 0 | `suseong.ord.parking__2025-06-30.json` |
| P4 | `daegu.ord.building` | 대구광역시 건축 조례 | [시행 2026. 9. 30.] [대구광역시조례 제6526호, 2026. 9. 30., 일부개정] | 2026-09-30 | 2173139 | 예 (confirmed) | 0 | `daegu.ord.building__2026-09-30.json` |
| P4 | `daegu.ord.building` | 대구광역시 건축 조례 | [시행 2026. 11. 12.] [대구광역시조례 제6551호, 2026. 10. 30., 일부개정] | 2026-11-12 | 2181437 | 아니오 — 시행 예정 | 0 | `daegu.ord.building__2026-11-12.json` |
| P4 | `kr.decree.building` | 건축법 시행령 | [시행 2026. 10. 6.] [대통령령 제36746호, 2026. 10. 6., 일부개정] | 2026-10-06 | 290833 | 예 (confirmed) | 0 | `kr.decree.building__2026-10-06.json` |

## 문서별 상세

### `kr.law.nkpa` — 국토의 계획 및 이용에 관한 법률

- 목적: 용도지역 건폐율·용적률 상한(제77조·제78조), 건축제한 위임(제76조)
- 이름 주소: https://www.law.go.kr/%EB%B2%95%EB%A0%B9/%EA%B5%AD%ED%86%A0%EC%9D%98%EA%B3%84%ED%9A%8D%EB%B0%8F%EC%9D%B4%EC%9A%A9%EC%97%90%EA%B4%80%ED%95%9C%EB%B2%95%EB%A5%A0
- 연혁 목록: https://www.law.go.kr/LSW/lsHstListR.do?lsId=009294 (raw: `raw/kr.law.nkpa__history.html`)
- 연혁 목록상 기준일(2026-11-06) 뒤에 시행되는 버전:
  - `286981` 3. 국토의 계획 및 이용에 관한 법률 [시행 2026. 12. 17.] [법률 제21812호, 2026. 6. 16., 일부개정]
  - `285937` 2. 국토의 계획 및 이용에 관한 법률 [시행 2027. 5. 13.] [법률 제21626호, 2026. 5. 12., 타법개정]
  - `286805` 1. 국토의 계획 및 이용에 관한 법률 [시행 2027. 6. 17.] [법률 제21796호, 2026. 6. 16., 타법개정]
- **시행본** `kr.law.nkpa__2026-07-01.json`
  - 표제: [시행 2026. 7. 1.] [법률 제21447호, 2026. 3. 5., 타법개정]
  - source_url: https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=284013&chrClsCd=010202&urlMode=lsInfoP&efYd=20260701&ancYnChk=0
  - raw: `raw/kr.law.nkpa__2026-07-01__lsInfoR-284013.html`
  - 검증: source_access=confirmed, version_header=confirmed, current_version=confirmed, text_match=not_checked
  - 수집: 제76조(용도지역 및 용도지구에서의 건축물의 건축 제한 등), 제77조(용도지역의 건폐율), 제78조(용도지역에서의 용적률)

### `kr.decree.nkpa` — 국토의 계획 및 이용에 관한 법률 시행령

- 목적: 세분 용도지역 건폐율·용적률 범위(제84조·제85조), 용도지역 건축제한(제71조·별표)
- 이름 주소: https://www.law.go.kr/%EB%B2%95%EB%A0%B9/%EA%B5%AD%ED%86%A0%EC%9D%98%EA%B3%84%ED%9A%8D%EB%B0%8F%EC%9D%B4%EC%9A%A9%EC%97%90%EA%B4%80%ED%95%9C%EB%B2%95%EB%A5%A0%EC%8B%9C%ED%96%89%EB%A0%B9
- 연혁 목록: https://www.law.go.kr/LSW/lsHstListR.do?lsId=009419 (raw: `raw/kr.decree.nkpa__history.html`)
- **시행본** `kr.decree.nkpa__2026-09-18.json`
  - 표제: [시행 2026. 9. 18.] [대통령령 제36587호, 2026. 8. 18., 타법개정]
  - source_url: https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=288857&chrClsCd=010202&urlMode=lsInfoP&efYd=20260918&ancYnChk=0
  - raw: `raw/kr.decree.nkpa__2026-09-18__lsInfoR-288857.html`, `raw/kr.decree.nkpa__2026-09-18__annex-18442155.html`, `raw/kr.decree.nkpa__2026-09-18__annex-18442157.html`, `raw/kr.decree.nkpa__2026-09-18__annex-18442279.html`, `raw/kr.decree.nkpa__2026-09-18__annex-18442281.html`, `raw/kr.decree.nkpa__2026-09-18__annex-18442285.html`, `raw/kr.decree.nkpa__2026-09-18__annex-18442301.html`
  - 검증: source_access=confirmed, version_header=confirmed, current_version=confirmed, text_match=not_checked
  - 수집: 제71조(용도지역안에서의 건축제한), 제84조(용도지역안에서의 건폐율), 제85조(용도지역 안에서의 용적률)
  - 별표 확인: 별표 4: 「[별표 4] 제1종일반주거지역안에서 건축할 수 있는 건축물(제71조제1항제3호관련)」 bylSeq=18442155, 제공 형태 attachment_only:HWP/PDF/이미지/문서뷰어(이미지 변환), 첨부 flDownload.do?gubun=&flSeq=168871289&bylClsCd=110201, flDownload.do?gubun=&flSeq=168871291&bylClsCd=110201, flDownload.do?gubun=&flExt=hwpx&flSeq=168871289&bylClsCd=110201
  - 별표 확인: 별표 5: 「[별표 5] 제2종일반주거지역안에서 건축할 수 있는 건축물(제71조제1항제4호관련)」 bylSeq=18442157, 제공 형태 attachment_only:HWP/PDF/이미지/문서뷰어(이미지 변환), 첨부 flDownload.do?gubun=&flSeq=168871299&bylClsCd=110201, flDownload.do?gubun=&flSeq=168871301&bylClsCd=110201, flDownload.do?gubun=&flExt=hwpx&flSeq=168871299&bylClsCd=110201
  - 별표 확인: 별표 6: 「[별표 6] 제3종일반주거지역안에서 건축할 수 있는 건축물(제71조제1항제5호관련)」 bylSeq=18442279, 제공 형태 attachment_only:HWP/PDF/이미지/문서뷰어(이미지 변환), 첨부 flDownload.do?gubun=&flSeq=168871307&bylClsCd=110201, flDownload.do?gubun=&flSeq=168871309&bylClsCd=110201, flDownload.do?gubun=&flExt=hwpx&flSeq=168871307&bylClsCd=110201
  - 별표 확인: 별표 7: 「[별표 7] 준주거지역안에서 건축할 수 없는 건축물(제71조제1항제6호 관련)」 bylSeq=18442281, 제공 형태 attachment_only:HWP/PDF/이미지/문서뷰어(이미지 변환), 첨부 flDownload.do?gubun=&flSeq=168871315&bylClsCd=110201, flDownload.do?gubun=&flSeq=168871317&bylClsCd=110201, flDownload.do?gubun=&flExt=hwpx&flSeq=168871315&bylClsCd=110201
  - 별표 확인: 별표 9: 「[별표 9] 일반상업지역안에서 건축할 수 없는 건축물(제71조제1항제8호 관련)」 bylSeq=18442285, 제공 형태 attachment_only:HWP/PDF/이미지/문서뷰어(이미지 변환), 첨부 flDownload.do?gubun=&flSeq=168871331&bylClsCd=110201, flDownload.do?gubun=&flSeq=168871333&bylClsCd=110201, flDownload.do?gubun=&flExt=hwpx&flSeq=168871331&bylClsCd=110201
  - 별표 확인: 별표 17: 「[별표 17] 자연녹지지역 안에서 건축할 수 있는 건축물(제71조제1항제16호관련)」 bylSeq=18442301, 제공 형태 attachment_only:HWP/PDF/이미지/문서뷰어(이미지 변환), 첨부 flDownload.do?gubun=&flSeq=168871391&bylClsCd=110201, flDownload.do?gubun=&flSeq=168871393&bylClsCd=110201, flDownload.do?gubun=&flExt=hwpx&flSeq=168871391&bylClsCd=110201
  - **누락** 별표 4: 별표가 첨부파일로만 제공됨 (attachment_only:HWP/PDF/이미지/문서뷰어(이미지 변환)) — 전사하지 않음. 제목: [별표 4] 제1종일반주거지역안에서 건축할 수 있는 건축물(제71조제1항제3호관련)
  - **누락** 별표 5: 별표가 첨부파일로만 제공됨 (attachment_only:HWP/PDF/이미지/문서뷰어(이미지 변환)) — 전사하지 않음. 제목: [별표 5] 제2종일반주거지역안에서 건축할 수 있는 건축물(제71조제1항제4호관련)
  - **누락** 별표 6: 별표가 첨부파일로만 제공됨 (attachment_only:HWP/PDF/이미지/문서뷰어(이미지 변환)) — 전사하지 않음. 제목: [별표 6] 제3종일반주거지역안에서 건축할 수 있는 건축물(제71조제1항제5호관련)
  - **누락** 별표 7: 별표가 첨부파일로만 제공됨 (attachment_only:HWP/PDF/이미지/문서뷰어(이미지 변환)) — 전사하지 않음. 제목: [별표 7] 준주거지역안에서 건축할 수 없는 건축물(제71조제1항제6호 관련)
  - **누락** 별표 9: 별표가 첨부파일로만 제공됨 (attachment_only:HWP/PDF/이미지/문서뷰어(이미지 변환)) — 전사하지 않음. 제목: [별표 9] 일반상업지역안에서 건축할 수 없는 건축물(제71조제1항제8호 관련)
  - **누락** 별표 17: 별표가 첨부파일로만 제공됨 (attachment_only:HWP/PDF/이미지/문서뷰어(이미지 변환)) — 전사하지 않음. 제목: [별표 17] 자연녹지지역 안에서 건축할 수 있는 건축물(제71조제1항제16호관련)

### `daegu.ord.urban_planning` — 대구광역시 도시계획 조례

- 목적: 대구 용도지역 건폐율(제75조)·용적률(제80조), 용도지역별 건축제한(제33~36·38·46조), 약칭 정의(제2·3·30조)
- 이름 주소: https://www.law.go.kr/%EC%9E%90%EC%B9%98%EB%B2%95%EA%B7%9C/%EB%8C%80%EA%B5%AC%EA%B4%91%EC%97%AD%EC%8B%9C%EB%8F%84%EC%8B%9C%EA%B3%84%ED%9A%8D%EC%A1%B0%EB%A1%80
- 연혁 목록: https://www.law.go.kr/LSW/ordinHstListR.do?ordinId=2152107 (raw: `raw/daegu.ord.urban_planning__history.html`)
- **시행본** `daegu.ord.urban_planning__2026-05-11.json`
  - 표제: [시행 2026. 5. 11.] [대구광역시조례 제6487호, 2026. 5. 11., 일부개정]
  - source_url: https://www.law.go.kr/LSW/ordinInfoP.do?ordinSeq=2128915&chrClsCd=010202&gubun=ELIS
  - raw: `raw/daegu.ord.urban_planning__2026-05-11__ordinInfoR-2128915.html`
  - 검증: source_access=confirmed, version_header=confirmed, current_version=confirmed, text_match=not_checked
  - 수집: 제1조(목적), 제2조(도시계획 및 관리의 기본방향), 제3조(자문단 등), 제30조(이행보증금의 예치금액 등), 제33조(제1종일반주거지역 안에서 건축할 수 있는 건축물), 제34조(제2종일반주거지역 안에서 건축할 수 있는 건축물), 제35조(제3종일반주거지역 안에서 건축할 수 있는 건축물), 제36조(준주거지역 안에서 건축할 수 없는 건축물), 제38조(일반상업지역 안에서 건축할 수 없는 건축물), 제46조(자연녹지지역 안에서 건축할 수 있는 건축물), 제75조(용도지역 안에서의 건폐율), 제80조(용도지역 안에서의 용적률)
  - aliases: `{"법": "kr.law.nkpa", "영": "kr.decree.nkpa"}`
    - '법' = 「국토의 계획 및 이용에 관한 법률」 (제2조에서 정의); 본문 링크 법령ID ['009294'] → aliases['법'] = 'kr.law.nkpa'
    - '영' = 「국토의 계획 및 이용에 관한 법률 시행령」 (제3조에서 정의); 본문 링크 법령ID ['009419'] → aliases['영'] = 'kr.decree.nkpa'
    - '규칙' = 「국토의 계획 및 이용에 관한 법률 시행규칙」 (제30조에서 정의); 본문 링크 법령ID 없음 → 이 저장소에 번들이 없어 aliases 에 넣지 않음

### `kr.law.parking` — 주차장법

- 목적: 주차장 설비기준 위임(제6조), 부설주차장 설치·지정(제19조)
- 이름 주소: https://www.law.go.kr/%EB%B2%95%EB%A0%B9/%EC%A3%BC%EC%B0%A8%EC%9E%A5%EB%B2%95
- 연혁 목록: https://www.law.go.kr/LSW/lsHstListR.do?lsId=001814 (raw: `raw/kr.law.parking__history.html`)
- **시행본** `kr.law.parking__2026-08-28.json`
  - 표제: [시행 2026. 8. 28.] [법률 제21413호, 2026. 2. 27., 일부개정]
  - source_url: https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=283743&chrClsCd=010202&urlMode=lsInfoP&efYd=20260828&ancYnChk=0
  - raw: `raw/kr.law.parking__2026-08-28__lsInfoR-283743.html`
  - 검증: source_access=confirmed, version_header=confirmed, current_version=confirmed, text_match=not_checked
  - 수집: 제6조(주차장설비기준 등), 제19조(부설주차장의 설치ㆍ지정)

### `kr.rule.parking` — 주차장법 시행규칙

- 목적: 주차구획(제3조), 노외주차장 구조·설비(제6조), 부설주차장 구조·설비(제11조)
- 이름 주소: https://www.law.go.kr/%EB%B2%95%EB%A0%B9/%EC%A3%BC%EC%B0%A8%EC%9E%A5%EB%B2%95%EC%8B%9C%ED%96%89%EA%B7%9C%EC%B9%99
- 연혁 목록: https://www.law.go.kr/LSW/lsHstListR.do?lsId=008238 (raw: `raw/kr.rule.parking__history.html`)
- **시행본** `kr.rule.parking__2026-07-27.json`
  - 표제: [시행 2026. 7. 27.] [국토교통부령 제1604호, 2026. 7. 27., 일부개정]
  - source_url: https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=288421&chrClsCd=010202&urlMode=lsInfoP&efYd=20260727&ancYnChk=0
  - raw: `raw/kr.rule.parking__2026-07-27__lsInfoR-288421.html`
  - 검증: source_access=confirmed, version_header=confirmed, current_version=confirmed, text_match=not_checked
  - 수집: 제3조(주차장의 주차구획), 제6조(노외주차장의 구조ㆍ설비기준), 제11조(부설주차장의 구조ㆍ설비기준)
  - **누락(본문 이미지)** 제3조 `[[IMAGE flSeq=24335428]]` (https://www.law.go.kr/LSW/flDownload.do?flSeq=24335428): 표가 이미지로만 실려 있어 text 에 없음. 참고로 law.go.kr 이 붙인 대체텍스트(alt) 원문은 아래와 같다 — 이미지 원본과 대조하지 않았고 오기가 있을 수 있으므로(예: '3.3.미터') 규칙 근거로 쓰지 말 것.

    ```text
    구분 너비 길이
    경형 1.7미터 이상 4.5미터 이상
    일반형 2.0미터 이상 6.0미터 이상
    보도와 차도의 구분이 없는 주거지역의 도로 2.0미터 이상 5.0미터 이상
    이륜자동차전용 1.0미터 이상 2.3미터 이상
    ```

  - **누락(본문 이미지)** 제3조 `[[IMAGE flSeq=33758907]]` (https://www.law.go.kr/LSW/flDownload.do?flSeq=33758907): 표가 이미지로만 실려 있어 text 에 없음. 참고로 law.go.kr 이 붙인 대체텍스트(alt) 원문은 아래와 같다 — 이미지 원본과 대조하지 않았고 오기가 있을 수 있으므로(예: '3.3.미터') 규칙 근거로 쓰지 말 것.

    ```text
    구분 너비 길이
    경형 2.0미터 이상 3.6미터 이상
    일반형 2.5미터 이상 5.0미터 이상
    확장형 2.6미터 이상 5.2미터 이상
    장애인전용 3.3미터 이상 5.0미터 이상
    이륜자동차 전용 1.0미터 이상 2.3미터 이상
    ```

  - **누락(본문 이미지)** 제6조 `[[IMAGE flSeq=24335457]]` (https://www.law.go.kr/LSW/flDownload.do?flSeq=24335457): 표가 이미지로만 실려 있어 text 에 없음. 참고로 law.go.kr 이 붙인 대체텍스트(alt) 원문은 아래와 같다 — 이미지 원본과 대조하지 않았고 오기가 있을 수 있으므로(예: '3.3.미터') 규칙 근거로 쓰지 말 것.

    ```text
    주차형식 차로의 너비 출입구가 2개 이상인 경우 출입구가 1개인 경우
    평행주차 2.25미터 3.5미터
    직각주차 4.0미터 4.0미터
    45도 대향(對向)주차 2.3미터 3.5미터
    ```

  - **누락(본문 이미지)** 제6조 `[[IMAGE flSeq=24335458]]` (https://www.law.go.kr/LSW/flDownload.do?flSeq=24335458): 표가 이미지로만 실려 있어 text 에 없음. 참고로 law.go.kr 이 붙인 대체텍스트(alt) 원문은 아래와 같다 — 이미지 원본과 대조하지 않았고 오기가 있을 수 있으므로(예: '3.3.미터') 규칙 근거로 쓰지 말 것.

    ```text
    주차형식 차로의 너비 출입구가 2개 이상인 경우 출입구가 1개인 경우
    평행주차 3.3.미터 5.0미터
    직각주차 6.0미터 6.0미터
    60도 대향주차 4.5미터 5.5미터
    45도 대향주차 3.5미터  5.0미터
    교차주차 3.5미터 5.0미터
    ```

  - **누락(본문 이미지)** 제11조 `[[IMAGE flSeq=24335465]]` (https://www.law.go.kr/LSW/flDownload.do?flSeq=24335465): 표가 이미지로만 실려 있어 text 에 없음. 참고로 law.go.kr 이 붙인 대체텍스트(alt) 원문은 아래와 같다 — 이미지 원본과 대조하지 않았고 오기가 있을 수 있으므로(예: '3.3.미터') 규칙 근거로 쓰지 말 것.

    ```text
    주차형식 차로의 너비
    평행주차 3.0미터
    직각주차 6.0미터
    60도 대향주차 4.0미터
    45도 대향주차 3.5미터
    교차주차 3.5미터
    ```

  - 경고: 제3조: 조문 안에 내용 이미지가 있음 ([[IMAGE flSeq=24335428]]) — 이미지 내용은 text 에 없고 그 자리에 표시 줄만 넣음
  - 경고: 제3조: 조문 안에 내용 이미지가 있음 ([[IMAGE flSeq=33758907]]) — 이미지 내용은 text 에 없고 그 자리에 표시 줄만 넣음
  - 경고: 제6조: 조문 안에 내용 이미지가 있음 ([[IMAGE flSeq=24335457]]) — 이미지 내용은 text 에 없고 그 자리에 표시 줄만 넣음
  - 경고: 제6조: 조문 안에 내용 이미지가 있음 ([[IMAGE flSeq=24335458]]) — 이미지 내용은 text 에 없고 그 자리에 표시 줄만 넣음
  - 경고: 제11조: 조문 안에 내용 이미지가 있음 ([[IMAGE flSeq=24335465]]) — 이미지 내용은 text 에 없고 그 자리에 표시 줄만 넣음

### `kr.decree.parking` — 주차장법 시행령

- 목적: 부설주차장 설치기준(제6조, 별표 1)
- 이름 주소: https://www.law.go.kr/%EB%B2%95%EB%A0%B9/%EC%A3%BC%EC%B0%A8%EC%9E%A5%EB%B2%95%EC%8B%9C%ED%96%89%EB%A0%B9
- 연혁 목록: https://www.law.go.kr/LSW/lsHstListR.do?lsId=004946 (raw: `raw/kr.decree.parking__history.html`)
- **시행본** `kr.decree.parking__2026-08-28.json`
  - 표제: [시행 2026. 8. 28.] [대통령령 제36623호, 2026. 8. 25., 일부개정]
  - source_url: https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=288729&chrClsCd=010202&urlMode=lsInfoP&efYd=20260828&ancYnChk=0
  - raw: `raw/kr.decree.parking__2026-08-28__lsInfoR-288729.html`, `raw/kr.decree.parking__2026-08-28__annex-18384279.html`
  - 검증: source_access=confirmed, version_header=confirmed, current_version=confirmed, text_match=not_checked
  - 수집: 제6조(부설주차장의 설치기준)
  - 별표 확인: 별표 1: 「[별표 1] 부설주차장의 설치대상 시설물 종류 및 설치기준(제6조제1항 관련)」 bylSeq=18384279, 제공 형태 attachment_only:HWP/PDF/이미지/문서뷰어(이미지 변환), 첨부 flDownload.do?gubun=&flSeq=168270375&bylClsCd=110201, flDownload.do?gubun=&flSeq=168270377&bylClsCd=110201, flDownload.do?gubun=&flExt=hwpx&flSeq=168270375&bylClsCd=110201
  - **누락** 별표 1: 별표가 첨부파일로만 제공됨 (attachment_only:HWP/PDF/이미지/문서뷰어(이미지 변환)) — 전사하지 않음. 제목: [별표 1] 부설주차장의 설치대상 시설물 종류 및 설치기준(제6조제1항 관련)

### `daegu.ord.parking` — 대구광역시 주차장 설치 및 관리 조례

- 목적: 대구 부설주차장 설치기준(제15조, 별표 2), 약칭 정의(제1조의2)
- 이름 주소: https://www.law.go.kr/%EC%9E%90%EC%B9%98%EB%B2%95%EA%B7%9C/%EB%8C%80%EA%B5%AC%EA%B4%91%EC%97%AD%EC%8B%9C%EC%A3%BC%EC%B0%A8%EC%9E%A5%EC%84%A4%EC%B9%98%EB%B0%8F%EA%B4%80%EB%A6%AC%EC%A1%B0%EB%A1%80
- 연혁 목록: https://www.law.go.kr/LSW/ordinHstListR.do?ordinId=2145152 (raw: `raw/daegu.ord.parking__history.html`)
- **시행본** `daegu.ord.parking__2025-12-10.json`
  - 표제: [시행 2025. 12. 10.] [대구광역시조례 제6420호, 2025. 12. 10., 일부개정]
  - source_url: https://www.law.go.kr/LSW/ordinInfoP.do?ordinSeq=2091151&chrClsCd=010202&gubun=ELIS
  - raw: `raw/daegu.ord.parking__2025-12-10__ordinInfoR-2091151.html`, `raw/daegu.ord.parking__2025-12-10__annex-21279951.html`
  - 검증: source_access=confirmed, version_header=confirmed, current_version=confirmed, text_match=not_checked
  - 수집: 제1조(목적), 제1조의2(주차장수급실태조사), 제15조(부설주차장의 설치기준 등)
  - aliases: `{"법": "kr.law.parking", "시행규칙": "kr.rule.parking", "영": "kr.decree.parking"}`
    - '법' = 「주차장법」 (제1조의2에서 정의); 본문 링크 법령ID ['001814'] → aliases['법'] = 'kr.law.parking'
    - '시행규칙' = 「주차장법 시행규칙」 (제1조의2에서 정의); 본문 링크 법령ID ['008238'] → aliases['시행규칙'] = 'kr.rule.parking'
    - '영' = 「주차장법 시행령」 (제15조에서 정의); 본문 링크 법령ID 없음 → aliases['영'] = 'kr.decree.parking'
  - 별표 확인: 별표 2: 「[별표 2] 부설주차장의 설치대상시설물 종류 및 설치기준」 bylSeq=21279951, 제공 형태 attachment_only:HWP/문서뷰어(이미지 변환), 첨부 flDownload.do?gubun=ELIS&flSeq=160191843&flNm=%5B%EB%B3%84%ED%91%9C+2%5D+%EB%B6%80%EC%84%A4%EC%A3%BC%EC%B0%A8%EC%9E%A5%EC%9D%98+%EC%84%A4%EC%B9%98%EB%8C%80%EC%83%81%EC%8B%9C%EC%84%A4%EB%AC%BC+%EC%A2%85%EB%A5%98+%EB%B0%8F+%EC%84%A4%EC%B9%98%EA%B8%B0%EC%A4%80
  - **누락** 별표 2: 별표가 첨부파일로만 제공됨 (attachment_only:HWP/문서뷰어(이미지 변환)) — 전사하지 않음. 제목: [별표 2] 부설주차장의 설치대상시설물 종류 및 설치기준

### `suseong.ord.parking` — 대구광역시 수성구 주차장 설치 및 관리 조례

- 목적: 수성구 주차장 조례 존재·버전 표제 확인 (조문 미수집)
- 이름 주소: https://www.law.go.kr/%EC%9E%90%EC%B9%98%EB%B2%95%EA%B7%9C/%EB%8C%80%EA%B5%AC%EA%B4%91%EC%97%AD%EC%8B%9C%EC%88%98%EC%84%B1%EA%B5%AC%EC%A3%BC%EC%B0%A8%EC%9E%A5%EC%84%A4%EC%B9%98%EB%B0%8F%EA%B4%80%EB%A6%AC%EC%A1%B0%EB%A1%80
- 연혁 목록: https://www.law.go.kr/LSW/ordinHstListR.do?ordinId=2049947 (raw: `raw/suseong.ord.parking__history.html`)
- **시행본** `suseong.ord.parking__2025-06-30.json`
  - 표제: [시행 2025. 6. 30.] [대구광역시수성구조례 제1837호, 2025. 6. 30., 일부개정]
  - source_url: https://www.law.go.kr/LSW/ordinInfoP.do?ordinSeq=2047061&chrClsCd=010202&gubun=ELIS
  - raw: `raw/suseong.ord.parking__2025-06-30__ordinInfoR-2047061.html`
  - 검증: source_access=confirmed, version_header=confirmed, current_version=confirmed, text_match=not_checked

### `daegu.ord.building` — 대구광역시 건축 조례

- 목적: 버전 표제만: 기준일 시행본 + 시행 예정본(PENDING_AMENDMENT 시연용)
- 이름 주소: https://www.law.go.kr/%EC%9E%90%EC%B9%98%EB%B2%95%EA%B7%9C/%EB%8C%80%EA%B5%AC%EA%B4%91%EC%97%AD%EC%8B%9C%EA%B1%B4%EC%B6%95%EC%A1%B0%EB%A1%80
- 연혁 목록: https://www.law.go.kr/LSW/ordinHstListR.do?ordinId=2141983 (raw: `raw/daegu.ord.building__history.html`)
- 사이트가 '현행'으로 표시한 버전(2181437: 1. 대구광역시 건축 조례[시행 2026. 11. 12.] [조례 제6551호, 2026. 10. 30., 일부개정])은 기준일 2026-11-06 현재 시행 전 — 시행일 기준으로 2173139 를 시행본으로 사용
- 연혁 목록상 기준일(2026-11-06) 뒤에 시행되는 버전:
  - `2181437` 1. 대구광역시 건축 조례[시행 2026. 11. 12.] [조례 제6551호, 2026. 10. 30., 일부개정]
- **시행본** `daegu.ord.building__2026-09-30.json`
  - 표제: [시행 2026. 9. 30.] [대구광역시조례 제6526호, 2026. 9. 30., 일부개정]
  - source_url: https://www.law.go.kr/LSW/ordinInfoP.do?ordinSeq=2173139&chrClsCd=010202&gubun=ELIS
  - raw: `raw/daegu.ord.building__2026-09-30__ordinInfoR-2173139.html`
  - 검증: source_access=confirmed, version_header=confirmed, current_version=confirmed, text_match=not_checked
- **시행 예정본** `daegu.ord.building__2026-11-12.json`
  - 표제: [시행 2026. 11. 12.] [대구광역시조례 제6551호, 2026. 10. 30., 일부개정]
  - source_url: https://www.law.go.kr/LSW/ordinInfoP.do?ordinSeq=2181437&chrClsCd=010202&gubun=ELIS
  - raw: `raw/daegu.ord.building__2026-11-12__ordinInfoR-2181437.html`
  - 검증: source_access=confirmed, version_header=confirmed, current_version=needs_review, text_match=not_checked

### `kr.decree.building` — 건축법 시행령

- 목적: 버전 표제 + 별표 1(용도별 건축물의 종류) 제공 형태 확인
- 이름 주소: https://www.law.go.kr/%EB%B2%95%EB%A0%B9/%EA%B1%B4%EC%B6%95%EB%B2%95%EC%8B%9C%ED%96%89%EB%A0%B9
- 연혁 목록: https://www.law.go.kr/LSW/lsHstListR.do?lsId=002118 (raw: `raw/kr.decree.building__history.html`)
- 연혁 목록상 기준일(2026-11-06) 뒤에 시행되는 버전:
  - `290833` 1. 건축법 시행령 [시행 2027. 4. 7.] [대통령령 제36746호, 2026. 10. 6., 일부개정]
- **시행본** `kr.decree.building__2026-10-06.json`
  - 표제: [시행 2026. 10. 6.] [대통령령 제36746호, 2026. 10. 6., 일부개정]
  - source_url: https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=290833&chrClsCd=010202&urlMode=lsInfoP&efYd=20261006&ancYnChk=0
  - raw: `raw/kr.decree.building__2026-10-06__lsInfoR-290833.html`, `raw/kr.decree.building__2026-10-06__annex-18516653.html`
  - 검증: source_access=confirmed, version_header=confirmed, current_version=confirmed, text_match=not_checked
  - 별표 확인: 별표 1: 「[별표 1] 용도별 건축물의 종류(제3조의5 관련)」 bylSeq=18516653, 제공 형태 attachment_only:HWP/PDF/이미지/문서뷰어(이미지 변환), 첨부 flDownload.do?gubun=&flSeq=170002559&bylClsCd=110201, flDownload.do?gubun=&flSeq=170002561&bylClsCd=110201, flDownload.do?gubun=&flExt=hwpx&flSeq=170002559&bylClsCd=110201
  - **누락** 별표 1: 별표가 첨부파일로만 제공됨 (attachment_only:HWP/PDF/이미지/문서뷰어(이미지 변환)) — 전사하지 않음. 제목: [별표 1] 용도별 건축물의 종류(제3조의5 관련)

## 법정동코드 확인

출처: 행정표준코드관리시스템(행정안전부) 법정동코드목록조회 `https://www.code.go.kr/stdcode/regCodeL.do` (폐지구분 '현존', 법정동코드 검색)

| 법정동코드 | 조회된 법정동명 | 기대 | 접두 | 결과 | raw |
| --- | --- | --- | --- | --- | --- |
| 2700000000 | 대구광역시 | 대구광역시 | `27` | 일치 | `raw/code.go.kr__regCodeL__2700000000.html` |
| 2726000000 | 대구광역시 수성구 | 대구광역시 수성구 | `27260` | 일치 | `raw/code.go.kr__regCodeL__2726000000.html` |

## 아직 없는 것

- 모든 별표 본문(시행령 별표 4·5·6·7·9·17, 주차장법 시행령 별표 1, 대구 주차장 조례 별표 2, 건축법 시행령 별표 1): 첨부파일 전용 → 미전사. DRF Open API(`<별표내용>`) 또는 공식 HWP/PDF 를 별도 검수 절차로 처리해야 함.
- 주차장법 시행규칙 제3조(주차단위구획 너비·길이), 제6조(노외주차장 차로 너비), 제11조⑤1(소규모 부설주차장 차로 너비)의 **표 이미지 내용**: text 에는 `[[IMAGE flSeq=…]]` 자리 표시만 있다. 위 대체텍스트는 참고용이며 원본 이미지와 대조한 값이 아니다.
- 대구광역시 도시계획 조례는 용도지역별 건축제한을 별표가 아니라 조문(제31조~제50조의2)으로 둔다. 1·2·3종일반주거(제33~35조), 준주거(제36조), 일반상업(제38조), 자연녹지(제46조)만 수집했다.
- 수성구 주차장 조례·대구 건축 조례·건축법 시행령: 버전 표제만(조문 없음).
- `v_text_match`: 다른 경로(DRF XML 등)와 대조하지 않았으므로 전부 `not_checked`.
- 부칙·조문별 시행일: 수집하지 않음. 시행 예정 개정이 있는 문서는 위 '기준일 뒤에 시행되는 버전' 참고.
