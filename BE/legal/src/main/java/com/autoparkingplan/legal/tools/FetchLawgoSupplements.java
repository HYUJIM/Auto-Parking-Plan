package com.autoparkingplan.legal.tools;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.PyJson;
import com.autoparkingplan.common.http.HttpRequest;
import com.autoparkingplan.common.http.HttpResponse;
import com.autoparkingplan.common.http.HttpTransport;
import com.autoparkingplan.common.http.JdkHttpTransport;
import com.autoparkingplan.legal.LawGoHtmlClient;
import com.autoparkingplan.legal.LegalPy;
import com.autoparkingplan.legal.RedirectFollower;
import com.autoparkingplan.legal.SupplementText;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * law.go.kr 화면에 텍스트로 없는 원문을 보충 자료로 만든다 (재실행 가능, 윈도우·맥 공통)
 * — 파이썬 {@code scripts/fetch_lawgo_supplements.py}.
 *
 * <ol>
 *   <li>시행령 별표(용도지역별 건축할 수 있는 건축물)의 공식 PDF 첨부 → 텍스트 층 추출(결정적) → pdf_annex</li>
 *   <li>주차장법 시행규칙 조문 안의 표 이미지 → 원본 이미지 보관 + data/legal_sources/transcriptions/ 의 전사본 → image_table</li>
 * </ol>
 *
 * <p>실행: {@code FetchLawgoSupplements [--sources-dir data/legal_sources]} (자바판은 저장 폴더를 인자로 받는다).<br>
 * 출력: data/legal_sources/supplements/*.json, data/legal_sources/raw/supp_*.{pdf,gif}.
 * JSON 은 파이썬 {@code json.dumps(…, ensure_ascii=False, indent=2)} (끝 줄바꿈 없음) 과 바이트 단위로 같다.
 */
public final class FetchLawgoSupplements {
    public static final String UA = "APP-legal-collector/0.1 (dev)";
    public static final String DL = "https://www.law.go.kr/LSW/flDownload.do";

    /** 국토의 계획 및 이용에 관한 법률 시행령 (대통령령 제36587호, 2026-09-18 시행) 별표 — PDF 첨부 flSeq (MANIFEST.md 기록). */
    public static final List<Map<String, String>> NKPA_ANNEXES = List.of(
            annex("별표 4", "annex4", "제1종일반주거지역안에서 건축할 수 있는 건축물", "168871291"),
            annex("별표 5", "annex5", "제2종일반주거지역안에서 건축할 수 있는 건축물", "168871301"),
            annex("별표 6", "annex6", "제3종일반주거지역안에서 건축할 수 있는 건축물", "168871309"),
            annex("별표 17", "annex17", "자연녹지지역 안에서 건축할 수 있는 건축물", "168871393"));

    private static Map<String, String> annex(String label, String path, String heading, String flSeq) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("path", path);
        m.put("heading", heading);
        m.put("flSeq", flSeq);
        return java.util.Collections.unmodifiableMap(m);
    }

    private final Path src;
    private final Path raw;
    private final Path outDir;
    private final RedirectFollower client;
    private final LawGoHtmlClient.Sleeper sleeper;
    private final Supplier<LocalDateTime> clock;
    private final PrintStream out;
    private final PrintStream err;

    private FetchLawgoSupplements(Path sourcesDir, HttpTransport transport, LawGoHtmlClient.Sleeper sleeper,
                                  Supplier<LocalDateTime> clock, PrintStream out, PrintStream err) {
        this.src = sourcesDir;
        this.raw = sourcesDir.resolve("raw");
        this.outDir = sourcesDir.resolve("supplements");
        this.client = new RedirectFollower(transport != null ? transport : new JdkHttpTransport(Duration.ofSeconds(30)));
        this.sleeper = sleeper;
        this.clock = clock;
        this.out = out;
        this.err = err;
    }

    private byte[] fetch(String url, Map<String, String> params) {
        RedirectFollower.Result res;
        try {
            URI uri = URI.create(url + (params.isEmpty() ? "" : "?" + LegalPy.urlencode(params)));
            res = client.send(HttpRequest.get(uri, Map.of("User-Agent", UA, "Accept", "*/*")), Duration.ofSeconds(30));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        HttpResponse r = res.response();
        if (r.status() < 200 || r.status() >= 300) {
            String loc = r.status() / 100 == 3 ? r.header("location") : null;
            throw new LawGoHtmlClient.HttpStatusError(r.status(), res.request().uri().toString(), loc);
        }
        try {
            sleeper.sleep(1.2); // law.go.kr 에 부담을 주지 않도록 순차·지연
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return r.body();
    }

    private static Map<String, String> params(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static boolean startsWith(byte[] data, String prefix) {
        byte[] p = prefix.getBytes(StandardCharsets.US_ASCII);
        if (data.length < p.length) {
            return false;
        }
        for (int i = 0; i < p.length; i++) {
            if (data[i] != p[i]) {
                return false;
            }
        }
        return true;
    }

    /** {@code time.strftime("%Y-%m-%dT%H:%M:%S+09:00")} — 파이썬판처럼 이 컴퓨터의 현지 시각에 +09:00 을 붙인다. */
    private String fetchedAt() {
        return clock.get().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + "+09:00";
    }

    private static int lineCount(String text) {
        return Py.splitlines(text).size();
    }

    private void write(Path p, byte[] data) {
        try {
            Files.write(p, data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private int main() {
        try {
            Files.createDirectories(raw);
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ArrayNode items = Json.array();
        for (Map<String, String> a : NKPA_ANNEXES) {
            byte[] data = fetch(DL, params("gubun", "", "flSeq", a.get("flSeq"), "bylClsCd", "110201"));
            if (!startsWith(data, "%PDF")) {
                err.println("[실패] " + a.get("label") + ": PDF 가 아님");
                continue;
            }
            String rawName = "supp_kr.decree.nkpa__2026-09-18__" + a.get("path") + "__flSeq" + a.get("flSeq") + ".pdf";
            write(raw.resolve(rawName), data);
            String text = SupplementText.normalizeAnnexText(SupplementText.pdfText(data));
            ObjectNode it = items.addObject();
            it.put("kind", "pdf_annex");
            it.put("label", a.get("label"));
            it.put("path", a.get("path"));
            it.put("heading", a.get("heading"));
            it.put("source_url", DL + "?gubun=&flSeq=" + a.get("flSeq") + "&bylClsCd=110201");
            it.put("raw_file", "raw/" + rawName);
            it.put("raw_sha256", Py.sha256Hex(data));
            it.put("text", text);
            out.println(a.get("label") + ": " + LegalPy.thousands(data.length) + " bytes, " + lineCount(text) + " 줄");
        }
        ObjectNode supp = Json.object();
        supp.put("format", SupplementText.SUPPLEMENT_FORMAT);
        supp.put("document_key", "kr.decree.nkpa");
        supp.put("effective_from", "2026-09-18");
        supp.put("external_version_id", "288857");
        supp.put("fetched_at", fetchedAt());
        supp.put("note", "별표는 law.go.kr 화면에 HTML 텍스트가 없어 공식 PDF 첨부의 텍스트 층을 추출했다(공백만 정규화).");
        supp.set("items", items);
        write(outDir.resolve("kr.decree.nkpa__2026-09-18__annexes.json"),
                PyJson.dumps(supp, false, false, 2).getBytes(StandardCharsets.UTF_8));

        JsonNode tr;
        try {
            tr = Json.parse(Files.readString(src.resolve("transcriptions").resolve("kr.rule.parking__2026-07-27.json"),
                    StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        items = Json.array();
        for (JsonNode t : tr.get("tables").values()) {
            String flSeq = t.get("flSeq").stringValue();
            byte[] data = fetch(DL, params("flSeq", flSeq));
            if (!startsWith(data, "GIF")) {
                err.println("[주의] flSeq=" + flSeq + ": GIF 가 아님");
            }
            String rawName = "supp_kr.rule.parking__2026-07-27__flSeq" + flSeq + ".gif";
            write(raw.resolve(rawName), data);
            String parentPath = t.get("parent_path").stringValue();
            ObjectNode it = items.addObject();
            it.put("kind", "image_table");
            it.put("parent_path", parentPath);
            it.put("path", parentPath + "/tbl" + flSeq);
            it.set("label", t.get("label").deepCopy());
            it.put("source_url", DL + "?flSeq=" + flSeq);
            it.put("raw_file", "raw/" + rawName);
            it.put("raw_sha256", Py.sha256Hex(data));
            it.set("transcription", t.get("transcription").deepCopy());
            it.set("rows", t.get("rows").deepCopy());
            out.println("표 flSeq=" + flSeq + ": " + LegalPy.thousands(data.length) + " bytes, 전사 " + (t.get("rows").size() - 1) + "행");
        }
        supp = Json.object();
        supp.put("format", SupplementText.SUPPLEMENT_FORMAT);
        supp.set("document_key", tr.get("document_key").deepCopy());
        supp.set("effective_from", tr.get("effective_from").deepCopy());
        supp.set("external_version_id", tr.get("external_version_id").deepCopy());
        supp.put("fetched_at", fetchedAt());
        supp.set("note", tr.get("note").deepCopy());
        supp.set("items", items);
        write(outDir.resolve("kr.rule.parking__2026-07-27__tables.json"),
                PyJson.dumps(supp, false, false, 2).getBytes(StandardCharsets.UTF_8));
        return 0;
    }

    /** 명령줄 실행 (저장 폴더 {@code --sources-dir}, 기본 ./data/legal_sources). */
    public static int run(String[] args) {
        ArgParser ap = new ArgParser("FetchLawgoSupplements",
                "law.go.kr 화면에 텍스트로 없는 원문을 보충 자료로 만든다 (재실행 가능, 윈도우·맥 공통).")
                .add("--sources-dir", 1, "SOURCES_DIR", "data/legal_sources 폴더 (자바판 추가)", null);
        Map<String, Object> a;
        try {
            a = ap.parse(args, System.out, System.err);
        } catch (ArgParser.Exit e) {
            return e.code;
        }
        Path dir = a.get("--sources-dir") != null ? Path.of((String) a.get("--sources-dir")) : Path.of("data", "legal_sources");
        return run(dir);
    }

    /** 저장 폴더(data/legal_sources)를 지정해 실행. */
    public static int run(Path sourcesDir) {
        return run(sourcesDir, null, s -> Thread.sleep(Duration.ofNanos((long) Math.ceil(s * 1e9))), LocalDateTime::now,
                System.out, System.err);
    }

    /**
     * 시험·재현용.
     *
     * @param transport null 이면 실제 전송
     * @param sleeper   요청 뒤 1.2초 쉬기 (시험에서는 바로 돌아오게)
     * @param clock     fetched_at 에 쓰는 현지 시각
     */
    public static int run(Path sourcesDir, HttpTransport transport, LawGoHtmlClient.Sleeper sleeper, Supplier<LocalDateTime> clock,
                          PrintStream out, PrintStream err) {
        return new FetchLawgoSupplements(sourcesDir, transport, sleeper, clock, out, err).main();
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }
}
