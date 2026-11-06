"""오류 응답 (설계서 10장 표 / 기능정의서 14장 문구)."""
from __future__ import annotations

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

MESSAGES = {
    "ADDRESS_NOT_FOUND": (404, "주소를 찾을 수 없습니다. 도로명 또는 지번주소를 다시 확인해주세요."),
    "PARCEL_NOT_FOUND": (404, "해당 필지를 찾을 수 없습니다. 지번을 다시 확인해주세요."),
    "UPSTREAM_UNAVAILABLE": (503, "공공데이터 조회가 지연되고 있습니다. 잠시 후 다시 시도해주세요."),
    "REVIEW_NOT_FOUND": (404, "검토 결과를 찾을 수 없습니다."),
    "REVIEW_NOT_READY": (409, "검토가 아직 끝나지 않았습니다."),
    "RULE_NOT_FOUND": (404, "규칙을 찾을 수 없습니다."),
    "SITE_GEOMETRY_MISSING": (422, "필지 경계 정보가 없어 배치를 계산할 수 없습니다."),
    "INVALID_REQUEST": (422, "요청 형식이 올바르지 않습니다."),
    "UNAUTHORIZED": (401, "관리자 인증이 필요합니다."),
    "ADMIN_DISABLED": (403, "관리자 API 가 비활성화되어 있습니다 (ADMIN_TOKEN 미설정)."),
    "CONFLICT": (409, "현재 상태에서는 처리할 수 없는 요청입니다."),
}


class ApiError(Exception):
    def __init__(self, code: str, message: str | None = None, *, status: int | None = None, details=None):  # noqa: ANN001
        default_status, default_msg = MESSAGES.get(code, (400, code))
        super().__init__(message or default_msg)
        self.code = code
        self.status = status or default_status
        self.message = message or default_msg
        self.details = details


def error_body(code: str, message: str, details=None) -> dict:  # noqa: ANN001
    body = {"error": {"code": code, "message": message}}
    if details is not None:
        body["error"]["details"] = details
    return body


async def api_error_handler(_: Request, exc: ApiError) -> JSONResponse:
    return JSONResponse(error_body(exc.code, exc.message, exc.details), status_code=exc.status)


async def validation_error_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
    details = [{"loc": list(e.get("loc", [])), "msg": e.get("msg")} for e in exc.errors()]
    return JSONResponse(error_body("INVALID_REQUEST", MESSAGES["INVALID_REQUEST"][1], details), status_code=422)
