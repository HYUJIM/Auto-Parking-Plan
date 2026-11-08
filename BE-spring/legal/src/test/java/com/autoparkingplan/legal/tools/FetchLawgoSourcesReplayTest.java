package com.autoparkingplan.legal.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.http.HttpRequest;
import com.autoparkingplan.common.http.HttpResponse;
import com.autoparkingplan.common.http.MockTransport;
import com.autoparkingplan.common.http.Urls;
import com.autoparkingplan.legal.TestFiles;

import tools.jackson.databind.JsonNode;

/**
 * {@link FetchLawgoSources} 재현 시험 (자바판 추가): 모의 law.go.kr 이 BE/data/legal_sources/raw 의 실제 원문 페이지를 돌려주면
 * 스크립트가 만든 번들 JSON·raw 파일·MANIFEST.md 가 파이썬판이 만든 저장소 파일과 바이트 단위로 같다 (받은 시각·실행 시작 시각만 제외).
 *
 * <p>같은 모의 응답으로 파이썬 스크립트를 돌려도 저장소 파일이 그대로 나옴을 확인했다 (fetched_at·실행 시작 제외).
 * 이름 주소 응답(/법령/…)은 저장소에 없어서 기준일 시행본을 가리키는 iframe 으로 만든다.
 */
class FetchLawgoSourcesReplayTest {
    private static final Path DATA = TestFiles.DATA;
    private static final Path RAW = DATA.resolve("raw");

    private static HttpResponse raw(String name) throws IOException {
        Path p = RAW.resolve(name);
        if (!Files.isRegularFile(p)) {
            return HttpResponse.of(404, "text/html", new byte[0]);
        }
        return HttpResponse.of(200, "text/html;charset=UTF-8", Files.readAllBytes(p));
    }

    private static List<String> rawNames() throws IOException {
        try (Stream<Path> s = Files.list(RAW)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** 모의 law.go.kr / code.go.kr. */
    static MockTransport replayTransport() throws IOException {
        List<JsonNode> bundles = new ArrayList<>();
        try (Stream<Path> s = Files.list(DATA)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".json")).sorted().toList()) {
                bundles.add(Json.parse(TestFiles.readText(p)));
            }
        }
        Map<String, String> extToKey = new HashMap<>();
        Map<String, JsonNode> inForce = new HashMap<>();
        for (JsonNode b : bundles) {
            extToKey.put(b.get("document").get("external_id").stringValue(), b.get("document").get("key").stringValue());
            if (!Json.isNull(b.get("version").get("current_checked_on"))) {
                inForce.put(b.get("document").get("key").stringValue(), b);
            }
        }
        Map<String, FetchLawgoSources.Target> byName = new HashMap<>();
        for (FetchLawgoSources.Target t : FetchLawgoSources.TARGETS) {
            byName.put(t.name().replace(" ", ""), t);
        }
        List<String> names = rawNames();
        Pattern resolver = Pattern.compile("^/(법령|자치법규)/(.+)$");
        return MockTransport.handler(req -> {
            String path = req.uri().getPath();
            Map<String, List<String>> qs = req.method().equals("POST")
                    ? Urls.parseQuery(new String(req.body(), StandardCharsets.UTF_8)) : req.queryParams();
            Map<String, String> q = new HashMap<>();
            qs.forEach((k, v) -> q.put(k, v.getFirst()));
            Matcher m = resolver.matcher(path);
            if (m.matches()) {
                FetchLawgoSources.Target t = byName.get(m.group(2));
                JsonNode v = inForce.get(t.key()).get("version");
                String src = t.kind().equals("law")
                        ? "/LSW/lsInfoP.do?lsiSeq=" + v.get("external_version_id").stringValue() + "&efYd="
                        + v.get("effective_from").stringValue().replace("-", "") + "&ancYnChk=0"
                        : "/LSW/ordinInfoP.do?ordinSeq=" + v.get("external_version_id").stringValue();
                return HttpResponse.of(200, "text/html", ("<html><body><iframe src=\"" + src + "\"></iframe></body></html>")
                        .getBytes(StandardCharsets.UTF_8));
            }
            switch (path) {
                case "/LSW/lsInfoR.do", "/LSW/ordinInfoR.do" -> {
                    boolean law = q.containsKey("lsiSeq");
                    String suffix = "__" + (law ? "lsInfoR" : "ordinInfoR") + "-" + (law ? q.get("lsiSeq") : q.get("ordinSeq")) + ".html";
                    List<String> cands = names.stream().filter(n -> n.endsWith(suffix)).toList();
                    String ef = q.get("efYd");
                    List<String> pick = ef == null ? cands : cands.stream()
                            .filter(n -> n.contains("__" + ef.substring(0, 4) + "-" + ef.substring(4, 6) + "-" + ef.substring(6) + "__")).toList();
                    if (pick.isEmpty()) {
                        pick = cands;
                    }
                    return pick.isEmpty() ? HttpResponse.of(404, "text/html", new byte[0]) : raw(pick.getFirst());
                }
                case "/LSW/lsHstListR.do", "/LSW/ordinHstListR.do" -> {
                    String key = extToKey.get(q.containsKey("lsId") ? q.get("lsId") : q.get("ordinId"));
                    return key == null ? HttpResponse.of(404, "text/html", new byte[0]) : raw(key + "__history.html");
                }
                case "/LSW/lsBylContentsInfoR.do", "/LSW/ordinBylContentsInfoR.do" -> {
                    String suffix = "__annex-" + q.get("bylSeq") + ".html";
                    return names.stream().filter(n -> n.endsWith(suffix)).findFirst()
                            .map(n -> {
                                try {
                                    return raw(n);
                                } catch (IOException e) {
                                    throw new java.io.UncheckedIOException(e);
                                }
                            }).orElse(HttpResponse.of(404, "text/html", new byte[0]));
                }
                case "/stdcode/regCodeL.do" -> {
                    return raw("code.go.kr__regCodeL__" + q.get("regionCd") + ".html");
                }
                default -> {
                    return HttpResponse.of(404, "text/html", new byte[0]);
                }
            }
        });
    }

    private static String withoutFetchedAt(String json) {
        return json.replaceAll("(?m)^\\s*\"fetched_at\": .*\\n", "");
    }

    private static String withoutStarted(String manifest) {
        return manifest.replaceAll("실행 시작 \\S+", "실행 시작 X");
    }

    @Test
    void replayReproducesSavedBundlesRawPagesAndManifest(@TempDir Path out) throws IOException {
        assumeTrue(Files.isDirectory(RAW), "BE/data/legal_sources/raw 없음");
        MockTransport transport = replayTransport();
        ByteArrayOutputStream so = new ByteArrayOutputStream();
        ByteArrayOutputStream se = new ByteArrayOutputStream();
        int rc = FetchLawgoSources.run(new String[] {"--as-of", "2026-11-06"}, out, transport, 0.0,
                new PrintStream(so, true, StandardCharsets.UTF_8), new PrintStream(se, true, StandardCharsets.UTF_8));
        assertThat(se.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(rc).isZero();
        String stdout = consoleText(so);
        assertThat(stdout).contains("[kr.law.nkpa] 국토의 계획 및 이용에 관한 법률\n")
                .contains("   -> kr.decree.nkpa__2026-09-18.json: 3 items, missing 6\n")
                .contains("   -> daegu.ord.building__2026-11-12.json: 0 items, missing 0\n");

        List<String> expectedBundles;
        try (Stream<Path> s = Files.list(DATA)) {
            expectedBundles = s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).sorted().toList();
        }
        List<String> producedBundles;
        try (Stream<Path> s = Files.list(out)) {
            producedBundles = s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).sorted().toList();
        }
        assertThat(producedBundles).isEqualTo(expectedBundles);
        for (String name : expectedBundles) {
            String produced = TestFiles.readText(out.resolve(name));
            assertThat(withoutFetchedAt(produced)).as(name).isEqualTo(withoutFetchedAt(TestFiles.readText(DATA.resolve(name))));
            assertThat(produced).endsWith("}\n");
        }
        for (String name : rawNames()) {
            if (name.endsWith(".html")) {
                assertThat(TestFiles.read(out.resolve("raw").resolve(name))).as(name).isEqualTo(TestFiles.read(RAW.resolve(name)));
            }
        }
        assertThat(withoutStarted(TestFiles.readText(out.resolve("MANIFEST.md"))))
                .isEqualTo(withoutStarted(TestFiles.readText(DATA.resolve("MANIFEST.md"))));
    }

    /** --only 는 그 key 만, 실패한 대상은 MANIFEST 에 '수집 실패'와 파이썬 repr 모양의 오류로 남고 종료 코드 1. */
    @Test
    void onlyAndFailureReporting(@TempDir Path out) throws IOException {
        MockTransport transport = MockTransport.handler(req -> HttpResponse.of(200, "text/html", "<html></html>".getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream so = new ByteArrayOutputStream();
        ByteArrayOutputStream se = new ByteArrayOutputStream();
        int rc = FetchLawgoSources.run(new String[] {"--only", "kr.law.parking", "--skip-region", "--as-of=2026-11-06"}, out, transport,
                0.0, new PrintStream(so, true, StandardCharsets.UTF_8), new PrintStream(se, true, StandardCharsets.UTF_8));
        assertThat(rc).isEqualTo(1);
        assertThat(consoleText(so)).startsWith("[kr.law.parking] 주차장법\n");
        String manifest = TestFiles.readText(out.resolve("MANIFEST.md"));
        assertThat(manifest).contains("| P2 | `kr.law.parking` | 주차장법 | 수집 실패 | — | — | — | 0 | — |")
                .contains("- **수집 실패**: RuntimeError('이름 주소에서 일련번호를 찾지 못함: "
                        + "https://www.law.go.kr/%EB%B2%95%EB%A0%B9/%EC%A3%BC%EC%B0%A8%EC%9E%A5%EB%B2%95')")
                .doesNotContain("kr.law.nkpa");
        assertThat(transport.count()).isEqualTo(1);
    }

    @Test
    void argparseErrors() {
        ByteArrayOutputStream se = new ByteArrayOutputStream();
        int rc = FetchLawgoSources.run(new String[] {"--bogus"}, Path.of("unused"), null, 1.2, System.out,
                new PrintStream(se, true, StandardCharsets.UTF_8));
        assertThat(rc).isEqualTo(2);
        assertThat(se.toString(StandardCharsets.UTF_8)).contains("error: unrecognized arguments: --bogus");
    }

    /**
     * 콘솔 출력 (줄 끝을 \n 으로 맞춤). 도구는 파이썬 print 처럼 플랫폼 줄 끝(윈도우 \r\n)으로 출력하므로 비교 전에 정규화한다.
     * 파일 출력(번들·MANIFEST·보충 자료)은 항상 \n 이며 아래에서 바이트 그대로 비교한다.
     */
    private static String consoleText(ByteArrayOutputStream so) {
        return so.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
