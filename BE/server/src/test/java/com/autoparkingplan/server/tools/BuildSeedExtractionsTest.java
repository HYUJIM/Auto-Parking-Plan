package com.autoparkingplan.server.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link BuildSeedExtractions} 재현 시험 (자바판 추가): 저장소의 원문 번들(data/legal_sources)로 자바판을 돌리면
 * 파이썬 {@code scripts/build_seed_extractions.py} 가 만든 data/seed/extractions/*.json 과 바이트 단위로 같은 파일,
 * 같은 콘솔 출력이 나온다.
 */
class BuildSeedExtractionsTest {
    /** BE/data (시험 작업 폴더는 server 모듈 폴더). */
    static final Path DATA = Path.of("..", "data");
    static final Path COMMITTED = DATA.resolve("seed").resolve("extractions");

    @Test
    void reproducesCommittedSeedExtractionsByteForByte(@TempDir Path tmp) throws IOException {
        // 임시 데이터 폴더: 입력(legal_sources)만 복사하고, 출력 폴더(seed/extractions)는 도구가 만든다
        copyTree(DATA.resolve("legal_sources"), tmp.resolve("legal_sources"));
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        int code = BuildSeedExtractions.run(new String[0], tmp, new PrintStream(console, true, StandardCharsets.UTF_8));

        assertThat(code).isZero();
        Path produced = tmp.resolve("seed").resolve("extractions");
        List<String> committedNames = jsonNames(COMMITTED);
        assertThat(committedNames).isNotEmpty();
        assertThat(jsonNames(produced)).containsExactlyElementsOf(committedNames);
        for (String name : committedNames) {
            long mismatch = Files.mismatch(produced.resolve(name), COMMITTED.resolve(name));
            assertThat(mismatch).as("%s: 첫 차이 위치(바이트) %s", name, mismatch == -1 ? "-" : context(produced.resolve(name),
                    COMMITTED.resolve(name), mismatch)).isEqualTo(-1L);
        }
        // 파이썬판 콘솔 출력과 같다
        assertThat(console.toString(StandardCharsets.UTF_8).lines()).containsExactly(
                "01_kr.decree.nkpa__ratios.json: 규칙 42개, 값 42개",
                "02_kr.decree.nkpa__uses.json: 규칙 4개, 값 105개",
                "03_kr.rule.parking__dimensions.json: 규칙 3개, 값 30개",
                "10_daegu.ord.urban_planning__ratios.json: 규칙 42개, 값 42개",
                "11_daegu.ord.urban_planning__uses.json: 규칙 4개, 값 61개");
    }

    private static List<String> jsonNames(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).sorted().toList();
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
    }

    /** 실패 메시지용: 차이 위치 앞뒤 일부 (자바판 / 파이썬판). */
    private static String context(Path a, Path b, long at) {
        try {
            return at + "\n  java  : " + around(Files.readAllBytes(a), at) + "\n  python: " + around(Files.readAllBytes(b), at);
        } catch (IOException e) {
            return at + " (" + e + ")";
        }
    }

    private static String around(byte[] data, long at) {
        int from = (int) Math.max(0, at - 80);
        int to = (int) Math.min(data.length, at + 80);
        return new String(data, from, to - from, StandardCharsets.UTF_8);
    }
}
