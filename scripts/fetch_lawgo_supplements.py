"""
law.go.kr 화면에 텍스트로 없는 원문을 보충 자료로 만든다 (재실행 가능, 윈도우·맥 공통).

  1) 시행령 별표(용도지역별 건축할 수 있는 건축물)의 공식 PDF 첨부 → 텍스트 층 추출(결정적) → pdf_annex
  2) 주차장법 시행규칙 조문 안의 표 이미지 → 원본 이미지 보관 + data/legal_sources/transcriptions/ 의 전사본 → image_table

실행:  python scripts/fetch_lawgo_supplements.py
출력:  data/legal_sources/supplements/*.json, data/legal_sources/raw/supp_*.{pdf,gif}
"""
from __future__ import annotations

import hashlib
import json
import sys
import time
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from app.console import ensure_utf8_stdio  # noqa: E402
from app.legal.supplement import SUPPLEMENT_FORMAT, normalize_annex_text, pdf_text  # noqa: E402

SRC = ROOT / "data" / "legal_sources"
RAW = SRC / "raw"
OUT = SRC / "supplements"
UA = "APP-legal-collector/0.1 (dev)"
DL = "https://www.law.go.kr/LSW/flDownload.do"

# 국토의 계획 및 이용에 관한 법률 시행령 (대통령령 제36587호, 2026-09-18 시행) 별표 — PDF 첨부 flSeq (MANIFEST.md 기록)
NKPA_ANNEXES = [
    {"label": "별표 4", "path": "annex4", "heading": "제1종일반주거지역안에서 건축할 수 있는 건축물", "flSeq": "168871291"},
    {"label": "별표 5", "path": "annex5", "heading": "제2종일반주거지역안에서 건축할 수 있는 건축물", "flSeq": "168871301"},
    {"label": "별표 6", "path": "annex6", "heading": "제3종일반주거지역안에서 건축할 수 있는 건축물", "flSeq": "168871309"},
    {"label": "별표 17", "path": "annex17", "heading": "자연녹지지역 안에서 건축할 수 있는 건축물", "flSeq": "168871393"},
]


def fetch(client: httpx.Client, url: str, params: dict) -> bytes:
    r = client.get(url, params=params, headers={"User-Agent": UA}, timeout=30)
    r.raise_for_status()
    time.sleep(1.2)  # law.go.kr 에 부담을 주지 않도록 순차·지연
    return r.content


def main() -> int:
    ensure_utf8_stdio()
    RAW.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    with httpx.Client(follow_redirects=True) as client:
        items = []
        for a in NKPA_ANNEXES:
            data = fetch(client, DL, {"gubun": "", "flSeq": a["flSeq"], "bylClsCd": "110201"})
            if not data.startswith(b"%PDF"):
                print(f"[실패] {a['label']}: PDF 가 아님", file=sys.stderr)
                continue
            raw_name = f"supp_kr.decree.nkpa__2026-09-18__{a['path']}__flSeq{a['flSeq']}.pdf"
            (RAW / raw_name).write_bytes(data)
            text = normalize_annex_text(pdf_text(data))
            items.append({"kind": "pdf_annex", "label": a["label"], "path": a["path"], "heading": a["heading"],
                          "source_url": f"{DL}?gubun=&flSeq={a['flSeq']}&bylClsCd=110201", "raw_file": f"raw/{raw_name}",
                          "raw_sha256": hashlib.sha256(data).hexdigest(), "text": text})
            print(f"{a['label']}: {len(data):,} bytes, {len(text.splitlines())} 줄")
        supp = {"format": SUPPLEMENT_FORMAT, "document_key": "kr.decree.nkpa", "effective_from": "2026-09-18",
                "external_version_id": "288857", "fetched_at": time.strftime("%Y-%m-%dT%H:%M:%S+09:00"),
                "note": "별표는 law.go.kr 화면에 HTML 텍스트가 없어 공식 PDF 첨부의 텍스트 층을 추출했다(공백만 정규화).", "items": items}
        (OUT / "kr.decree.nkpa__2026-09-18__annexes.json").write_text(json.dumps(supp, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")

        tr = json.loads((SRC / "transcriptions" / "kr.rule.parking__2026-07-27.json").read_text(encoding="utf-8"))
        items = []
        for t in tr["tables"]:
            data = fetch(client, DL, {"flSeq": t["flSeq"]})
            if not data.startswith(b"GIF"):
                print(f"[주의] flSeq={t['flSeq']}: GIF 가 아님", file=sys.stderr)
            raw_name = f"supp_kr.rule.parking__2026-07-27__flSeq{t['flSeq']}.gif"
            (RAW / raw_name).write_bytes(data)
            items.append({"kind": "image_table", "parent_path": t["parent_path"], "path": f"{t['parent_path']}/tbl{t['flSeq']}",
                          "label": t["label"], "source_url": f"{DL}?flSeq={t['flSeq']}", "raw_file": f"raw/{raw_name}",
                          "raw_sha256": hashlib.sha256(data).hexdigest(), "transcription": t["transcription"], "rows": t["rows"]})
            print(f"표 flSeq={t['flSeq']}: {len(data):,} bytes, 전사 {len(t['rows']) - 1}행")
        supp = {"format": SUPPLEMENT_FORMAT, "document_key": tr["document_key"], "effective_from": tr["effective_from"],
                "external_version_id": tr["external_version_id"], "fetched_at": time.strftime("%Y-%m-%dT%H:%M:%S+09:00"),
                "note": tr["note"], "items": items}
        (OUT / "kr.rule.parking__2026-07-27__tables.json").write_text(json.dumps(supp, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
