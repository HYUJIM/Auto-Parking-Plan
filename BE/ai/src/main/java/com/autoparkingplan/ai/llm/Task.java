package com.autoparkingplan.ai.llm;

/**
 * AI 를 쓰는 곳 (설계서 원칙 2: 두 곳 + 판정).
 * <ul>
 *   <li>rule_extraction : 오프라인 배치에서 조문 → 규칙 JSON (사람 검수 전 draft)</li>
 *   <li>explanation_writer : 확정된 review_item → 쉬운 설명 (사후 검증 통과해야 화면 노출)</li>
 *   <li>semantic_judge : 작성 모델과 분리된 판정 모델의 의미 검증 (실패만 추가 가능)</li>
 * </ul>
 * {@link #toString()} 은 파이썬판과 같은 이름 (오류 문구 {@code f"{task}: ..."} 에 쓰인다).
 */
public enum Task {
    RULE_EXTRACTION("rule_extraction"),
    EXPLANATION_WRITER("explanation_writer"),
    SEMANTIC_JUDGE("semantic_judge");

    private final String value;

    Task(String value) {
        this.value = value;
    }

    /** 파이썬판 이름 (rule_extraction / explanation_writer / semantic_judge). */
    public String value() {
        return value;
    }

    public static Task of(String value) {
        for (Task t : values()) {
            if (t.value.equals(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("알 수 없는 task: " + value);
    }

    @Override
    public String toString() {
        return value;
    }
}
