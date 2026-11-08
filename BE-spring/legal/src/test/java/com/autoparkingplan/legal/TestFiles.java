package com.autoparkingplan.legal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 시험 자원 읽기. 파이썬판 데이터(BE/data/legal_sources)는 모듈 폴더 기준 상대 경로로 찾는다. */
public final class TestFiles {
    private TestFiles() {
    }

    /** {@code BE/data/legal_sources} (BE-spring/legal 에서 ../../BE/data/legal_sources). 없을 수 있다. */
    public static final Path DATA = Path.of("../../BE/data/legal_sources");

    public static byte[] fixtureBytes(String name) {
        try (InputStream in = TestFiles.class.getResourceAsStream("/fixtures/lawgo/" + name)) {
            if (in == null) {
                throw new IllegalStateException("fixture 없음: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code (FIX / name).read_bytes().decode("utf-8")}. */
    public static String fixture(String name) {
        return new String(fixtureBytes(name), StandardCharsets.UTF_8);
    }

    public static String resourceText(String path) {
        try (InputStream in = TestFiles.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("자원 없음: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] read(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String readText(Path p) {
        return new String(read(p), StandardCharsets.UTF_8);
    }
}
