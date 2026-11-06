"""python -m mockserver [--host 127.0.0.1] [--port 8090] [--slow-delay 3.5] [--fixtures PATH]"""

from __future__ import annotations

import argparse

import uvicorn

from app.console import ensure_utf8_stdio
from mockserver.app import DEFAULT_SLOW_DELAY_S, create_app


def main(argv: list[str] | None = None) -> None:
    ensure_utf8_stdio()
    parser = argparse.ArgumentParser(prog="python -m mockserver", description="APP 외부 공공 API mock 서버")
    parser.add_argument("--host", default="127.0.0.1", help="바인드 주소 (기본 127.0.0.1)")
    parser.add_argument("--port", type=int, default=8090, help="포트 (기본 8090)")
    parser.add_argument(
        "--slow-delay",
        type=float,
        default=DEFAULT_SLOW_DELAY_S,
        help=f"slow 필지의 토지특성 응답 지연(초, 기본 {DEFAULT_SLOW_DELAY_S})",
    )
    parser.add_argument("--fixtures", default=None, help="예시 필지 JSON 경로 (기본 mockserver/fixtures/parcels.json)")
    args = parser.parse_args(argv)
    app = create_app(dataset_path=args.fixtures, slow_delay_s=args.slow_delay)
    print(f"APP 외부 API mock: http://{args.host}:{args.port}/  (예시 데이터, 인증키 'invalid' = 인증 오류)")
    uvicorn.run(app, host=args.host, port=args.port, log_level="info")


if __name__ == "__main__":
    main()
