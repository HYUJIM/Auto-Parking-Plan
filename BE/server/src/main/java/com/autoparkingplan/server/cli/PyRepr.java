package com.autoparkingplan.server.cli;

import java.util.List;

/** 파이썬 repr 표기 (CLI 출력이 파이썬판과 같도록: 종단 검증이 bootstrap 출력 줄을 읽는다). */
final class PyRepr {
    private PyRepr() {
    }

    /** 파이썬 {@code repr(str)}: 작은따옴표, 문자열에 ' 만 있고 " 가 없으면 큰따옴표. */
    static String str(String s) {
        char q = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder sb = new StringBuilder().append(q);
        s.codePoints().forEach(cp -> {
            if (cp == q || cp == '\\') {
                sb.append('\\').appendCodePoint(cp);
            } else if (cp == '\n') {
                sb.append("\\n");
            } else if (cp == '\r') {
                sb.append("\\r");
            } else if (cp == '\t') {
                sb.append("\\t");
            } else if (cp < 0x20 || cp == 0x7F || (cp >= 0x80 && cp < 0xA0)) {
                sb.append(String.format("\\x%02x", cp));
            } else if (!isPrintable(cp)) {
                sb.append(cp > 0xFFFF ? String.format("\\U%08x", cp) : String.format("\\u%04x", cp));
            } else {
                sb.appendCodePoint(cp);
            }
        });
        return sb.append(q).toString();
    }

    private static boolean isPrintable(int cp) {
        if (cp == ' ') {
            return true;
        }
        int t = Character.getType(cp);
        return !(Character.isWhitespace(cp) || Character.isSpaceChar(cp) || t == Character.CONTROL || t == Character.FORMAT
                || t == Character.SURROGATE || t == Character.PRIVATE_USE || t == Character.UNASSIGNED || t == Character.LINE_SEPARATOR
                || t == Character.PARAGRAPH_SEPARATOR);
    }

    /** 파이썬 {@code repr(list[str])}. */
    static String strList(List<String> xs) {
        return "[" + String.join(", ", xs.stream().map(PyRepr::str).toList()) + "]";
    }
}
