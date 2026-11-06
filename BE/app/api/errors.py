"""오류 응답 (설계서 10장 표 / 기능정의서 14장 문구)."""
from __future__ import annotations

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

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
    "RATE_LIMITED": (429, "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."),
    "PAYLOAD_TOO_LARGE": (413, "요청 본문이 너무 큽니다."),
    "SERVICE_BUSY": (503, "검토 요청이 많아 잠시 처리할 수 없습니다. 잠시 후 다시 시도해주세요."),
    "INTERNAL_ERROR": (500, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),
}


class ApiError(Exception):
    def __init__(self, code: str, message: str | None = None, *, status: int | None = None, details=None,  # noqa: ANN001
                 headers: dict[str, str] | None = None):
        default_status, default_msg = MESSAGES.get(code, (400, code))
        super().__init__(message or default_msg)
        self.code = code
        self.status = status or default_status
        self.message = message or default_msg
        self.details = details
        self.headers = headers


def error_body(code: str, message: str, details=None) -> dict:  # noqa: ANN001
    body = {"error": {"code": code, "message": message}}
    if details is not None:
        body["error"]["details"] = details
    return body


async def api_error_handler(_: Request, exc: ApiError) -> JSONResponse:
    return JSONResponse(error_body(exc.code, exc.message, exc.details), status_code=exc.status, headers=exc.headers)


async def unhandled_error_handler(_: Request, exc: Exception) -> JSONResponse:
    """예상 못 한 오류: 내부 정보(예외 종류·메시지·스택)를 응답에 싣지 않는다. 자세한 내용은 서버 로그에만 남는다."""
    return JSONResponse(error_body("INTERNAL_ERROR", MESSAGES["INTERNAL_ERROR"][1]), status_code=500)


async def validation_error_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
    details = [{"loc": list(e.get("loc", [])), "msg": e.get("msg")} for e in exc.errors()]
    return JSONResponse(error_body("INVALID_REQUEST", MESSAGES["INVALID_REQUEST"][1], details), status_code=422)


_HTTP_ERRORS = {
    400: ("INVALID_REQUEST", MESSAGES["INVALID_REQUEST"][1]),
    401: ("UNAUTHORIZED", MESSAGES["UNAUTHORIZED"][1]),
    404: ("NOT_FOUND", "요청한 주소를 찾을 수 없습니다."),
    405: ("METHOD_NOT_ALLOWED", "허용되지 않은 요청 방식입니다."),
    413: ("PAYLOAD_TOO_LARGE", MESSAGES["PAYLOAD_TOO_LARGE"][1]),
}


async def http_error_handler(_: Request, exc: StarletteHTTPException) -> JSONResponse:
    """프레임워크가 내는 404·405·본문 해석 실패 등도 같은 오류 형식으로 (내부 메시지는 싣지 않는다)."""
    code, msg = _HTTP_ERRORS.get(exc.status_code, ("HTTP_ERROR", "요청을 처리할 수 없습니다."))
    return JSONResponse(error_body(code, msg), status_code=exc.status_code, headers=getattr(exc, "headers", None))
