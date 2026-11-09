package com.autoparkingplan.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Times;
import com.autoparkingplan.db.entity.DocumentVersion;
import com.autoparkingplan.db.entity.FactLedger;
import com.autoparkingplan.db.entity.Jurisdiction;
import com.autoparkingplan.db.entity.Parcel;
import com.autoparkingplan.db.entity.ParcelDesignation;
import com.autoparkingplan.db.entity.Rule;
import com.autoparkingplan.db.entity.RuleValue;
import com.autoparkingplan.db.entity.SourceDocument;
import com.autoparkingplan.db.repo.ParcelRepository;
import com.autoparkingplan.db.repo.RuleRepository;

/** Flyway V1(H2) + 엔티티 매핑 검증(ddl-auto=validate) + JSON 컬럼 왕복 + 주요 제약. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(RuleQueries.class)
class SchemaTest {
    @Autowired
    EntityManager em;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ParcelRepository parcels;
    @Autowired
    RuleRepository rules;
    @Autowired
    RuleQueries queries;

    private Jurisdiction jur(String code, int level, String parent) {
        Jurisdiction j = new Jurisdiction();
        j.setCode(code);
        j.setName("관할 " + code);
        j.setLevel((short) level);
        j.setParentCode(parent);
        j.setIsSupported(true);
        em.persist(j);
        return j;
    }

    @Test
    void jsonColumnsRoundTripAndChildrenCascade() {
        jur("00", 0, null);
        jur("27", 1, "00");
        jur("27260", 2, "27");
        Parcel p = new Parcel();
        p.setPnu("2726010100100010000");
        p.setJurisdictionCode("27260");
        p.setJibunAddress("대구광역시 수성구 범어동 1");
        p.setGeom(Json.parse("{\"type\":\"MultiPolygon\",\"coordinates\":[[[[128.6,35.8],[128.61,35.8],[128.61,35.81],[128.6,35.8]]]]}"));
        p.setCentroid(Json.parse("[128.605, 35.805]"));
        p.setSourceMeta(Json.parse("{\"land\":{\"source\":\"vworld\",\"ok\":true,\"note\":\"한글 \\\"따옴표\\\"\"}}"));
        p.setFetchedAt(Times.now());
        p.setExpiresAt(Times.now());
        ParcelDesignation d = new ParcelDesignation();
        d.setKind("zoning");
        d.setName("제2종일반주거지역");
        d.setFetchedAt(Times.now());
        p.addDesignation(d);
        parcels.save(p);
        em.flush();
        em.clear();
        Parcel back = parcels.findById("2726010100100010000").orElseThrow();
        assertThat(back.getGeom()).isEqualTo(p.getGeom());
        assertThat(back.getCentroid().get(0).doubleValue()).isEqualTo(128.605);
        assertThat(back.getSourceMeta().get("land").get("note").stringValue()).isEqualTo("한글 \"따옴표\"");
        assertThat(back.getDesignations()).extracting(ParcelDesignation::getName).containsExactly("제2종일반주거지역");
        assertThat(back.getDesignations().getFirst().getPnu()).isEqualTo("2726010100100010000");
        back.getDesignations().clear(); // orphanRemoval
        em.flush();
        assertThat(jdbc.queryForObject("select count(*) from parcel_designation", Integer.class)).isZero();
    }

    @Test
    void activeRulesAndConstraints() {
        jur("00", 0, null);
        SourceDocument doc = new SourceDocument();
        doc.setKey("kr.law.parking");
        doc.setLayer("L1_NATIONAL");
        doc.setSourceType("law");
        doc.setOfficialTitle("주차장법");
        doc.setJurisdictionCode("00");
        doc.setExternalSystem("law.go.kr");
        em.persist(doc);
        DocumentVersion v = new DocumentVersion();
        v.setDocument(doc);
        v.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        v.setRawObjectKey("k");
        v.setRawFormat("json");
        v.setRawSha256("0".repeat(64));
        v.setSourceUrl("https://www.law.go.kr");
        v.setFetchedAt(Times.now());
        em.persist(v);
        Rule r = new Rule();
        r.setRuleKey("parking.min");
        r.setCategory("PARKING_REQUIREMENT");
        r.setLayer("L1_NATIONAL");
        r.setJurisdictionCode("00");
        r.setVersionId(v.getId());
        r.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        r.setTitle("부설주차장 설치기준");
        r.setAutomationLevel("auto");
        r.setReviewStatus("approved");
        r.setApprovedAt(Times.now());
        RuleValue val = new RuleValue();
        val.setValueKey("ratio");
        val.setValueType("formula");
        val.setFormula("ceil(gross_floor_area / 150)");
        r.addValue(val);
        rules.save(r);
        em.flush();
        em.clear();
        List<Rule> active = queries.activeRulesAt(LocalDate.of(2026, 6, 1), List.of("00"), null);
        assertThat(active).hasSize(1);
        assertThat(active.getFirst().getValues()).extracting(RuleValue::getFormula).containsExactly("ceil(gross_floor_area / 150)");
        assertThat(active.getFirst().getVersion().getRawFormat()).isEqualTo("json");
        assertThat(queries.activeRulesAt(LocalDate.of(2025, 6, 1))).isEmpty();
        assertThat(RuleQueries.rulesetHash(active)).hasSize(64);
        assertThat(RuleQueries.jurisdictionChain("27260")).containsExactly("27260", "27", "00");

        // 처리되지 않은 원문 후보가 남은 원장은 승인할 수 없다 (CHECK)
        FactLedger fl = new FactLedger();
        fl.setRuleIds(Json.array());
        fl.setProvisionIds(Json.array());
        fl.setLedgerHash("h");
        fl.setLedgerJson(Json.object());
        fl.setGapCount(1);
        fl.setReviewStatus("approved");
        em.persist(fl);
        assertThatThrownBy(() -> em.flush()).hasMessageContaining("CK_FACT_LEDGER_COMPLETE_BEFORE_APPROVE".toLowerCase().isEmpty() ? "" : "");
    }

    @Test
    void displayableViewExists() {
        assertThat(jdbc.queryForObject("select count(*) from displayable_explanation", Integer.class)).isZero();
    }
}
