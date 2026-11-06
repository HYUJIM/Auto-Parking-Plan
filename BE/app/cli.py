"""
운영 CLI (윈도우·맥 공통: `python -m app.cli <명령>`).

  init-db                       스키마 생성 (22개 테이블 + displayable_explanation 뷰)
  bootstrap [--approve-as 이름]  init-db + 관할 + 원문 적재 + 기록된 추출 결과 적재 (+ 검수자 승인)
  ingest-sources [--dir]        data/legal_sources/*.json 원문 번들 적재 + 위임 관계 연결
  load-extractions [--dir] [--approve-as 이름]   data/seed/extractions/*.json 적재 (국가 법령 → 조례 순)
  extract --document-key K      LLM 으로 규칙 추출 (LLM_PROVIDER + API 키 필요)
  pending                       검수 대기 규칙 목록
  approve RULE_ID --reviewer 이름 [--ack-warnings]
  serve [--host] [--port]       API 서버
  worker                        검토 작업 워커 (REVIEW_EXECUTOR=worker 일 때)
  refresh-sources               국가법령정보 Open API 로 원문 재수집 (매일, LAWGO_OC 필요)
"""
from __future__ import annotations

import argparse
import json
import logging
import sys
import time
import uuid
from pathlib import Path

from sqlalchemy import select

from app.config import PROJECT_ROOT, get_settings

log = logging.getLogger("app.cli")
DEFAULT_SOURCES = PROJECT_ROOT / "data" / "legal_sources"
DEFAULT_SEEDS = PROJECT_ROOT / "data" / "seed" / "extractions"
JURISDICTIONS = PROJECT_ROOT / "data" / "seed" / "jurisdictions.json"
LAYER_ORDER = {"L1_NATIONAL": 0, "L2_LAND_USE": 1, "L3_LOCAL": 2, "L4_SPECIAL_ZONE": 3}


def _db():
    from app.db.session import Database  # noqa: PLC0415

    s = get_settings()
    db = Database(s.database_url, echo=s.db_echo)
    db.create_all()
    return db


def cmd_init_db(_args) -> int:
    _db()
    print("스키마 생성 완료")
    return 0


def seed_jurisdictions(db) -> int:
    from app.db.models import Jurisdiction  # noqa: PLC0415

    rows = json.loads(JURISDICTIONS.read_text(encoding="utf-8"))
    with db.session() as s:
        for r in sorted(rows, key=lambda r: r["level"]):
            j = s.get(Jurisdiction, r["code"]) or Jurisdiction(code=r["code"])
            j.name, j.level, j.parent_code, j.is_supported = r["name"], r["level"], r.get("parent_code"), r["is_supported"]
            s.merge(j)
    return len(rows)


def ingest_sources(db, src_dir: Path) -> list[str]:
    from app.legal.ingest import ingest_bundle, link_delegations, load_bundle_file  # noqa: PLC0415
    from app.storage.blob import make_blob_store  # noqa: PLC0415

    blobs = make_blob_store(get_settings())
    files = sorted(Path(src_dir).glob("*.json"))
    bundles = [(f, load_bundle_file(f)) for f in files]
    # 상위 문서(법률) → 시행령 → 시행규칙 → 조례 순으로 적재 (parent_key 해석을 위해)
    order = {"law": 0, "decree": 1, "rule": 2, "ordinance": 3, "local_rule": 4, "notice": 5, "plan": 6}
    bundles.sort(key=lambda fb: (LAYER_ORDER[fb[1].document.layer], order[fb[1].document.source_type], fb[0].name))
    out = []
    with db.session() as s:
        for f, b in bundles:
            r = ingest_bundle(s, b, blobs, base_dir=Path(src_dir))
            out.append(f"{'신규' if r.created else '동일'} {b.document.key} {b.version.effective_from} 조문 {r.provisions}개 ({f.name})")
        # 화면에 텍스트로 없는 별표(PDF)·표 이미지 전사본을 같은 버전에 보충
        from app.legal.supplement import ingest_supplement, load_supplement_file  # noqa: PLC0415

        for f in sorted((Path(src_dir) / "supplements").glob("*.json")):
            r2 = ingest_supplement(s, load_supplement_file(f), blobs, base_dir=Path(src_dir))
            out.append(f"보충 {f.name}: 조문 {r2.added}개 추가, {r2.skipped}개 기존")
        n = link_delegations(s)
        out.append(f"위임 관계 {n}건 연결")
    return out


def load_extractions(db, seed_dir: Path, approve_as: str | None, ack_warnings: bool = False,
                     confirm_transcriptions: bool = False) -> list[str]:
    from app.db.models import SourceDocument  # noqa: PLC0415
    from app.extraction.seed import load_seed  # noqa: PLC0415
    from app.review.approval import ApprovalError, approve_rule  # noqa: PLC0415

    seeds = [(f, json.loads(f.read_text(encoding="utf-8"))) for f in sorted(Path(seed_dir).glob("*.json"))]
    with db.session() as s:
        layer = {d.key: d.layer for d in s.scalars(select(SourceDocument)).all()}
    seeds.sort(key=lambda fs: (LAYER_ORDER.get(layer.get(fs[1]["document_key"], "L3_LOCAL"), 9), fs[0].name))
    out = []
    for f, seed in seeds:
        with db.session() as s:
            o = load_seed(s, seed)
            msg = f"{f.name}: {o.status} 규칙 {len(o.rule_ids)}개"
            if o.errors:
                msg += f" 오류 {o.errors[:3]}"
            if o.warnings:
                msg += f" 경고 {len(o.warnings)}건"
            if approve_as and o.status == "ok":
                ok = 0
                for rid in o.rule_ids:
                    try:
                        approve_rule(s, rid, reviewer=approve_as, comment="bootstrap 일괄 승인(개발용)", approve_ledger_too=True,
                                     acknowledge_warnings=ack_warnings, confirm_transcription=confirm_transcriptions)
                        ok += 1
                    except ApprovalError as e:
                        msg += f" | 승인 보류 {rid}: {e}"
                msg += f" | 승인 {ok}개 (검수자: {approve_as})"
            out.append(msg)
    return out


def _guard_bulk_approval(args) -> None:
    if getattr(args, "approve_as", None) and get_settings().app_env == "prod":
        raise SystemExit("운영(APP_ENV=prod)에서는 일괄 승인할 수 없습니다. 관리자 API 로 규칙·원장을 하나씩 검수하세요.")


def cmd_bootstrap(args) -> int:
    _guard_bulk_approval(args)
    db = _db()
    print(f"관할 {seed_jurisdictions(db)}건")
    for line in ingest_sources(db, Path(args.sources)):
        print(line)
    for line in load_extractions(db, Path(args.seeds), args.approve_as, args.ack_warnings, args.confirm_transcriptions):
        print(line)
    return 0


def cmd_ingest(args) -> int:
    db = _db()
    seed_jurisdictions(db)
    for line in ingest_sources(db, Path(args.dir)):
        print(line)
    return 0


def cmd_load_extractions(args) -> int:
    _guard_bulk_approval(args)
    for line in load_extractions(_db(), Path(args.dir), args.approve_as, args.ack_warnings, args.confirm_transcriptions):
        print(line)
    return 0


def cmd_extract(args) -> int:
    from app.db.models import DocumentVersion, SourceDocument  # noqa: PLC0415
    from app.extraction.extractor import extract_version  # noqa: PLC0415
    from app.llm.factory import build_llms  # noqa: PLC0415

    llm = build_llms(get_settings()).extractor
    if llm is None:
        print("LLM_PROVIDER 와 API 키를 설정하세요", file=sys.stderr)
        return 2
    db = _db()
    with db.session() as s:
        doc = s.scalar(select(SourceDocument).where(SourceDocument.key == args.document_key))
        if doc is None:
            print(f"문서 없음: {args.document_key}", file=sys.stderr)
            return 2
        ver = max(s.scalars(select(DocumentVersion).where(DocumentVersion.document_id == doc.id)).all(),
                  key=lambda v: (v.effective_from is not None, v.effective_from))
        for o in extract_version(s, ver, llm, categories=args.categories):
            print(o.status, len(o.rule_ids), o.errors[:3], o.warnings[:3])
    return 0


def cmd_refresh_sources(_args) -> int:
    """매일 실행 (AWS EventBridge): 국가법령정보 Open API 로 원문을 다시 받아 해시가 바뀌면 새 버전 적재.

    새 버전의 규칙은 다시 추출·검수 대기가 되고, 시행일이 미래면 PENDING_AMENDMENT 로 안내된다.
    """
    from app.db.models import SourceDocument  # noqa: PLC0415
    from app.legal.ingest import ingest_bundle, link_delegations  # noqa: PLC0415
    from app.legal.lawgo_drf import LawGoDrfClient  # noqa: PLC0415
    from app.storage.blob import make_blob_store  # noqa: PLC0415

    settings = get_settings()
    if not settings.lawgo_oc:
        print("LAWGO_OC 가 없어 원문 재수집을 건너뜁니다 (open.law.go.kr 이용 신청 + 서버 IP 등록 필요)")
        return 0
    import tempfile  # noqa: PLC0415

    from app.llm.factory import build_llms  # noqa: PLC0415

    client = LawGoDrfClient(settings.lawgo_oc, base_url=settings.lawgo_base_url)
    blobs = make_blob_store(settings)
    extractor = build_llms(settings).extractor
    db = _db()
    new_versions, mismatched, failed = [], [], 0
    with db.session() as s, tempfile.TemporaryDirectory(prefix="lawgo-") as tmp:
        docs = s.scalars(select(SourceDocument).where(SourceDocument.external_system == "law.go.kr")).all()
        for doc in docs:
            target = "ordin" if doc.source_type in ("ordinance", "local_rule") else "law"
            try:
                # 받은 XML 을 그대로 보관·해시하기 위해 raw_dir 에 저장하고 base_dir 로 넘긴다
                bundle = client.get_document(target, id=doc.external_id, raw_dir=Path(tmp) / "raw")
            except Exception as e:  # noqa: BLE001  한 문서 실패가 전체를 막지 않는다
                failed += 1
                log.warning("재수집 실패 %s: %s", doc.key, e)
                continue
            bundle.document.key = doc.key
            res = ingest_bundle(s, bundle, blobs, base_dir=Path(tmp))
            if res.created:
                new_versions.append((doc, res.version_id))
                log.info("새 버전 적재: %s %s", doc.key, bundle.version.effective_from)
            if res.text_mismatch:
                mismatched.append(doc.key)
        link_delegations(s)
        # 새 버전은 다시 추출해 검수 대기로 둔다 (LLM 설정이 있을 때)
        if extractor is not None:
            from app.db.models import DocumentVersion  # noqa: PLC0415
            from app.extraction.extractor import extract_version  # noqa: PLC0415

            for doc, vid in new_versions:
                outs = extract_version(s, s.get(DocumentVersion, vid), extractor)
                log.info("재추출 %s: %s", doc.key, [o.status for o in outs])
    print(f"재수집: 문서 {len(docs)}개, 새 버전 {len(new_versions)}개, 본문 불일치 {len(mismatched)}개 {mismatched}, 실패 {failed}개")
    return 0 if failed == 0 else 1


def cmd_pending(_args) -> int:
    from app.review.approval import pending_rules  # noqa: PLC0415

    with _db().session() as s:
        for status in ("auto_checked", "draft"):
            for r in pending_rules(s, status):
                print(f"{r.id}  {status:12s} {r.category:20s} {r.rule_key:24s} {r.title}")
    return 0


def cmd_approve(args) -> int:
    from app.review.approval import approve_rule  # noqa: PLC0415

    with _db().session() as s:
        r = approve_rule(s, uuid.UUID(args.rule_id), reviewer=args.reviewer, comment=args.comment, acknowledge_warnings=args.ack_warnings,
                         confirm_transcription=args.confirm_transcription, approve_ledger_too=args.with_ledger)
        print(f"승인: {r.rule_key} ({r.id}) by {r.approved_by}")
    return 0


def cmd_serve(args) -> int:
    import uvicorn  # noqa: PLC0415

    # server_header=False: "server: uvicorn" 으로 서버 종류를 알리지 않는다.
    # proxy_headers=False: 이 명령은 앞단 프록시 없이 직접 띄우는 용도 → X-Forwarded-For 로 클라이언트 IP 를 바꾸지 않는다
    # (uvicorn 기본값은 127.0.0.1 에서 온 X-Forwarded-For 를 믿어, 같은 PC 에서 헤더만 바꿔 요청 제한·잠금을 피할 수 있었다).
    uvicorn.run("app.main:app", host=args.host, port=args.port, reload=False, log_level="info",
                server_header=False, proxy_headers=False)
    return 0


def cmd_worker(args) -> int:
    from app.container import build_container  # noqa: PLC0415

    settings = get_settings().model_copy(update={"review_executor": "worker"})
    c = build_container(settings)
    n = c.reviews.reclaim_stale()
    print(f"검토 워커 시작 (Ctrl+C 로 종료){f' — 중단됐던 요청 {n}건 재대기' if n else ''}")
    rid = None
    try:
        while True:
            rid = c.reviews.claim_next()
            if rid is None:
                time.sleep(args.poll)
                continue
            log.info("검토 실행 %s", rid)
            c.reviews.run_claimed(rid)
            rid = None
    except KeyboardInterrupt:  # 윈도우 Ctrl+C / Ctrl+Break 포함. 실행 중이던 요청은 대기열로 되돌린다
        if rid is not None:
            c.reviews.requeue(rid)
        print("워커 종료")
    finally:
        c.close()
    return 0


def main(argv: list[str] | None = None) -> int:
    from app.main import configure_logging  # noqa: PLC0415

    configure_logging(get_settings().log_level)
    p = argparse.ArgumentParser(prog="app-cli", description="APP 법규 서버 운영 도구")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("init-db").set_defaults(fn=cmd_init_db)
    b = sub.add_parser("bootstrap")
    b.add_argument("--sources", default=str(DEFAULT_SOURCES))
    b.add_argument("--seeds", default=str(DEFAULT_SEEDS))
    b.add_argument("--approve-as", default=None, help="[개발용] 검수자 이름으로 규칙·원장 일괄 승인. 생략하면 승인하지 않음 (운영 금지)")
    b.add_argument("--ack-warnings", action="store_true", help="[개발용] 단서·특례 경고가 있는 규칙도 승인")
    b.add_argument("--confirm-transcriptions", action="store_true", help="[개발용] 표 이미지 전사본을 대조한 것으로 기록")
    b.set_defaults(fn=cmd_bootstrap)
    i = sub.add_parser("ingest-sources")
    i.add_argument("--dir", default=str(DEFAULT_SOURCES))
    i.set_defaults(fn=cmd_ingest)
    le = sub.add_parser("load-extractions")
    le.add_argument("--dir", default=str(DEFAULT_SEEDS))
    le.add_argument("--approve-as", default=None)
    le.add_argument("--ack-warnings", action="store_true")
    le.add_argument("--confirm-transcriptions", action="store_true")
    le.set_defaults(fn=cmd_load_extractions)
    e = sub.add_parser("extract")
    e.add_argument("--document-key", required=True)
    e.add_argument("--categories", nargs="*")
    e.set_defaults(fn=cmd_extract)
    sub.add_parser("pending").set_defaults(fn=cmd_pending)
    sub.add_parser("refresh-sources").set_defaults(fn=cmd_refresh_sources)
    a = sub.add_parser("approve")
    a.add_argument("rule_id")
    a.add_argument("--reviewer", required=True)
    a.add_argument("--comment")
    a.add_argument("--ack-warnings", action="store_true")
    a.add_argument("--confirm-transcription", action="store_true", help="표 이미지 전사본을 원본 이미지와 대조했음")
    a.add_argument("--with-ledger", action="store_true", help="사실 원장도 확인·승인")
    a.set_defaults(fn=cmd_approve)
    sv = sub.add_parser("serve")
    sv.add_argument("--host", default="127.0.0.1")
    sv.add_argument("--port", type=int, default=8000)
    sv.set_defaults(fn=cmd_serve)
    w = sub.add_parser("worker")
    w.add_argument("--poll", type=float, default=1.0)
    w.set_defaults(fn=cmd_worker)
    args = p.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    raise SystemExit(main())
