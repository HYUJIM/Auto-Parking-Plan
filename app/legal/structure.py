"""
조문 구조 파서 (결정적, AI 미사용) — 설계서 6장 1단계.

조(article) 원문 전체를 받아 항(①)·호(1.)·목(가.) 트리로 나눈다.
각 노드의 text 는 '그 단위 원문 전체'(자기 줄 + 하위 단위 줄)이며, 원문의 연속 부분 문자열이다.
그래서 AI 가 하위 단위(호)를 근거로 인용해도 상위 단위 원문 안에서 같은 문자열을 찾을 수 있다.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

CIRCLED = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳"
# 목 순서: 가~하 다음은 거·너·더… (법령 별표에서 실제로 쓰임)
HANGUL_ORDER = "가나다라마바사아자차카타파하거너더러머버서어저처커터퍼허"

ARTICLE_HEAD_RE = re.compile(r"제\s*(\d+)\s*조(?:\s*의\s*(\d+))?\s*(?:\(([^)]*)\))?\s*")
PARA_RE = re.compile(rf"^\s*([{CIRCLED}])\s*")
ITEM_RE = re.compile(r"^\s*(\d+)(?:\s*의\s*(\d+))?\s*\.(?!\d)\s*")
SUBITEM_RE = re.compile(rf"^\s*([{HANGUL_ORDER}])(?:\s*의\s*(\d+))?\s*\.\s*")


@dataclass
class Node:
    kind: str  # article | paragraph | item | subitem | annex | annex_row
    label: str
    path: str
    heading: str | None
    start: int  # 원문(article text) 안의 시작 위치
    end: int
    children: list[Node] = field(default_factory=list)
    table_json: object | None = None

    def text_of(self, source: str) -> str:
        return source[self.start:self.end].strip()


def _line_spans(text: str) -> list[tuple[int, int]]:
    spans, pos = [], 0
    for line in text.split("\n"):
        spans.append((pos, pos + len(line)))
        pos += len(line) + 1
    return spans


def parse_article(label: str, path: str, heading: str | None, text: str, *, kind: str = "article") -> Node:
    """조 하나의 원문을 트리로 만든다. 줄 단위로 항·호·목 표지를 인식한다.

    첫 줄이 '제84조(…) ① …' 처럼 조 제목과 첫 항을 함께 담고 있으면 제목 부분을 건너뛰고 항을 인식한다.
    """
    root = Node(kind, label, path, heading, 0, len(text))
    para: Node | None = None
    item: Node | None = None
    sub: Node | None = None
    for i, (s, e) in enumerate(_line_spans(text)):
        line = text[s:e]
        if not line.strip():
            continue
        offset = 0
        if i == 0 and kind == "article":
            # 첫 줄의 '제84조(제목)' 머리 부분은 건너뛴다 (앞에 주석·표지가 붙어 있어도)
            m = ARTICLE_HEAD_RE.search(line[:80])
            if m:
                offset = m.end()
        body = line[offset:]
        if m := PARA_RE.match(body):
            n = CIRCLED.index(m.group(1)) + 1
            para = Node("paragraph", m.group(1), f"{path}/para{n}", None, s + offset, e)
            root.children.append(para)
            item = sub = None
        elif m := ITEM_RE.match(body):
            n = m.group(1) + (f"-{m.group(2)}" if m.group(2) else "")
            lab = m.group(1) + (f"의{m.group(2)}" if m.group(2) else "") + "."
            parent = para or root
            item = Node("item", lab, f"{parent.path}/item{n}", None, s + offset, e)
            parent.children.append(item)
            sub = None
        elif (m := SUBITEM_RE.match(body)) and item is not None:
            n = str(HANGUL_ORDER.index(m.group(1)) + 1) + (f"-{m.group(2)}" if m.group(2) else "")
            lab = m.group(1) + (f"의{m.group(2)}" if m.group(2) else "") + "."
            sub = Node("subitem", lab, f"{item.path}/sub{n}", None, s + offset, e)
            item.children.append(sub)
        else:
            # 이어지는 줄(표지 없음): 가장 깊은 열린 노드에 붙인다
            pass
        for open_node in (sub, item, para):
            if open_node is not None:
                open_node.end = max(open_node.end, e)
        # 상위 노드의 end 를 하위 노드 끝까지 늘린다
        if item is not None and sub is not None:
            item.end = max(item.end, sub.end)
        if para is not None and item is not None:
            para.end = max(para.end, item.end)
    return root


def flatten(root: Node, source: str) -> list[dict]:
    """트리를 provision 행 목록으로 (부모 path 포함, 정렬 순서 유지)."""
    out: list[dict] = []

    def walk(n: Node, parent_path: str | None):
        out.append({"kind": n.kind, "label": n.label, "path": n.path, "heading": n.heading,
                    "text": source.strip() if parent_path is None else n.text_of(source),
                    "table_json": n.table_json, "parent_path": parent_path})
        for c in n.children:
            walk(c, n.path)

    walk(root, None)
    # 같은 path 가 중복되면(원문 번호 오기 등) 뒤의 것에 접미사를 붙여 유일하게 만든다
    seen: dict[str, int] = {}
    for row in out:
        if row["path"] in seen:
            seen[row["path"]] += 1
            row["path"] = f"{row['path']}~{seen[row['path']]}"
        else:
            seen[row["path"]] = 0
    return out


def article_label_for(path: str, base_label: str) -> str:
    """'art84/para1/item4' → '제84조제1항제4호' (근거 표시용)."""
    parts = path.split("/")
    label = base_label
    for p in parts[1:]:
        if p.startswith("para"):
            label += f"제{p[4:].split('~')[0]}항"
        elif p.startswith("item"):
            n = p[4:].split("~")[0]
            label += "제" + n.replace("-", "호의") + ("호" if "-" not in n else "")
        elif p.startswith("sub"):
            n = int(p[3:].split("~")[0].split("-")[0])
            label += f"{HANGUL_ORDER[n - 1]}목"
    return label
