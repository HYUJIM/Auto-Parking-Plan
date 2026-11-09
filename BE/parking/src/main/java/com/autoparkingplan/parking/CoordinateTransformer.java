package com.autoparkingplan.parking;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;

/**
 * EPSG:4326(경위도) ↔ 횡메르카토르 투영 좌표(EPSG:5179 등) 변환기. pyproj {@code Transformer(always_xy=True)} 대응.
 *
 * <p>투영 정의(타원체·원점·축척·가산값)는 proj4j 의 EPSG 등록부에서 읽고, 계산식은 pyproj 가 쓰는 PROJ 9.8 의
 * {@code tmerc} 기본 알고리즘(Poder/Engsager "exact", {@code src/projections/tmerc.cpp} + {@code src/latitudes.cpp})을
 * 그대로 옮겼다. proj4j 의 {@code tmerc} 는 옛 근사식(Evenden/Snyder)이라 대구 부근에서도 0.1 µm, 투영 원점에서 멀면
 * mm 단위로 PROJ 와 달라 같은 대지라도 배치 탐색이 달라질 수 있다. 이 구현과 pyproj 의 차이는 수학 함수 마지막 자리 수준
 * (1e-9 m 미만)이다. PROJ 는 clang 의 부동소수 축약({@code -ffp-contract=on}: 한 식 안의 {@code a*b+c} 를 FMA 로)으로
 * 빌드되어 있으므로 같은 식은 {@link Math#fma} 로 계산한다 (clang 규칙: 왼쪽 곱을 우선 축약).
 *
 * <p>불변 객체라 여러 스레드가 함께 써도 안전하다. 삼각함수는 플랫폼과 무관하게 같은 값을 내는 {@link StrictMath}.
 */
public final class CoordinateTransformer {
    /** PROJ {@code DEG_TO_RAD}. */
    private static final double DEG_TO_RAD = .017453292519943296;
    private static final double HALFPI = Math.PI / 2;
    private static final double TWOPI = Math.PI * 2;
    private static final double PJ_EPS_LAT = 1e-12;
    private static final int ORDER = 6;

    private final boolean inverse;
    private final double a;
    private final double ra;
    private final double lam0;
    private final double x0;
    private final double y0;
    private final double qn;
    private final double zb;
    private final double[] cgb = new double[ORDER];
    private final double[] cbg = new double[ORDER];
    private final double[] utg = new double[ORDER];
    private final double[] gtu = new double[ORDER];
    /** 테스트용 고정값 (입력 좌표 → 기록된 출력). 보통 null. */
    private final java.util.Map<java.util.List<Double>, double[]> pinned;

    private CoordinateTransformer(boolean inverse, double a, double rf, double k0, double lat0Deg, double lon0Deg,
            double x0, double y0) {
        this.pinned = null;
        this.inverse = inverse;
        this.a = a;
        this.ra = 1. / a;
        // PROJ ell_set: f = 1/rf, es = 2f - f^2, e = sqrt(es), alpha = asin(e), n = tan(alpha/2)^2
        double f = 1 / rf;
        double es = 2 * f - f * f;
        double e = Math.sqrt(es);
        double alpha = StrictMath.asin(e);
        double n = StrictMath.pow(StrictMath.tan(alpha / 2), 2);
        // PROJ dmstor: 십진 도 → 라디안
        this.lam0 = lon0Deg * DEG_TO_RAD;
        double phi0 = lat0Deg * DEG_TO_RAD;
        this.x0 = x0;
        this.y0 = y0;
        auxlatCoeffs(n, CHI_TO_PHI, cgb);
        auxlatCoeffs(n, PHI_TO_CHI, cbg);
        this.qn = k0 * rectifyingRadius(n);
        auxlatCoeffs(n, MU_TO_CHI, utg);
        auxlatCoeffs(n, CHI_TO_MU, gtu);
        double z = auxlatConvert(phi0, cbg);
        this.zb = -qn * auxlatConvert(z, gtu);
    }

    private CoordinateTransformer(CoordinateTransformer base, java.util.Map<java.util.List<Double>, double[]> pinned) {
        this.pinned = pinned;
        this.inverse = base.inverse;
        this.a = base.a;
        this.ra = base.ra;
        this.lam0 = base.lam0;
        this.x0 = base.x0;
        this.y0 = base.y0;
        this.qn = base.qn;
        this.zb = base.zb;
        System.arraycopy(base.cgb, 0, cgb, 0, ORDER);
        System.arraycopy(base.cbg, 0, cbg, 0, ORDER);
        System.arraycopy(base.utg, 0, utg, 0, ORDER);
        System.arraycopy(base.gtu, 0, gtu, 0, ORDER);
    }

    /**
     * 대조 시험용: 주어진 입력 좌표는 기록된 값(예: pyproj 결과)을 그대로 돌려주고, 나머지는 계산하는 변환기.
     * 플랫폼 수학 라이브러리의 마지막 비트 차이를 배제하고 나머지 계산의 동등성을 확인할 때 쓴다.
     *
     * @param table [x, y] → [x', y']
     */
    CoordinateTransformer pinned(java.util.Map<java.util.List<Double>, double[]> table) {
        return new CoordinateTransformer(this, java.util.Map.copyOf(table));
    }

    /**
     * src → dst 변환기. 지원: EPSG:4326 ↔ 횡메르카토르(tmerc) 투영 CRS (proj4j EPSG 등록부에 있는 것).
     *
     * @throws IllegalArgumentException 지원하지 않는 조합
     */
    public static CoordinateTransformer create(String src, String dst) {
        boolean srcGeo = isGeographic(src);
        boolean dstGeo = isGeographic(dst);
        if (srcGeo == dstGeo) {
            throw new IllegalArgumentException("unsupported transformation: " + src + " -> " + dst);
        }
        String projected = srcGeo ? dst : src;
        CoordinateReferenceSystem crs = new CRSFactory().createFromName(projected);
        java.util.Map<String, String> prm = new java.util.HashMap<>();
        for (String token : crs.getParameters()) {
            String t = token.startsWith("+") ? token.substring(1) : token;
            int eq = t.indexOf('=');
            prm.put(eq < 0 ? t : t.substring(0, eq), eq < 0 ? "" : t.substring(eq + 1));
        }
        if (!"tmerc".equals(prm.get("proj"))) {
            throw new IllegalArgumentException("only Transverse Mercator projections are supported: " + projected);
        }
        if (prm.containsKey("units") && !"m".equals(prm.get("units"))) {
            throw new IllegalArgumentException("only metre units are supported: " + projected);
        }
        double a;
        double rf;
        String ellps = prm.getOrDefault("ellps", "");
        // PROJ ellps.cpp
        switch (ellps) {
            case "GRS80" -> {
                a = 6378137.0;
                rf = 298.257222101;
            }
            case "WGS84" -> {
                a = 6378137.0;
                rf = 298.257223563;
            }
            default -> {
                if (prm.containsKey("a") && prm.containsKey("rf")) {
                    a = Double.parseDouble(prm.get("a"));
                    rf = Double.parseDouble(prm.get("rf"));
                } else {
                    throw new IllegalArgumentException("unsupported ellipsoid for " + projected + ": " + ellps);
                }
            }
        }
        return new CoordinateTransformer(!srcGeo, a, rf,
                Double.parseDouble(prm.getOrDefault("k", prm.getOrDefault("k_0", "1"))),
                Double.parseDouble(prm.getOrDefault("lat_0", "0")),
                Double.parseDouble(prm.getOrDefault("lon_0", "0")),
                Double.parseDouble(prm.getOrDefault("x_0", "0")),
                Double.parseDouble(prm.getOrDefault("y_0", "0")));
    }

    private static boolean isGeographic(String code) {
        return "EPSG:4326".equalsIgnoreCase(code) || "WGS84".equalsIgnoreCase(code);
    }

    /** (x, y) 변환. 투영 영역 밖·잘못된 입력은 PROJ 처럼 무한대(inf). */
    public double[] transform(double x, double y) {
        double[] out = new double[2];
        transform(x, y, out);
        return out;
    }

    /** (x, y) 변환 결과를 out[0], out[1] 에 쓴다. */
    public void transform(double x, double y, double[] out) {
        if (pinned != null) {
            double[] hit = pinned.get(java.util.List.of(x, y));
            if (hit != null) {
                out[0] = hit[0];
                out[1] = hit[1];
                return;
            }
        }
        if (inverse) {
            inverse(x, y, out);
        } else {
            forward(x, y, out);
        }
    }

    // ── 정방향: 경위도(도) → 투영 좌표(m) ──────────────────────────────

    private void forward(double lng, double lat, double[] out) {
        // unitconvert xy_in=deg xy_out=rad
        double lam = lng * DEG_TO_RAD;
        double phi = lat * DEG_TO_RAD;
        if (lam == Double.POSITIVE_INFINITY || phi == Double.POSITIVE_INFINITY) {
            error(out);
            return;
        }
        // fwd_prepare
        double t = (phi < 0 ? -phi : phi) - HALFPI;
        if (t > PJ_EPS_LAT || lam > 10 || lam < -10) {
            error(out);
            return;
        }
        if (phi > HALFPI) {
            phi = HALFPI;
        }
        if (phi < -HALFPI) {
            phi = -HALFPI;
        }
        lam = adjlon(lam);
        lam = (lam - 0.0) - lam0;
        lam = adjlon(lam);
        // exact_e_fwd
        double cn = auxlatConvert(phi, cbg);
        double sinCn = StrictMath.sin(cn);
        double cosCn = StrictMath.cos(cn);
        double sinCe = StrictMath.sin(lam);
        double cosCe = StrictMath.cos(lam);
        double cosCnCosCe = cosCn * cosCe;
        cn = StrictMath.atan2(sinCn, cosCnCosCe);
        double invDenomTanCe = 1. / StrictMath.hypot(sinCn, cosCnCosCe);
        double tanCe = sinCe * cosCn * invDenomTanCe;
        double ce = asinh(tanCe);
        double twoInvDenomTanCe = 2 * invDenomTanCe;
        double twoInvDenomTanCeSquare = twoInvDenomTanCe * invDenomTanCe;
        double tmpR = cosCnCosCe * twoInvDenomTanCeSquare;
        double sinArgR = sinCn * tmpR;
        double cosArgR = Math.fma(cosCnCosCe, tmpR, -1);
        double sinhArgI = tanCe * twoInvDenomTanCe;
        double coshArgI = twoInvDenomTanCeSquare - 1;
        double[] ri = clenS(gtu, sinArgR, cosArgR, sinhArgI, coshArgI);
        cn += ri[0];
        ce += ri[1];
        if (!(Math.abs(ce) <= 2.623395162778)) {
            // NaN 도 여기로 (PROJ: HUGE_VAL)
            if (Double.isNaN(ce)) {
                out[0] = Double.NaN;
                out[1] = Double.NaN;
                return;
            }
            error(out);
            return;
        }
        double xs = qn * ce;
        double ys = Math.fma(qn, cn, zb);
        // fwd_finalize (PJ_IO_UNITS_CLASSIC): x *= a, then fr_meter * (x + x0)
        out[0] = 1.0 * (xs * a + x0);
        out[1] = 1.0 * (ys * a + y0);
    }

    // ── 역방향: 투영 좌표(m) → 경위도(도) ──────────────────────────────

    private void inverse(double x, double y, double[] out) {
        if (x == Double.POSITIVE_INFINITY || y == Double.POSITIVE_INFINITY) {
            error(out);
            return;
        }
        // inv_prepare (PJ_IO_UNITS_CLASSIC)
        double xs = (1.0 * x - x0) * ra;
        double ys = (1.0 * y - y0) * ra;
        // exact_e_inv
        double cn = (ys - zb) / qn;
        double ce = xs / qn;
        if (!(Math.abs(ce) <= 2.623395162778)) {
            if (Double.isNaN(ce)) {
                out[0] = Double.NaN;
                out[1] = Double.NaN;
                return;
            }
            error(out);
            return;
        }
        double sinArgR = StrictMath.sin(2 * cn);
        double cosArgR = StrictMath.cos(2 * cn);
        double exp2Ce = StrictMath.exp(2 * ce);
        double halfInvExp2Ce = 0.5 / exp2Ce;
        double sinhArgI = 0.5 * exp2Ce - halfInvExp2Ce;
        double coshArgI = 0.5 * exp2Ce + halfInvExp2Ce;
        double[] ri = clenS(utg, sinArgR, cosArgR, sinhArgI, coshArgI);
        cn += ri[0];
        ce += ri[1];
        double sinCn = StrictMath.sin(cn);
        double cosCn = StrictMath.cos(cn);
        double sinhCe = StrictMath.sinh(ce);
        ce = StrictMath.atan2(sinhCe, cosCn);
        double modulusCe = StrictMath.hypot(sinhCe, cosCn);
        double rr = StrictMath.hypot(sinCn, modulusCe);
        cn = StrictMath.atan2(sinCn, modulusCe);
        double phi = cn + clenshaw(sinCn / rr, modulusCe / rr, cgb);
        double lam = ce;
        // inv_finalize
        lam = lam + 0.0 + lam0;
        lam = adjlon(lam);
        // unitconvert xy_in=rad xy_out=deg (factor 1.0 / DEG_TO_RAD)
        double factor = 1.0 / DEG_TO_RAD;
        out[0] = lam * factor;
        out[1] = phi * factor;
    }

    private static void error(double[] out) {
        out[0] = Double.POSITIVE_INFINITY;
        out[1] = Double.POSITIVE_INFINITY;
    }

    /** PROJ {@code adjlon}. */
    private static double adjlon(double longitude) {
        if (Math.abs(longitude) < Math.PI + 1e-12) {
            return longitude;
        }
        longitude += Math.PI;
        longitude = Math.fma(-TWOPI, Math.floor(longitude / TWOPI), longitude);
        longitude -= Math.PI;
        return longitude;
    }

    /** C {@code asinh} (log1p 기반, 홀함수). */
    static double asinh(double x) {
        double ax = Math.abs(x);
        double r;
        if (ax < 0x1p-28) {
            r = ax;
        } else if (ax > 0x1p28) {
            r = StrictMath.log(ax) + 0.6931471805599453;
        } else {
            r = StrictMath.log1p(ax + ax * ax / (1 + StrictMath.sqrt(1 + ax * ax)));
        }
        return x < 0 ? -r : r;
    }

    /** PROJ tmerc {@code clenS} (복소 Clenshaw). [R, I] */
    private static double[] clenS(double[] a, double sinArgR, double cosArgR, double sinhArgI, double coshArgI) {
        double r = 2 * cosArgR * coshArgI;
        double i = -2 * sinArgR * sinhArgI;
        int p = a.length;
        double hi1 = 0;
        double hr1 = 0;
        double hi = 0;
        double hr = a[--p];
        double hr2;
        double hi2;
        while (p > 0) {
            hr2 = hr1;
            hi2 = hi1;
            hr1 = hr;
            hi1 = hi;
            hr = Math.fma(-i, hi1, Math.fma(r, hr1, -hr2)) + a[--p];
            hi = Math.fma(r, hi1, Math.fma(i, hr1, -hi2));
        }
        r = sinArgR * coshArgI;
        i = cosArgR * sinhArgI;
        return new double[] {Math.fma(r, hr, -(i * hi)), Math.fma(r, hi, i * hr)};
    }

    // ── PROJ latitudes.cpp ────────────────────────────────────────────

    private static double polyval(double x, double[] p, int off, int n) {
        double y = n < 0 ? 0 : p[off + n];
        for (; n > 0; ) {
            y = Math.fma(y, x, p[off + --n]);
        }
        return y;
    }

    private static double clenshaw(double szeta, double czeta, double[] f) {
        double u0 = 0;
        double u1 = 0;
        double x = 2 * (czeta - szeta) * (czeta + szeta);
        for (int k = f.length; k > 0; ) {
            double t = Math.fma(x, u0, -u1) + f[--k];
            u1 = u0;
            u0 = t;
        }
        return 2 * szeta * czeta * u0;
    }

    private static double auxlatConvert(double zeta, double[] f) {
        return zeta + clenshaw(StrictMath.sin(zeta), StrictMath.cos(zeta), f);
    }

    private static double rectifyingRadius(double n) {
        double[] coeffRad = {1, 1.0 / 4, 1.0 / 64, 1.0 / 256};
        return polyval(n * n, coeffRad, 0, 3) / (1 + n);
    }

    // C[out, in] 계수 (PROJ 9.8 latitudes.cpp, Maxima 생성). 각 행렬은 위삼각 6차.
    private static final double[] CHI_TO_PHI = { // C[phi,chi]
        2.0, -2.0 / 3.0, -2.0, 116.0 / 45.0, 26.0 / 45.0, -2854.0 / 675.0,
        7.0 / 3.0, -8.0 / 5.0, -227.0 / 45.0, 2704.0 / 315.0, 2323.0 / 945.0,
        56.0 / 15.0, -136.0 / 35.0, -1262.0 / 105.0, 73814.0 / 2835.0,
        4279.0 / 630.0, -332.0 / 35.0, -399572.0 / 14175.0, 4174.0 / 315.0,
        -144838.0 / 6237.0, 601676.0 / 22275.0,
    };
    private static final double[] CHI_TO_MU = { // C[mu,chi]
        1.0 / 2.0, -2.0 / 3.0, 5.0 / 16.0, 41.0 / 180.0, -127.0 / 288.0,
        7891.0 / 37800.0, 13.0 / 48.0, -3.0 / 5.0, 557.0 / 1440.0,
        281.0 / 630.0, -1983433.0 / 1935360.0, 61.0 / 240.0, -103.0 / 140.0,
        15061.0 / 26880.0, 167603.0 / 181440.0, 49561.0 / 161280.0,
        -179.0 / 168.0, 6601661.0 / 7257600.0, 34729.0 / 80640.0,
        -3418889.0 / 1995840.0, 212378941.0 / 319334400.0,
    };
    private static final double[] PHI_TO_CHI = { // C[chi,phi]
        -2.0, 2.0 / 3.0, 4.0 / 3.0, -82.0 / 45.0, 32.0 / 45.0, 4642.0 / 4725.0,
        5.0 / 3.0, -16.0 / 15.0, -13.0 / 9.0, 904.0 / 315.0, -1522.0 / 945.0,
        -26.0 / 15.0, 34.0 / 21.0, 8.0 / 5.0, -12686.0 / 2835.0, 1237.0 / 630.0,
        -12.0 / 5.0, -24832.0 / 14175.0, -734.0 / 315.0, 109598.0 / 31185.0,
        444337.0 / 155925.0,
    };
    private static final double[] MU_TO_CHI = { // C[chi,mu]
        -1.0 / 2.0, 2.0 / 3.0, -37.0 / 96.0, 1.0 / 360.0, 81.0 / 512.0,
        -96199.0 / 604800.0, -1.0 / 48.0, -1.0 / 15.0, 437.0 / 1440.0,
        -46.0 / 105.0, 1118711.0 / 3870720.0, -17.0 / 480.0, 37.0 / 840.0,
        209.0 / 4480.0, -5569.0 / 90720.0, -4397.0 / 161280.0, 11.0 / 504.0,
        830251.0 / 7257600.0, -4583.0 / 161280.0, 108847.0 / 3991680.0,
        -20648693.0 / 638668800.0,
    };

    /** PROJ {@code pj_auxlat_coeffs} (보조위도 쌍 중 하나라도 conformal 이므로 n 의 다항식 분기). */
    private static void auxlatCoeffs(double n, double[] coeffs, double[] f) {
        double d = n;
        int o = 0;
        for (int l = 0; l < ORDER; ++l) {
            int m = ORDER - l - 1;
            f[l] = d * polyval(n, coeffs, o, m);
            o += m + 1;
            d *= n;
        }
    }
}
