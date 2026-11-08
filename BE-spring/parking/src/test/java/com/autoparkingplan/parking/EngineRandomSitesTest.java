package com.autoparkingplan.parking;

import static com.autoparkingplan.parking.ParkingTestSupport.assertLayoutValid;
import static com.autoparkingplan.parking.ParkingTestSupport.box;
import static com.autoparkingplan.parking.ParkingTestSupport.localPointLnglat;
import static com.autoparkingplan.parking.ParkingTestSupport.localToGeojson;
import static com.autoparkingplan.parking.ParkingTestSupport.makeDims;
import static com.autoparkingplan.parking.ParkingTestSupport.rotate;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.locationtech.jts.algorithm.ConvexHull;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;

import com.autoparkingplan.parking.geom.Shapely;

/** 고정 시드 무작위 대지(볼록·별모양·곡선 경계·홈 파인 사각형)에서 모든 불변식 확인. 파이썬 {@code tests/parking/test_engine_random_sites.py}. */
class EngineRandomSitesTest {
    static final ParkingDimensions DIMS = makeDims();

    static Geometry randomSite(PyRandom rng) {
        String kind = rng.choice(List.of("star", "convex", "curvy", "notched"));
        double radius = rng.uniform(15, 55);
        if ("notched".equals(kind)) {
            double w = rng.uniform(15, 80);
            double h = rng.uniform(15, 80);
            Polygon p = rotate(box(0, 0, w, h), rng.uniform(0, 180), 0, 0);
            return Shapely.difference(p, rotate(box(0, 0, w * 0.4, h * 0.4), rng.uniform(0, 180), 0, 0));
        }
        int n = "curvy".equals(kind) ? rng.randint(40, 70) : rng.randint(4, 12);
        double[] angs = new double[n];
        for (int i = 0; i < n; i++) {
            angs[i] = rng.uniform(0, 2 * Math.PI);
        }
        Arrays.sort(angs);
        double[][] pts = new double[n][];
        for (int i = 0; i < n; i++) {
            double a = angs[i];
            double r;
            if ("curvy".equals(kind)) {
                r = radius * (0.8 + 0.2 * StrictMath.sin(3 * a)); // 짧은 변이 많은 곡선 경계
            } else if ("star".equals(kind)) {
                r = radius * rng.uniform(0.55, 1.0);
            } else {
                r = radius;
            }
            pts[i] = new double[] {r * StrictMath.cos(a), r * StrictMath.sin(a)};
        }
        Polygon p = Shapely.polygon(pts);
        return "convex".equals(kind) ? new ConvexHull(p).getConvexHull() : Shapely.buffer(p, 0);
    }

    record Case(Geometry poly, SimulationParams params) {
    }

    static List<Case> cases(long seed, int n) {
        PyRandom rng = new PyRandom(seed);
        List<Case> out = new ArrayList<>();
        while (out.size() < n) {
            Geometry poly = randomSite(rng);
            if (poly.isEmpty() || !"Polygon".equals(poly.getGeometryType()) || poly.getArea() < 300) {
                continue;
            }
            SimulationParams kw = SimulationParams.defaults().withSetbackM(rng.choice(List.of(0.0, 0.5, 1.0)));
            if (rng.random() < 0.5) {
                Envelope b = poly.getEnvelopeInternal();
                double x = rng.uniform(b.getMinX() - 20, b.getMaxX() + 20);
                double y = rng.uniform(b.getMinY() - 20, b.getMaxY() + 20);
                kw = kw.withGatePoint(localPointLnglat(x, y));
            }
            if (rng.random() < 0.3) {
                kw = kw.withGateCount(2);
            }
            out.add(new Case(poly, kw));
        }
        return out;
    }

    static Stream<Arguments> randomCases() {
        List<Case> cs = cases(20261106L, 10);
        return IntStream.range(0, cs.size()).mapToObj(i -> Arguments.of(i, cs.get(i)));
    }

    /** test_random_site_invariants (parametrize idx, case × 10, seed 20261106) */
    @ParameterizedTest(name = "[{0}]")
    @MethodSource("randomCases")
    void randomSiteInvariants(int idx, Case c) {
        ParkingEngine.Detailed d = ParkingEngine.simulateDetailed(localToGeojson(c.poly()), DIMS, c.params());
        SimulationResult res = d.result();
        assertThat(res.siteAreaM2()).isNotNull().isPositive();
        if (c.poly().getArea() > 1500) {
            assertThat(res.alternatives()).as(res.constraintReasons().toString()).isNotEmpty();
        }
        for (int i = 0; i < res.alternatives().size(); i++) {
            assertLayoutValid(d.geoms().get(i), res.alternatives().get(i), DIMS, c.params());
        }
    }
}
