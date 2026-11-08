package com.autoparkingplan.legal.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.autoparkingplan.common.http.HttpResponse;
import com.autoparkingplan.common.http.MockTransport;
import com.autoparkingplan.legal.TestFiles;

/**
 * {@link FetchLawgoSupplements} 재현 시험 (자바판 추가): 모의 flDownload.do 가 저장소의 공식 PDF·GIF 원본을 돌려주면
 * supplements/*.json 이 파이썬판(pypdf)이 만든 파일과 바이트 단위로 같다 (fetched_at 만 제외).
 */
class FetchLawgoSupplementsReplayTest {
    private static final Path DATA = TestFiles.DATA;

    @Test
    void replayReproducesSavedSupplements(@TempDir Path out) throws IOException {
        Path raw = DATA.resolve("raw");
        assumeTrue(Files.isDirectory(DATA.resolve("supplements")) && Files.isDirectory(raw), "BE/data/legal_sources 없음");
        Files.createDirectories(out.resolve("transcriptions"));
        Files.copy(DATA.resolve("transcriptions").resolve("kr.rule.parking__2026-07-27.json"),
                out.resolve("transcriptions").resolve("kr.rule.parking__2026-07-27.json"));
        List<String> names;
        try (Stream<Path> s = Files.list(raw)) {
            names = s.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("supp_")).sorted().toList();
        }
        MockTransport transport = MockTransport.handler(req -> {
            assertThat(req.uri().getPath()).isEqualTo("/LSW/flDownload.do");
            assertThat(req.headers().get("User-Agent")).isEqualTo("APP-legal-collector/0.1 (dev)");
            String fl = req.queryParam("flSeq");
            String name = names.stream().filter(n -> n.contains("__flSeq" + fl + ".")).findFirst().orElseThrow();
            return HttpResponse.of(200, "application/octet-stream", Files.readAllBytes(raw.resolve(name)));
        });
        ByteArrayOutputStream so = new ByteArrayOutputStream();
        ByteArrayOutputStream se = new ByteArrayOutputStream();
        List<Double> sleeps = new java.util.ArrayList<>();
        int rc = FetchLawgoSupplements.run(out, transport, sleeps::add, () -> LocalDateTime.of(2026, 11, 6, 12, 31, 43),
                new PrintStream(so, true, StandardCharsets.UTF_8), new PrintStream(se, true, StandardCharsets.UTF_8));
        assertThat(rc).isZero();
        assertThat(se.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(sleeps).hasSize(names.size()).allMatch(s -> s == 1.2); // 요청마다 1.2초 쉼
        assertThat(transport.requests().getFirst().uri().getRawQuery()).isEqualTo("gubun=&flSeq=168871291&bylClsCd=110201");
        assertThat(so.toString(StandardCharsets.UTF_8)).contains(" bytes, ").contains(" 줄\n").contains("표 flSeq=24335428: ");
        for (String f : List.of("kr.decree.nkpa__2026-09-18__annexes.json", "kr.rule.parking__2026-07-27__tables.json")) {
            String produced = TestFiles.readText(out.resolve("supplements").resolve(f));
            String expected = TestFiles.readText(DATA.resolve("supplements").resolve(f));
            String norm = "\"fetched_at\": \"X\"";
            assertThat(produced.replaceAll("\"fetched_at\": \"[^\"]*\"", norm)).as(f)
                    .isEqualTo(expected.replaceAll("\"fetched_at\": \"[^\"]*\"", norm));
            assertThat(produced).contains("\"fetched_at\": \"2026-11-06T12:31:43+09:00\"").endsWith("}");
        }
        for (String n : names) {
            assertThat(TestFiles.read(out.resolve("raw").resolve(n))).as(n).isEqualTo(TestFiles.read(raw.resolve(n)));
        }
    }
}
