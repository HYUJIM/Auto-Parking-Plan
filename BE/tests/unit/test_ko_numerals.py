"""법령의 천 단위 혼용 숫자 표기(1천500퍼센트) 회귀 테스트."""
from app.verification.ai_output import _numbers
from app.verification.ko_quantity import parse


def test_parse_korean_thousands_percent():
    qs = parse("7. 중심상업지역 : 200퍼센트 이상 1천500퍼센트 이하")
    vals = [(q.kind, q.value, q.comparator) for q in qs if q.kind == "QUANTITY"]
    assert ("QUANTITY", 200.0, "GE") in vals
    assert ("QUANTITY", 1500.0, "LE") in vals


def test_money_with_korean_multiplier_still_works():
    qs = parse("과태료 5천만원")
    assert any(q.dim == "money" and q.value == 5e7 for q in qs)


def test_validator_number_set_includes_korean_thousands():
    assert 1500.0 in _numbers("중심상업지역 : 200퍼센트 이상 1천500퍼센트 이하")


def test_count_unit_gae():
    qs = parse("출입구가 2개 이상인 경우 6.0미터")
    assert any(q.dim == "count" and q.value == 2.0 and q.comparator == "GE" for q in qs)
    assert not any(q.kind == "BARE" for q in qs)
    # '개월'은 여전히 기간으로 읽는다
    assert any(q.kind == "PERIOD" and q.value == 6.0 for q in parse("6개월 이내"))


def test_korean_thousands_spacing_variants_from_daegu_ordinance():
    # 대구광역시 도시계획 조례 제80조 원문 표기
    q = [x for x in parse("7. 중심상업지역 : 1천 300퍼센트 이하") if x.kind == "QUANTITY"]
    assert q[0].value == 1300.0 and q[0].comparator == "LE"
    q = [x for x in parse("8. 일반상업지역 : 1천 퍼센트 이하") if x.kind == "QUANTITY"]
    assert q[0].value == 1000.0 and q[0].comparator == "LE"
    q = [x for x in parse("바닥면적의 합계가 2천제곱미터 미만인 것") if x.kind == "QUANTITY"]
    assert q[0].value == 2000.0 and q[0].dim == "area" and q[0].comparator == "LT"
    assert {1300.0} <= _numbers("1천 300퍼센트 이하") and 1000.0 in _numbers("1천 퍼센트 이하")


def test_angle_unit_and_road_not_angle():
    assert any(q.dim == "angle" and q.value == 60.0 for q in parse("60도 대향주차 | 4.5미터 | 5.5미터"))
    assert not any(q.dim == "angle" for q in parse("너비 8도로"))  # '도로' 는 각도가 아님
