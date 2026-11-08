package com.autoparkingplan.legal;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * 파이썬 {@code xml.etree.ElementTree.Element} 와 같은 모양의 읽기 전용 XML 요소 (JDK StAX 로 만든다).
 *
 * <ul>
 *   <li>tag: 이름공간이 없으면 지역 이름, 있으면 {@code {uri}local} (ElementTree 표기)</li>
 *   <li>attrib: 문서 순서 (이름공간 선언은 빠짐)</li>
 *   <li>text: 첫 자식 앞의 문자 데이터, tail: 이 요소 뒤 다음 형제 앞까지의 문자 데이터 (CDATA 포함, 주석·PI 는 무시)</li>
 * </ul>
 */
public final class XmlElement implements Iterable<XmlElement> {
    private final String tag;
    private final Map<String, String> attrib;
    private final List<XmlElement> children = new ArrayList<>();
    private String text;
    private String tail;

    private XmlElement(String tag, Map<String, String> attrib) {
        this.tag = tag;
        this.attrib = attrib;
    }

    public String tag() {
        return tag;
    }

    public Map<String, String> attrib() {
        return Collections.unmodifiableMap(attrib);
    }

    /** 첫 자식 앞 문자 데이터, 없으면 null. */
    public String text() {
        return text;
    }

    public String tail() {
        return tail;
    }

    /** 직계 자식 요소 수 ({@code len(el)}). */
    public int size() {
        return children.size();
    }

    public List<XmlElement> children() {
        return Collections.unmodifiableList(children);
    }

    @Override
    public Iterator<XmlElement> iterator() {
        return children().iterator();
    }

    /** {@code el.find(tag)}: 같은 이름의 첫 직계 자식, 없으면 null. */
    public XmlElement find(String childTag) {
        for (XmlElement c : children) {
            if (c.tag.equals(childTag)) {
                return c;
            }
        }
        return null;
    }

    /** {@code el.findall(tag)}: 같은 이름의 직계 자식들. */
    public List<XmlElement> findall(String childTag) {
        List<XmlElement> out = new ArrayList<>();
        for (XmlElement c : children) {
            if (c.tag.equals(childTag)) {
                out.add(c);
            }
        }
        return out;
    }

    /** {@code el.iter()}: 자기 자신과 모든 하위 요소 (문서 순서). */
    public List<XmlElement> iter() {
        List<XmlElement> out = new ArrayList<>();
        collect(this, null, out);
        return out;
    }

    /** {@code el.iter(tag)}: 자기 자신 포함, 이름이 같은 요소들 (문서 순서). */
    public List<XmlElement> iter(String t) {
        List<XmlElement> out = new ArrayList<>();
        collect(this, t, out);
        return out;
    }

    private static void collect(XmlElement e, String t, List<XmlElement> out) {
        if (t == null || e.tag.equals(t)) {
            out.add(e);
        }
        for (XmlElement c : e.children) {
            collect(c, t, out);
        }
    }

    /** {@code "".join(el.itertext())}. */
    public String itertext() {
        StringBuilder sb = new StringBuilder();
        appendText(this, sb);
        return sb.toString();
    }

    private static void appendText(XmlElement e, StringBuilder sb) {
        if (e.text != null) {
            sb.append(e.text);
        }
        for (XmlElement c : e.children) {
            appendText(c, sb);
            if (c.tail != null) {
                sb.append(c.tail);
            }
        }
    }

    /**
     * {@code ET.fromstring(raw)}. 인코딩은 XML 선언·BOM 으로 정한다. 외부 엔티티·외부 DTD 는 읽지 않는다.
     *
     * @throws XMLStreamException 형식이 맞지 않을 때 (파이썬 ET.ParseError)
     */
    public static XmlElement parse(byte[] raw) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, true);
        f.setProperty(XMLInputFactory.IS_COALESCING, false);
        try {
            f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        } catch (IllegalArgumentException ignored) {
            // 구현에 따라 지원하지 않을 수 있다
        }
        XMLStreamReader r = f.createXMLStreamReader(new ByteArrayInputStream(raw));
        try {
            Deque<XmlElement> stack = new ArrayDeque<>();
            XmlElement root = null;
            XmlElement lastClosed = null; // 직전에 닫힌 요소 (그 뒤 문자 데이터 = tail)
            StringBuilder data = new StringBuilder();
            while (r.hasNext()) {
                int ev = r.next();
                switch (ev) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        flush(stack, lastClosed, data);
                        Map<String, String> attrs = new LinkedHashMap<>();
                        for (int i = 0; i < r.getAttributeCount(); i++) {
                            attrs.put(qname(r.getAttributeNamespace(i), r.getAttributeLocalName(i)), r.getAttributeValue(i));
                        }
                        XmlElement el = new XmlElement(qname(r.getNamespaceURI(), r.getLocalName()), attrs);
                        if (stack.isEmpty()) {
                            root = el;
                        } else {
                            stack.peek().children.add(el);
                        }
                        stack.push(el);
                        lastClosed = null;
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        flush(stack, lastClosed, data);
                        lastClosed = stack.pop();
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                        if (!stack.isEmpty()) {
                            data.append(r.getText());
                        }
                    }
                    case XMLStreamConstants.ENTITY_REFERENCE -> {
                        if (!stack.isEmpty()) {
                            data.append(r.getText());
                        }
                    }
                    default -> {
                        // 주석·PI·DTD 는 ElementTree 기본 TreeBuilder 처럼 버린다 (문자 데이터는 이어 붙는다)
                    }
                }
            }
            if (root == null) {
                throw new XMLStreamException("no element found");
            }
            return root;
        } catch (NoSuchElementException e) {
            throw new XMLStreamException("unexpected end of document", e);
        } finally {
            r.close();
        }
    }

    private static void flush(Deque<XmlElement> stack, XmlElement lastClosed, StringBuilder data) {
        if (data.isEmpty()) {
            return;
        }
        String s = data.toString();
        data.setLength(0);
        if (lastClosed != null) {
            lastClosed.tail = lastClosed.tail == null ? s : lastClosed.tail + s;
        } else if (!stack.isEmpty()) {
            XmlElement cur = stack.peek();
            cur.text = cur.text == null ? s : cur.text + s;
        }
    }

    private static String qname(String ns, String local) {
        return ns == null || ns.isEmpty() ? local : "{" + ns + "}" + local;
    }
}
