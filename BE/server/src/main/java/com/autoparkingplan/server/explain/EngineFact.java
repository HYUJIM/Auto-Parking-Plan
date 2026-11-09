package com.autoparkingplan.server.explain;

import java.util.List;

/**
 * 엔진이 만든 사실 (원장에 engine 출처로 들어간다) — 파이썬 app.explain.ledger.EngineFact.
 *
 * @param kind        SUBJECT | CONDITION
 * @param quote       설명이 인용할 수 있는 문장
 * @param mustMention 기본 true
 * @param keyTerms    null 가능 (파이썬 기본값 None)
 */
public record EngineFact(String kind, String quote, boolean mustMention, String name, List<String> keyTerms) {
    public static EngineFact subject(String quote, String name) {
        return new EngineFact("SUBJECT", quote, true, name, null);
    }

    public static EngineFact condition(String quote, List<String> keyTerms) {
        return new EngineFact("CONDITION", quote, true, null, keyTerms);
    }
}
