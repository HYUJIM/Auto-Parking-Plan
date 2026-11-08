"""RulesLogicParityTest 기대값 생성 (파이썬판 app.review.rules_logic 실행 결과).
실행: cd BE && .venv/bin/python ../BE-spring/server/src/test/resources/parity/gen_rules_logic.py"""
import json
from pathlib import Path
from app.review.rules_logic import eval_formula, fmt_num, base_use_name, use_group, norm

V = {"gross_floor_area": 1234.5, "facility_area": 300.0, "households": 12.0, "site_area": 500.0}
EXPRS = ["ceil(gross_floor_area / 150)", "floor(site_area * 0.6)", "min(gross_floor_area, site_area)", "max(1, 2, 3)",
         "-(gross_floor_area - 100) / 2", "- -3", "households * 1.5 + 0.5", "ceil(facility_area / 100) * 2", "1e3 / 7",
         "(gross_floor_area / 150", "gross_floor_area ** 2", "gross_floor_area // 2", "gross_floor_area % 2", "+3", "abs(-1)",
         "gross_floor_area.__class__", "__import__('os')", "min()", "min(5)", "ceil(1, 2)", "ceil(1e308 * 1e308)",
         "min(1/0, 5)", "gross_floor_area / 0", "0x10 + 1", "1_000 * 2", "07", "1 # 주석", "True + 1", "None", "'a'",
         "lambda: 1", "min(1, x=2)", "max(*[1,2])", "unknown_var", "ceil(1.5) + floor(-1.5)", "min(3, 1, 2,)", ".5 + 1.", "2e-3 * 1000"]
out = {"formula": []}
for e in EXPRS:
    try:
        out["formula"].append({"expr": e, "value": eval_formula(e, V)})
    except ValueError:
        out["formula"].append({"expr": e, "error": True})
out["formula"].append({"expr": "x" * 501, "error": True})
nums = [None, 0.0, 1.0, 1234567.0, 1234.5, 1234.567, 0.125, 2.675, -1500.25, 0.005, 1e-7]
out["fmt_num"] = [{"x": x, "s": fmt_num(x)} for x in nums]
uses = ["자동차관련시설 중 동호아목에 해당하는 것과 주차장 및 세차장", "공동주택(아파트를 제외한다)", "제1종 근린생활시설",
        "업무시설로서 오피스텔", "단독주택［다가구주택］", "의료시설"]
out["use"] = [{"u": u, "base": base_use_name(u), "group": use_group(u)} for u in uses]
out["norm"] = [{"v": v, "n": norm(v)} for v in ["제2종 일반　주거지역", " a\tb\n", "", 0, 1.5, 12]]
Path(__file__).with_name("rules_logic.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
print("ok", len(out["formula"]))
