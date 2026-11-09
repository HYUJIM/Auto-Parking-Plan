package com.autoparkingplan.legal;

/** DRF Open API 오류 (파이썬 {@code lawgo_drf.DrfError(RuntimeError)}). 메시지에 OC 인증값을 넣지 않는다. */
public class DrfError extends RuntimeException implements LegalPy.PyNamed {
    public DrfError(String message) {
        super(message);
    }

    public DrfError(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String pythonName() {
        return "DrfError";
    }
}
