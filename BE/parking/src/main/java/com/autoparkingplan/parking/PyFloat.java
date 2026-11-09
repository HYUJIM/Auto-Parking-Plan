package com.autoparkingplan.parking;

import java.util.List;

/**
 * 파이썬 float 연산 중 자바와 결과가 다른 것 (엔진 내부용).
 *
 * <ul>
 *   <li>{@code a // b}, {@code a % b}: CPython {@code float_divmod} 와 같은 식 (나머지 부호 = 제수 부호, 몫 보정)</li>
 *   <li>{@code sum(floats)}: CPython 3.12 의 Neumaier 보정 합</li>
 *   <li>{@code min(a, b)}, {@code max(a, b)}: 같으면 앞의 값 (-0.0/0.0 구분 유지)</li>
 * </ul>
 */
final class PyFloat {
    private PyFloat() {
    }

    /** 파이썬 {@code vx % wx} (float). */
    static double mod(double vx, double wx) {
        double mod = vx % wx; // C fmod
        if (mod != 0.0) {
            if ((wx < 0) != (mod < 0)) {
                mod += wx;
            }
        } else {
            mod = Math.copySign(0.0, wx);
        }
        return mod;
    }

    /** 파이썬 {@code vx // wx} (float). */
    static double floorDiv(double vx, double wx) {
        double mod = vx % wx;
        double div = (vx - mod) / wx;
        if (mod != 0.0) {
            if ((wx < 0) != (mod < 0)) {
                div -= 1.0;
            }
        }
        double floordiv;
        if (div != 0.0) {
            floordiv = Math.floor(div);
            if (div - floordiv > 0.5) {
                floordiv += 1.0;
            }
        } else {
            floordiv = Math.copySign(0.0, vx / wx);
        }
        return floordiv;
    }

    /** 파이썬 {@code int(a // b)} (0 이상의 몫). */
    static int floorDivInt(double vx, double wx) {
        return (int) floorDiv(vx, wx);
    }

    /** CPython 3.12 {@code sum(iterable_of_floats)} (시작값 int 0, Neumaier 보정). */
    static double sum(List<Double> xs) {
        if (xs.isEmpty()) {
            return 0.0; // int 0 (호출부에서 float() 로 감싸면 0.0)
        }
        double fResult = 0.0 + xs.get(0); // int 0 + float
        double c = 0.0;
        for (int i = 1; i < xs.size(); i++) {
            double x = xs.get(i);
            double t = fResult + x;
            if (Math.abs(fResult) >= Math.abs(x)) {
                c += (fResult - t) + x;
            } else {
                c += (x - t) + fResult;
            }
            fResult = t;
        }
        if (c != 0.0 && Double.isFinite(c)) {
            fResult += c;
        }
        return fResult;
    }

    /** 파이썬 {@code min(a, b)}: b 가 더 작을 때만 b. */
    static double min(double a, double b) {
        return b < a ? b : a;
    }

    /** 파이썬 {@code max(a, b)}: b 가 더 클 때만 b. */
    static double max(double a, double b) {
        return b > a ? b : a;
    }

    /** 파이썬 튜플 원소 비교 (float): -0.0 == 0.0. */
    static int compare(double a, double b) {
        return a < b ? -1 : (a > b ? 1 : 0);
    }

    /**
     * 파이썬 {@code math.hypot(x, y)} (CPython 3.12 {@code vector_norm}: 오차 없는 제곱·합 + 미분 보정).
     * C 라이브러리 hypot(numpy) 과는 끝자리가 다를 수 있다.
     */
    static double hypot(double x, double y) {
        double ax = Math.abs(x);
        double ay = Math.abs(y);
        boolean foundNan = Double.isNaN(ax) || Double.isNaN(ay);
        double max = 0.0;
        if (ax > max) {
            max = ax;
        }
        if (ay > max) {
            max = ay;
        }
        if (Double.isInfinite(ax) || Double.isInfinite(ay)) {
            return Double.POSITIVE_INFINITY;
        }
        if (foundNan) {
            return Double.NaN;
        }
        if (max == 0.0) {
            return max;
        }
        int maxE = Math.getExponent(max) + 1; // frexp: max = m * 2^maxE, 0.5 <= m < 1
        if (max < Double.MIN_NORMAL) {
            return Math.hypot(x, y); // 비정규 수: 이 엔진에서 나오지 않는다
        }
        double scale = Math.scalb(1.0, -maxE);
        double[] vec = {ax, ay};
        double csum = 1.0;
        double frac1 = 0.0;
        double frac2 = 0.0;
        for (double v : vec) {
            double xs = v * scale;
            double prHi = xs * xs;
            double prLo = Math.fma(xs, xs, -prHi);
            double smHi = csum + prHi;
            double z = smHi - csum;
            double smLo = prHi - z;
            csum = smHi;
            frac1 += prLo;
            frac2 += smLo;
        }
        double h = Math.sqrt(csum - 1.0 + (frac1 + frac2));
        double prHi = -h * h;
        double prLo = Math.fma(-h, h, -prHi);
        double smHi = csum + prHi;
        double z = smHi - csum;
        double smLo = prHi - z;
        csum = smHi;
        frac1 += prLo;
        frac2 += smLo;
        double xr = csum - 1.0 + (frac1 + frac2);
        h += xr / (2.0 * h);
        return h / scale;
    }
}
