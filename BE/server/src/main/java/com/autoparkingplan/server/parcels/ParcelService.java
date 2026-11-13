package com.autoparkingplan.server.parcels;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import jakarta.persistence.EntityManager;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.Times;
import com.autoparkingplan.db.entity.Jurisdiction;
import com.autoparkingplan.db.entity.Parcel;
import com.autoparkingplan.db.entity.ParcelBuilding;
import com.autoparkingplan.db.entity.ParcelDesignation;
import com.autoparkingplan.external.AddressCandidate;
import com.autoparkingplan.external.BuildingHubApi;
import com.autoparkingplan.external.BuildingInfo;
import com.autoparkingplan.external.CadastralParcel;
import com.autoparkingplan.external.JusoApi;
import com.autoparkingplan.external.CircuitBreaker;
import com.autoparkingplan.external.JusoQueryError;
import com.autoparkingplan.external.LandCharacteristics;
import com.autoparkingplan.external.LandUseDesignation;
import com.autoparkingplan.external.Pnu;
import com.autoparkingplan.external.UpstreamError;
import com.autoparkingplan.external.VWorldApi;
import com.autoparkingplan.server.parcels.ParcelExceptions.AddressNotFound;
import com.autoparkingplan.server.parcels.ParcelExceptions.ParcelNotFound;
import com.autoparkingplan.server.parcels.ParcelExceptions.UpstreamUnavailable;
import com.autoparkingplan.server.security.Sanitize;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 필지 확정 (설계서 7장 1단계): 주소 → PNU → 지적·토지이용계획·토지특성 조회 → parcel 캐시(30일) — 파이썬 app.parcels.service.
 *
 * <ul>
 *   <li>실패한 항목은 null 로 두고 data_notices 에 PARCEL_PARTIAL / DESIGNATION_UNAVAILABLE 을 넣는다.</li>
 *   <li>외부 API 장애 시 만료된 캐시라도 있으면 그것을 돌려준다 (조회 중단 방지, 안내 문구 포함).</li>
 *   <li>API 키는 서버에서만 쓴다.</li>
 * </ul>
 * 모든 메서드는 호출한 쪽의 트랜잭션(EntityManager) 안에서 쓴다.
 */
public class ParcelService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ParcelService.class);
    static final Pattern PNU_RE = Pattern.compile("^\\d{19}$");
    private static final Pattern SIGUNGU_SUFFIX = Pattern.compile("(시|군|구)$");

    public final JusoApi juso;
    public final VWorldApi vworld;
    public final BuildingHubApi building;
    private final int cacheDays;
    private final int coordLookupLimit;
    private final boolean exampleData;
    private final int refreshMinIntervalS;
    private final ExecutorService pool;

    /**
     * @param exampleData         공식 API 가 아닌 곳(모의 서버 등)에서 받은 필지 → 예시 데이터로 표시
     * @param refreshMinIntervalS 공개 API 의 ?refresh=true 는 외부 공공 API(일일 할당량)를 다시 부른다. 같은 필지는 이 간격 안에 다시
     *                            부르지 않는다 → 반복 호출로 인증키 할당량을 소진시켜 서비스 전체를 멈추게 하는 공격 차단
     */
    public ParcelService(JusoApi juso, VWorldApi vworld, BuildingHubApi building, int cacheDays, int coordLookupLimit, boolean exampleData,
            int refreshMinIntervalS) {
        this.juso = juso;
        this.vworld = vworld;
        this.building = building;
        this.cacheDays = cacheDays;
        this.coordLookupLimit = coordLookupLimit;
        this.exampleData = exampleData;
        this.refreshMinIntervalS = refreshMinIntervalS;
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "parcel-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }

    /** '대구광역시 수성구 범어동 1-1' → ('대구광역시', '수성구'). 세종시처럼 시·군·구가 없으면 (시도, null). */
    public static String[] splitRegion(String address) {
        if (address == null || address.isEmpty()) {
            return new String[] {null, null};
        }
        List<String> toks = Py.split(address);
        String sido = toks.isEmpty() ? null : toks.getFirst();
        String sigungu = toks.size() > 1 && SIGUNGU_SUFFIX.matcher(toks.get(1)).find() ? toks.get(1) : null;
        return new String[] {sido, sigungu};
    }

    /** 경계의 대표점 [경도, 위도] (무게중심이 경계 안에 있으면 무게중심, 아니면 내부점). 소수 7자리. */
    static ArrayNode centroid(JsonNode geom) {
        if (geom == null || geom.isNull() || geom.isEmpty()) {
            return null;
        }
        try {
            Geometry g = Sanitize.toGeometry(Json.text(geom, "type"), geom.get("coordinates"));
            Point c = g.getCentroid();
            Point p = g.contains(c) ? c : g.getInteriorPoint();
            ArrayNode a = Json.array();
            a.add(Py.round(p.getX(), 7));
            a.add(Py.round(p.getY(), 7));
            return a;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ── 주소 검색 ──────────────────────────────────────────
    public List<ObjectNode> search(EntityManager em, String q) {
        if (juso == null) {
            throw new UpstreamUnavailable("주소 검색 API 키가 설정되지 않았습니다");
        }
        List<AddressCandidate> cands;
        try {
            cands = juso.search(q, 1, 10);
        } catch (JusoQueryError e) {
            // 검색어 오류(E0005 등)는 장애가 아니라 '주소를 찾을 수 없음'
            if (e.code() != null && !e.retriable()) {
                throw new AddressNotFound(e.userMessage());
            }
            throw new UpstreamUnavailable(e.message());
        } catch (CircuitBreaker.OpenError e) { // 연속 실패로 차단 중: 언제 다시 시도하면 되는지 알려 준다
            throw new UpstreamUnavailable(e.message(), e.retryAfterSeconds());
        } catch (UpstreamError e) {
            throw new UpstreamUnavailable(e.message());
        }
        if (cands.isEmpty()) {
            throw new AddressNotFound(q);
        }
        Map<String, JsonNode> coords = new LinkedHashMap<>();
        List<String> toLookup = new ArrayList<>();
        for (AddressCandidate c : cands) {
            Parcel cached = em.find(Parcel.class, c.pnu());
            if (cached != null) {
                coords.put(c.pnu(), cached.getCentroid());
            } else if (toLookup.size() < coordLookupLimit && vworld != null) {
                toLookup.add(c.pnu());
            }
        }
        if (!toLookup.isEmpty()) {
            Map<String, Future<Optional<CadastralParcel>>> futs = new LinkedHashMap<>();
            for (String pnu : toLookup) {
                futs.put(pnu, pool.submit(() -> vworld.getParcel(pnu)));
            }
            for (Map.Entry<String, Future<Optional<CadastralParcel>>> f : futs.entrySet()) {
                try {
                    Optional<CadastralParcel> cp = f.getValue().get();
                    coords.put(f.getKey(), cp.isPresent() ? centroid(cp.get().geometry()) : null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    coords.put(f.getKey(), null);
                } catch (ExecutionException | RuntimeException e) { // 좌표는 선택 정보
                    coords.put(f.getKey(), null);
                }
            }
        }
        List<ObjectNode> out = new ArrayList<>();
        for (AddressCandidate c : cands) {
            Jurisdiction j = em.find(Jurisdiction.class, c.pnu().substring(0, 5));
            ObjectNode o = Json.object();
            o.put("pnu", c.pnu());
            o.put("road_address", c.roadAddress());
            o.put("jibun_address", c.jibunAddress());
            o.put("sido", c.sido());
            o.put("sigungu", c.sigungu());
            o.put("region_code", c.pnu().substring(0, 5));
            JsonNode xy = coords.get(c.pnu());
            if (xy != null && !xy.isNull() && !xy.isEmpty()) {
                ObjectNode loc = o.putObject("location");
                loc.set("lng", xy.get(0));
                loc.set("lat", xy.get(1));
            } else {
                o.putNull("location");
            }
            o.put("supported", j != null && Boolean.TRUE.equals(j.getIsSupported()));
            o.put("building_name", c.buildingName());
            out.add(o);
        }
        return out;
    }

    // ── 필지 조회 + 캐시 ───────────────────────────────────
    public Parcel getParcel(EntityManager em, String pnu, boolean refresh) {
        return getParcel(em, pnu, refresh, null);
    }

    public Parcel getParcel(EntityManager em, String pnu, boolean refresh, Map<String, String> addressHint) {
        if (pnu == null || !PNU_RE.matcher(pnu).find() || !Pnu.isValidPnu(pnu)) {
            throw new ParcelNotFound(pnu);
        }
        Parcel row = em.find(Parcel.class, pnu);
        Instant now = Times.now();
        if (refresh && row != null && Duration.between(row.getFetchedAt(), now).toMillis() / 1000.0 < refreshMinIntervalS) {
            refresh = false; // 방금 받은 정보 → 외부 API 를 다시 부르지 않고 저장된 정보를 쓴다
        }
        if (row != null && !refresh && row.getExpiresAt().isAfter(now)) {
            return row;
        }
        Fetched fetched;
        try {
            fetched = fetch(pnu);
        } catch (UpstreamUnavailable e) {
            if (row != null) {
                ObjectNode meta = row.getSourceMeta() != null && row.getSourceMeta().isObject() ? (ObjectNode) row.getSourceMeta().deepCopy() : Json.object();
                ObjectNode stale = meta.putObject("stale");
                stale.put("ok", false);
                stale.put("error", "외부 API 장애로 저장된 정보를 표시");
                stale.put("fetched_at", Times.isoformat(row.getFetchedAt()));
                row.setSourceMeta(meta);
                return row;
            }
            throw e;
        }
        return upsert(em, pnu, fetched, row, addressHint);
    }

    public Parcel getParcelAt(EntityManager em, double lng, double lat) {
        if (vworld == null) {
            throw new UpstreamUnavailable("VWorld API 키가 설정되지 않았습니다");
        }
        Optional<CadastralParcel> cp;
        try {
            cp = vworld.getParcelAt(lng, lat);
        } catch (UpstreamError e) {
            throw new UpstreamUnavailable(e.message());
        }
        if (cp.isEmpty()) {
            throw new ParcelNotFound(Py.floatRepr(lng) + "," + Py.floatRepr(lat));
        }
        return getParcel(em, cp.get().pnu(), false);
    }

    /** 외부 조회 결과 묶음. 각 항목은 값 또는 UpstreamError. */
    record Fetched(CadastralParcel cadastral, Object landUse, Object landCharacteristics, Object buildings, boolean hasBuildings) {
    }

    private Fetched fetch(String pnu) {
        if (vworld == null) {
            throw new UpstreamUnavailable("VWorld API 키가 설정되지 않았습니다");
        }
        Optional<CadastralParcel> cad;
        try {
            cad = vworld.getParcel(pnu);
        } catch (UpstreamError e) {
            throw new UpstreamUnavailable(e.message());
        }
        if (cad.isEmpty()) {
            throw new ParcelNotFound(pnu);
        }
        Future<List<LandUseDesignation>> lu = pool.submit(() -> vworld.getLandUse(pnu));
        Future<Optional<LandCharacteristics>> lc = pool.submit(() -> vworld.getLandCharacteristics(pnu));
        Future<List<BuildingInfo>> bl = building != null ? pool.submit(() -> building.getBuildings(pnu)) : null;
        Object luV = result("land_use", lu);
        Object lcV = result("land_characteristics", lc);
        if (lcV instanceof Optional<?> o) {
            lcV = o.orElse(null);
        }
        Object blV = bl != null ? result("buildings", bl) : null;
        return new Fetched(cad.get(), luV, lcV, blV, bl != null);
    }

    private static Object result(String k, Future<?> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new UpstreamError(k, "InterruptedException");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof UpstreamError ue) {
                return ue;
            }
            log.error("{} 조회 중 예외", k, c);
            return new UpstreamError(k, c.getClass().getSimpleName());
        }
    }

    private static Jurisdiction ensureJurisdiction(EntityManager em, String code, String name) {
        return com.autoparkingplan.server.legal.Ingest.ensureJurisdiction(em, code, name);
    }

    private Parcel upsert(EntityManager em, String pnu, Fetched f, Parcel row, Map<String, String> hint) {
        Instant now = Times.now();
        CadastralParcel cad = f.cadastral();
        Object lu = f.landUse();
        Object lc = f.landCharacteristics();
        Object bl = f.buildings();
        String address = firstNonEmpty(hint != null ? hint.get("jibun_address") : null, cad.address(), row != null ? row.getJibunAddress() : null, pnu);
        String road = firstNonEmpty(hint != null ? hint.get("road_address") : null, row != null ? row.getRoadAddress() : null);
        String[] region = splitRegion(address);
        String sido = region[0];
        String sigungu = region[1];
        ensureJurisdiction(em, pnu.substring(0, 2), sido);
        ensureJurisdiction(em, pnu.substring(0, 5), sido != null && !sido.isEmpty() && sigungu != null && !sigungu.isEmpty() ? sido + " " + sigungu : sido);
        ObjectNode meta = Json.object();
        ObjectNode cm = meta.putObject("cadastral");
        cm.put("source", cad.source());
        cm.put("fetched_at", Times.isoformat(cad.fetchedAt()));
        cm.put("ok", true);
        note(meta, "land_use", lu, now);
        note(meta, "land_characteristics", lc, now);
        JsonNode lcErrors = null;
        if (lc instanceof LandCharacteristics l && l.raw() != null) {
            JsonNode e = l.raw().get("_errors");
            if (e != null && !e.isNull() && !e.isEmpty()) {
                lcErrors = e;
            }
        }
        if (lcErrors != null) { // 토지대장/토지특성 중 하나만 실패한 부분 결과
            List<String> errs = new ArrayList<>();
            lcErrors.values().forEach(x -> errs.add(x.isString() ? x.stringValue() : x.toString()));
            ObjectNode m = (ObjectNode) meta.get("land_characteristics");
            m.put("ok", false);
            m.put("partial", true);
            m.put("error", Py.slice(String.join("; ", errs), 0, 300));
        }
        if (building != null) {
            note(meta, "buildings", bl, now);
        }

        if (row == null) {
            row = new Parcel();
            row.setPnu(pnu);
            row.setJurisdictionCode(pnu.substring(0, 5));
            row.setJibunAddress(address);
            row.setSourceMeta(Json.object());
            row.setFetchedAt(now);
            row.setExpiresAt(now);
            em.persist(row);
        }
        row.setJurisdictionCode(pnu.substring(0, 5));
        row.setJibunAddress(address);
        row.setRoadAddress(road);
        row.setGeom(cad.geometry());
        row.setCentroid(centroid(cad.geometry()));
        boolean lcOk = lc instanceof LandCharacteristics;
        if (lcOk) {
            LandCharacteristics l = (LandCharacteristics) lc;
            row.setLandAreaM2(l.landAreaM2());
            row.setLandCategory(l.landCategory());
            row.setRoadSide(l.roadSide());
        }
        Long price = lcOk && ((LandCharacteristics) lc).officialLandPrice() != null && ((LandCharacteristics) lc).officialLandPrice() != 0
                ? ((LandCharacteristics) lc).officialLandPrice() : cad.officialLandPrice();
        row.setOfficialLandPrice(price == null ? null : Math.toIntExact(price));
        if (lu instanceof List<?> designations) {
            row.getDesignations().clear();
            em.flush();
            for (Object o : designations) {
                LandUseDesignation d = (LandUseDesignation) o;
                ParcelDesignation pd = new ParcelDesignation();
                pd.setKind(d.kind());
                pd.setName(d.name());
                pd.setCode(d.code());
                pd.setConflict(d.conflict());
                pd.setBasisLaw(d.basisLaw());
                pd.setFetchedAt(d.fetchedAt().toInstant());
                row.addDesignation(pd);
            }
            row.setDesignationsOk(true);
        } else if (!Boolean.TRUE.equals(row.getDesignationsOk())) {
            row.setDesignationsOk(false); // 조회 실패 + 이전 값 없음 → designations = null
        } else {
            ((ObjectNode) meta.get("land_use")).put("kept_previous", true);
        }
        if (building != null && bl instanceof List<?> buildings) {
            row.getBuildings().clear();
            for (Object o : buildings) {
                BuildingInfo b = (BuildingInfo) o;
                ParcelBuilding pb = new ParcelBuilding();
                pb.setMainUse(b.mainUse());
                pb.setGrossFloorArea(b.grossFloorArea());
                pb.setBuildingArea(b.buildingArea());
                pb.setApprovalDate(b.approvalDate() != null && !b.approvalDate().isEmpty() ? LocalDate.parse(b.approvalDate()) : null);
                pb.setRaw(b.raw());
                pb.setFetchedAt(b.fetchedAt().toInstant());
                row.addBuilding(pb);
            }
        }
        JsonNode cadRaw = cad.raw();
        boolean rawExample = cadRaw != null && cadRaw.has("is_example") && truthy(cadRaw.get("is_example"));
        row.setIsExample(exampleData || rawExample);
        row.setSourceMeta(meta);
        row.setFetchedAt(now);
        boolean partial = lu instanceof UpstreamError || lc instanceof UpstreamError || bl instanceof UpstreamError || lcErrors != null;
        // 일부 실패한 결과는 오래 캐시하지 않는다 (1시간 뒤 다시 시도)
        row.setExpiresAt(now.plus(partial ? Duration.ofHours(1) : Duration.ofDays(cacheDays)));
        em.flush();
        return row;
    }

    private static boolean truthy(JsonNode v) {
        if (v == null || v.isNull()) {
            return false;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isNumber()) {
            return v.doubleValue() != 0.0;
        }
        if (v.isString()) {
            return !v.stringValue().isEmpty();
        }
        return !v.isEmpty();
    }

    private static String firstNonEmpty(String... xs) {
        for (String x : xs) {
            if (x != null && !x.isEmpty()) {
                return x;
            }
        }
        return null;
    }

    private static void note(ObjectNode meta, String k, Object v, Instant now) {
        ObjectNode m = Json.object();
        if (v instanceof UpstreamError e) {
            m.put("source", e.service());
            m.put("ok", false);
            m.put("error", e.message());
            m.put("fetched_at", Times.isoformat(now));
        } else if (v != null && !(v instanceof List<?> l && l.isEmpty())) {
            String src;
            java.time.OffsetDateTime fa;
            if (v instanceof List<?> l) {
                Object first = l.getFirst();
                src = first instanceof LandUseDesignation d ? d.source() : ((BuildingInfo) first).source();
                fa = first instanceof LandUseDesignation d ? d.fetchedAt() : ((BuildingInfo) first).fetchedAt();
            } else {
                LandCharacteristics c = (LandCharacteristics) v;
                src = c.source();
                fa = c.fetchedAt();
            }
            m.put("source", src);
            m.put("fetched_at", Times.isoformat(fa));
            m.put("ok", true);
        } else if (v instanceof List<?>) {
            // 파이썬: 빈 목록은 'v is not None' 이므로 getattr(v, "source", k) → k, fetched_at → now
            m.put("source", k);
            m.put("fetched_at", Times.isoformat(now));
            m.put("ok", true);
        } else {
            m.put("source", k);
            m.put("ok", true);
            m.put("empty", true);
            m.put("fetched_at", Times.isoformat(now));
        }
        meta.set(k, m);
    }

    // ── 검토 엔진 입력 ─────────────────────────────────────
    public ParcelSnapshot snapshot(EntityManager em, Parcel row) {
        String[] region = splitRegion(row.getJibunAddress());
        Jurisdiction j = em.find(Jurisdiction.class, row.getJurisdictionCode());
        JsonNode meta = row.getSourceMeta() == null ? Json.object() : row.getSourceMeta();
        List<ObjectNode> notices = new ArrayList<>();
        boolean failed = false;
        for (Map.Entry<String, JsonNode> e : meta.properties()) {
            JsonNode v = e.getValue();
            if (v.isObject() && v.has("ok") && v.get("ok").isBoolean() && !v.get("ok").booleanValue() && !e.getKey().equals("stale")) {
                failed = true;
            }
        }
        if (failed || row.getLandAreaM2() == null) {
            notices.add(notice("PARCEL_PARTIAL", "해당 토지의 일부 정보를 불러오지 못했습니다. 누락 항목은 '정보 확인 필요'로 표시합니다."));
        }
        if (meta.has("stale")) {
            notices.add(notice("PARCEL_PARTIAL", "공공데이터 조회가 지연되어 저장된 정보(조회 " + Py.slice(meta.get("stale").get("fetched_at").stringValue(), 0, 10)
                    + ")를 표시합니다."));
        }
        if (!Boolean.TRUE.equals(row.getDesignationsOk())) {
            notices.add(notice("DESIGNATION_UNAVAILABLE", "토지이용계획(용도지역·지구·구역) 정보를 불러오지 못했습니다. 정보 확인 필요."));
        }
        Map<String, List<String>> fieldMap = new LinkedHashMap<>();
        fieldMap.put("cadastral", List.of("geometry"));
        fieldMap.put("land_use", List.of("designations"));
        fieldMap.put("land_characteristics", List.of("land_area_m2", "land_category"));
        fieldMap.put("buildings", List.of("buildings"));
        List<ObjectNode> sources = new ArrayList<>();
        for (Map.Entry<String, List<String>> fm : fieldMap.entrySet()) {
            JsonNode v = meta.get(fm.getKey());
            if (v != null && v.isObject() && truthy(v.get("ok"))) {
                for (String fld : fm.getValue()) {
                    ObjectNode s = Json.object();
                    s.put("field", fld);
                    s.set("source", v.has("source") ? v.get("source") : Json.nodes().stringNode(fm.getKey()));
                    s.set("fetched_at", v.get("fetched_at"));
                    sources.add(s);
                }
            }
        }
        List<ParcelSnapshot.Designation> designations = null;
        if (Boolean.TRUE.equals(row.getDesignationsOk())) {
            designations = new ArrayList<>();
            for (ParcelDesignation d : row.getDesignations()) {
                designations.add(new ParcelSnapshot.Designation(d.getKind(), d.getName(), d.getConflict() == null || d.getConflict().isEmpty()
                        ? "unknown" : d.getConflict(), d.getCode()));
            }
        }
        List<ObjectNode> buildings = new ArrayList<>();
        for (ParcelBuilding b : row.getBuildings()) {
            ObjectNode o = Json.object();
            o.put("main_use", b.getMainUse());
            o.put("gross_floor_area", b.getGrossFloorArea());
            o.put("approval_date", b.getApprovalDate() != null ? b.getApprovalDate().toString() : null);
            buildings.add(o);
        }
        String sido = region[0] != null && !region[0].isEmpty() ? region[0] : (j != null ? j.getName() : "");
        return new ParcelSnapshot(row.getPnu(), row.getRoadAddress(), row.getJibunAddress(), row.getJurisdictionCode(), sido, region[1],
                j != null && Boolean.TRUE.equals(j.getIsSupported()), row.getLandAreaM2(), row.getLandCategory(), designations, row.getGeom(),
                row.getCentroid(), row.getRoadSide(), row.getOfficialLandPrice() == null ? null : row.getOfficialLandPrice().longValue(), sources,
                notices, buildings, Boolean.TRUE.equals(row.getIsExample()));
    }

    static ObjectNode notice(String code, String message) {
        ObjectNode n = Json.object();
        n.put("code", code);
        n.put("message", message);
        return n;
    }

    /** GET /v1/parcels/{pnu} 응답: 대지면적, 지목, 용도지역·지구·구역, GeoJSON 경계, 항목별 출처·조회시각. */
    public static ObjectNode publicView(ParcelSnapshot snap) {
        ObjectNode o = Json.object();
        o.put("pnu", snap.pnu());
        o.put("road_address", snap.roadAddress());
        o.put("jibun_address", snap.jibunAddress());
        ObjectNode j = o.putObject("jurisdiction");
        j.put("code", snap.jurisdictionCode());
        j.put("sido", snap.sido());
        j.put("sigungu", snap.sigungu());
        j.put("supported", snap.supported());
        o.put("land_area_m2", snap.landAreaM2());
        Double a = snap.landAreaM2();
        o.put("land_area_pyeong", a != null && a != 0.0 ? Py.round(a * 0.3025, 2) : null);
        o.put("land_category", snap.landCategory());
        o.put("official_land_price", snap.officialLandPrice());
        o.put("road_side", snap.roadSide());
        if (snap.designations() == null) {
            o.putNull("designations");
        } else {
            ArrayNode ds = o.putArray("designations");
            snap.designations().forEach(d -> ds.add(d.toJson(true)));
        }
        ObjectNode loc = o.putObject("location");
        loc.set("centroid", snap.centroid() == null ? Json.nodes().nullNode() : snap.centroid());
        loc.set("geometry", snap.geometry() == null ? Json.nodes().nullNode() : snap.geometry());
        ArrayNode b = o.putArray("buildings");
        snap.buildings().forEach(b::add);
        ArrayNode s = o.putArray("sources");
        snap.sources().forEach(s::add);
        ArrayNode n = o.putArray("data_notices");
        snap.notices().forEach(n::add);
        o.put("is_example", snap.isExample());
        return o;
    }
}
