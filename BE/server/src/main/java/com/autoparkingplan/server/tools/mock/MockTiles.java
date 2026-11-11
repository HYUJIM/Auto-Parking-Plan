package com.autoparkingplan.server.tools.mock;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * 모의 브이월드 배경지도 타일(WMTS) — 파이썬판 mock 서버에는 없던 자바판 추가분.
 *
 * <pre>
 *   GET /req/wmts/1.0.0/{key}/{Layer}/{z}/{y}/{x}.{ext}     Layer: Base(png) · Satellite(jpeg)
 * </pre>
 *
 * 실제 API 처럼 실패도 HTTP 200 + {@code application/xml} (OWS ExceptionReport) 로 돌려준다: 인증키 'invalid', 모르는 레이어,
 * 줌 6~19 밖, 타일 번호 범위 밖. 성공이면 256×256 PNG 한 장(연한 바탕 + 타일 경계선)을 준다 — 실제 지도가 아니라는 것이 한눈에 보이게.
 * 위성 레이어도 PNG 로 준다 (실제 API 는 JPEG. 서버는 원천의 Content-Type 을 그대로 전달하므로 화면에는 문제없다).
 */
final class MockTiles {
    static final String PATH_PREFIX = "/req/wmts/1.0.0/";
    static final String XML_TYPE = "application/xml;charset=UTF-8";
    private static final int SIZE = 256;
    private static final int MIN_ZOOM = 6;
    private static final int MAX_ZOOM = 19;

    private static final byte[] BASE_PNG = png(0xEDF1F4, 0xC7D0D9);
    private static final byte[] SATELLITE_PNG = png(0x4B5A50, 0x60705F);

    /** 모의 응답 (HTTP 상태는 언제나 200). */
    record Result(String contentType, byte[] body) {
    }

    private MockTiles() {
    }

    /**
     * @param rest {@link #PATH_PREFIX} 뒤의 경로 ({@code {key}/{Layer}/{z}/{y}/{x}.{ext}})
     * @return 경로 모양이 다르면 null (호출 쪽이 404)
     */
    static Result respond(String rest) {
        String[] p = rest.split("/", -1);
        if (p.length != 5) {
            return null;
        }
        String key = p[0];
        String layer = p[1];
        int dot = p[4].lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String ext = p[4].substring(dot + 1);
        if (key.isEmpty() || key.equals(MockServer.INVALID_KEY)) {
            return error("InvalidParameterValue", "key", "등록되지 않은 인증키입니다.");
        }
        byte[] png;
        if (layer.equals("Base") && ext.equals("png")) {
            png = BASE_PNG;
        } else if (layer.equals("Satellite") && ext.equals("jpeg")) {
            png = SATELLITE_PNG;
        } else {
            return error("InvalidParameterValue", "layer", "layer 파라미터의 값이 유효하지 않습니다.");
        }
        Integer z = number(p[2]);
        Integer y = number(p[3]);
        Integer x = number(p[4].substring(0, dot));
        if (z == null || z < MIN_ZOOM || z > MAX_ZOOM) {
            return error("InvalidParameterValue", "tilematrix",
                    "tilematrix 파라미터의 값이 유효한 범위를 넘었습니다. 유효한 파라미터 값의 범위 : " + MIN_ZOOM + " ~ " + MAX_ZOOM);
        }
        int n = 1 << z;
        if (x == null || y == null || x >= n || y >= n) {
            return error("FileNotFound", "", "서비스 제공영역이 아닙니다.");
        }
        return new Result("image/png", png);
    }

    private static Integer number(String s) {
        if (s.isEmpty() || s.length() > 7 || !s.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        return Integer.parseInt(s);
    }

    /** 실제 API 의 오류 본문 모양 (2026-11-12 실호출 관찰). */
    private static Result error(String code, String locator, String text) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<ExceptionReport xmlns=\"http://www.opengis.net/ows/1.1\"\n"
                + "\txmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                + "\txsi:schemaLocation=\"http://schemas.opengis.net/ows/1.1.0/owsExceptionReport.xsd\"\n"
                + "\tversion=\"1.1.0\" xml:lang=\"kor\">\n"
                + "\t<Exception exceptionCode=\"" + code + "\" locator=\"" + locator + "\">\n"
                + "\t\t<ExceptionText><![CDATA[" + text + "]]></ExceptionText>\n"
                + "\t</Exception>\n"
                + "</ExceptionReport>\n";
        return new Result(XML_TYPE, xml.getBytes(StandardCharsets.UTF_8));
    }

    /** 256×256, 2색 팔레트(1비트) PNG: 바탕색 + 위·왼쪽 가장자리 1px 선(타일 경계). JDK 의 압축·CRC 만 쓴다 (AWT 없이). */
    static byte[] png(int background, int line) {
        int rowBytes = SIZE / 8;
        byte[] raw = new byte[SIZE * (1 + rowBytes)];
        for (int row = 0; row < SIZE; row++) {
            int at = row * (1 + rowBytes); // 줄마다 필터 바이트 0(None) + 픽셀 비트
            if (row == 0) {
                java.util.Arrays.fill(raw, at + 1, at + 1 + rowBytes, (byte) 0xFF);
            } else {
                raw[at + 1] = (byte) 0x80;
            }
        }
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (!deflater.finished()) {
            idat.write(buf, 0, deflater.deflate(buf));
        }
        deflater.end();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        ihdr.writeBytes(int32(SIZE));
        ihdr.writeBytes(int32(SIZE));
        ihdr.writeBytes(new byte[] {1, 3, 0, 0, 0}); // 비트 깊이 1, 색 형식 3(팔레트), 압축·필터·인터레이스 0
        chunk(out, "IHDR", ihdr.toByteArray());
        chunk(out, "PLTE", new byte[] {(byte) (background >> 16), (byte) (background >> 8), (byte) background, (byte) (line >> 16),
                (byte) (line >> 8), (byte) line});
        chunk(out, "IDAT", idat.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.writeBytes(int32(data.length));
        out.writeBytes(t);
        out.writeBytes(data);
        out.writeBytes(int32((int) crc.getValue()));
    }

    private static byte[] int32(int v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }
}
