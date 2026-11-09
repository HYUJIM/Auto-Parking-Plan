package com.autoparkingplan.legal;

/** OC 검증 실패 (미등록 IP/도메인 등) — 파이썬 {@code lawgo_drf.DrfAuthError(DrfError)}. */
public class DrfAuthError extends DrfError {
    public DrfAuthError(String message) {
        super(message);
    }

    @Override
    public String pythonName() {
        return "DrfAuthError";
    }
}
