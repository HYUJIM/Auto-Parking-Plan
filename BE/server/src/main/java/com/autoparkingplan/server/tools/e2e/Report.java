package com.autoparkingplan.server.tools.e2e;

import java.util.ArrayList;
import java.util.List;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;

import tools.jackson.databind.node.ObjectNode;

/** 결과 기록 (파이썬판 {@code Report} dataclass): 점검 목록 {name, ok, detail} + 시나리오 목록. */
final class Report {
    final List<ObjectNode> checks = new ArrayList<>();
    final List<ObjectNode> scenarios = new ArrayList<>();

    boolean check(String name, boolean ok) {
        return check(name, ok, "");
    }

    /** 점검 하나를 기록하고 콘솔에 {@code [통과]/[실패]} 줄을 찍는다. 상세는 500자, 콘솔은 200자까지. */
    boolean check(String name, boolean ok, String detail) {
        String d = detail == null ? "" : detail;
        ObjectNode c = Json.object();
        c.put("name", name);
        c.put("ok", ok);
        c.put("detail", Py.slice(d, 0, 500));
        checks.add(c);
        System.out.println((ok ? "  [통과] " : "  [실패] ") + name + (!d.isEmpty() && !ok ? " — " + Py.slice(d, 0, 200) : ""));
        return ok;
    }

    List<ObjectNode> failed() {
        return checks.stream().filter(c -> !c.get("ok").booleanValue()).toList();
    }
}
