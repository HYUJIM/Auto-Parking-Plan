package com.autoparkingplan.common.http;

import java.util.regex.Pattern;

/** 인증키를 로그·예외 메시지에서 가린다 (파이썬 app.external._http.redact_secrets). */
public final class Redact {
    // confmKey(도로명주소)·key(브이월드)·serviceKey(공공데이터포털)·apiKey·OC(국가법령정보 공동활용)
    private static final Pattern SECRET_PARAM = Pattern.compile("(?i)\\b((?:confm|service|api)?key|oc)=([^&\\s\"']+)");

    private Redact() {
    }

    /** 문자열에서 인증키 파라미터 값과 주어진 비밀값(4자 이상)을 '***' 로 가린다. */
    public static String secrets(String text, Iterable<String> secrets) {
        String out = SECRET_PARAM.matcher(text).replaceAll("$1=***");
        if (secrets != null) {
            for (String s : secrets) {
                if (s != null && s.length() >= 4) {
                    out = out.replace(s, "***");
                }
            }
        }
        return out;
    }

    public static String secrets(String text) {
        return secrets(text, null);
    }
}
