package com.autoparkingplan.legal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import javax.xml.stream.XMLStreamException;

import org.junit.jupiter.api.Test;

/** (자바판 추가) XML 해석기 보호 설정 — 외부 엔티티(XXE)·엔티티 폭발을 처리하지 않는다. */
class XmlElementSecurityTest {
    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parsesPlainDocument() throws XMLStreamException {
        XmlElement root = XmlElement.parse(utf8("<?xml version=\"1.0\" encoding=\"UTF-8\"?><법령><조문>가 &amp; 나</조문></법령>"));
        assertThat(root.tag()).isEqualTo("법령");
        assertThat(root.find("조문").text()).isEqualTo("가 & 나");
    }

    /** billion laughs: DTD 를 처리하지 않으므로 확장되지 않는다 (선언되지 않은 엔티티 → 형식 오류, 또는 빈 텍스트). 시간·메모리를 쓰지 않는다. */
    @Test
    void doesNotExpandEntityBomb() {
        StringBuilder dtd = new StringBuilder("<!DOCTYPE lolz [<!ENTITY lol \"lol\">");
        for (int i = 1; i <= 9; i++) {
            String prev = i == 1 ? "&lol;" : "&lol" + (i - 1) + ";";
            dtd.append("<!ENTITY lol").append(i).append(" \"").append(prev.repeat(10)).append("\">");
        }
        dtd.append("]>");
        byte[] doc = utf8("<?xml version=\"1.0\"?>" + dtd + "<lolz>&lol9;</lolz>");
        long t0 = System.nanoTime();
        try {
            XmlElement root = XmlElement.parse(doc);
            assertThat(root.itertext()).doesNotContain("lol");
        } catch (XMLStreamException expected) {
            // DTD 미처리 → 선언되지 않은 엔티티
        }
        assertThat((System.nanoTime() - t0) / 1e9).isLessThan(5.0);
    }

    /** 외부 엔티티(XXE): 파일·URL 을 읽지 않는다. */
    @Test
    void doesNotResolveExternalEntities() {
        byte[] doc = utf8("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY xxe SYSTEM \"file:///etc/hosts\">]><x>&xxe;</x>");
        try {
            XmlElement root = XmlElement.parse(doc);
            assertThat(root.itertext()).isEmpty();
        } catch (XMLStreamException expected) {
            // DTD 미처리 → 선언되지 않은 엔티티
        }
    }
}
