#!/usr/bin/env python3
"""Render a Markdown file to a standalone HTML file.

Stdlib-only implementation covering the Markdown subset produced by
tools/bazel/apt_to_md.py: headings, paragraphs, fenced code blocks,
bullet and numbered lists, tables, links (inline + autolink), images,
inline code, emphasis. Invoked from archiva-docs/BUILD.bazel via a
py_binary so the build is hermetic — no external Markdown engine is
required.

Internal `*.html` link targets are not rewritten: the original APT source
linked between rendered pages, and the markdown sources preserve those
hrefs so the output HTML site keeps working.

Usage:
    md_to_html.py <input.md> <output.html>
"""

import html
import re
import sys
from pathlib import Path


FENCE_RE = re.compile(r"^```\s*(\S*)\s*$")
HEADING_RE = re.compile(r"^(#{1,6})\s+(.*\S)\s*$")
BULLET_RE = re.compile(r"^(\s*)-\s+(.*)$")
NUMBERED_RE = re.compile(r"^(\s*)(\d+)\.\s+(.*)$")
TABLE_ROW_RE = re.compile(r"^\s*\|.*\|\s*$")
TABLE_SEP_RE = re.compile(r"^\s*\|?\s*[:\- ]+(\|\s*[:\- ]+\s*)+\|?\s*$")
IMAGE_RE = re.compile(r"!\[([^\]]*)\]\(([^)]+)\)")
LINK_RE = re.compile(r"\[([^\]]+)\]\(([^)]+)\)")
AUTOLINK_RE = re.compile(r"<((?:https?|ftp|mailto):[^>\s]+)>")
CODE_SPAN_RE = re.compile(r"`([^`]+)`")
BOLD_RE = re.compile(r"\*\*([^*]+)\*\*")
ITALIC_RE = re.compile(r"(?<![*\w])\*([^*\s][^*]*?)\*(?!\w)")


def escape(s: str) -> str:
    return html.escape(s, quote=False)


def render_inline(text: str) -> str:
    """Render Markdown inline syntax to HTML.

    Code spans are extracted first so their contents survive the other
    transformations untouched.
    """
    spans: list[str] = []

    def park(s: str) -> str:
        spans.append(s)
        return f"\x00{len(spans) - 1}\x00"

    text = CODE_SPAN_RE.sub(
        lambda m: park("<code>" + escape(m.group(1)) + "</code>"), text
    )
    text = IMAGE_RE.sub(
        lambda m: park(
            '<img src="{}" alt="{}"/>'.format(
                escape(m.group(2).strip()), escape(m.group(1).strip())
            )
        ),
        text,
    )
    text = LINK_RE.sub(
        lambda m: park(
            '<a href="{}">{}</a>'.format(
                escape(m.group(2).strip()), escape(m.group(1))
            )
        ),
        text,
    )
    text = AUTOLINK_RE.sub(
        lambda m: park(
            '<a href="{}">{}</a>'.format(
                escape(m.group(1)), escape(m.group(1))
            )
        ),
        text,
    )

    text = escape(text)

    text = BOLD_RE.sub(r"<strong>\1</strong>", text)
    text = ITALIC_RE.sub(r"<em>\1</em>", text)

    text = re.sub(r"\x00(\d+)\x00", lambda m: spans[int(m.group(1))], text)
    return text


def render_table(rows: list[str]) -> str:
    """Convert a Markdown pipe table (header / separator / rows) to HTML."""
    cells_rows: list[list[str]] = []
    for r in rows:
        stripped = r.strip()
        if stripped.startswith("|"):
            stripped = stripped[1:]
        if stripped.endswith("|"):
            stripped = stripped[:-1]
        cells_rows.append([c.strip() for c in stripped.split("|")])

    if len(cells_rows) < 2:
        return ""
    header = cells_rows[0]
    body = cells_rows[2:]

    out = ["<table>"]
    out.append("<thead><tr>")
    for c in header:
        out.append("<th>" + render_inline(c) + "</th>")
    out.append("</tr></thead>")
    if body:
        out.append("<tbody>")
        for row in body:
            out.append("<tr>")
            for c in row:
                out.append("<td>" + render_inline(c) + "</td>")
            out.append("</tr>")
        out.append("</tbody>")
    out.append("</table>")
    return "".join(out)


def render(md: str) -> str:
    """Render a Markdown document to an HTML body fragment."""
    lines = md.splitlines()
    out: list[str] = []
    i, n = 0, len(lines)

    # `list_stack` tracks open <ul>/<ol> tags so nested-list close tags
    # come out in the right order.
    list_stack: list[str] = []

    def close_lists() -> None:
        while list_stack:
            out.append(f"</{list_stack.pop()}>")

    while i < n:
        line = lines[i]

        m_fence = FENCE_RE.match(line)
        if m_fence:
            close_lists()
            lang = m_fence.group(1).strip()
            cls = f' class="language-{escape(lang)}"' if lang else ""
            i += 1
            buf: list[str] = []
            while i < n and not FENCE_RE.match(lines[i]):
                buf.append(lines[i])
                i += 1
            if i < n:
                i += 1  # consume closing fence
            out.append(f"<pre><code{cls}>" + escape("\n".join(buf)) + "</code></pre>")
            continue

        if TABLE_ROW_RE.match(line) and i + 1 < n and TABLE_SEP_RE.match(lines[i + 1]):
            close_lists()
            tbl: list[str] = [line, lines[i + 1]]
            i += 2
            while i < n and TABLE_ROW_RE.match(lines[i]):
                tbl.append(lines[i])
                i += 1
            out.append(render_table(tbl))
            continue

        m_h = HEADING_RE.match(line)
        if m_h:
            close_lists()
            level = len(m_h.group(1))
            out.append(f"<h{level}>" + render_inline(m_h.group(2).strip()) + f"</h{level}>")
            i += 1
            continue

        if not line.strip():
            # Blank lines inside a same-type list block don't end the list,
            # so peek ahead to decide whether to close.
            j = i + 1
            while j < n and not lines[j].strip():
                j += 1
            if (
                list_stack
                and j < n
                and (
                    (list_stack[-1] == "ul" and BULLET_RE.match(lines[j]))
                    or (list_stack[-1] == "ol" and NUMBERED_RE.match(lines[j]))
                )
            ):
                i = j
                continue
            close_lists()
            i += 1
            continue

        m_b = BULLET_RE.match(line)
        m_o = NUMBERED_RE.match(line)
        if m_b or m_o:
            if not list_stack or list_stack[-1] != ("ul" if m_b else "ol"):
                close_lists()
                list_stack.append("ul" if m_b else "ol")
                out.append(f"<{list_stack[-1]}>")
            content = (m_b.group(2) if m_b else m_o.group(3)).strip()
            item_parts = [content]
            i += 1
            while i < n:
                nxt = lines[i]
                if not nxt.strip():
                    break
                if (
                    HEADING_RE.match(nxt)
                    or BULLET_RE.match(nxt)
                    or NUMBERED_RE.match(nxt)
                    or FENCE_RE.match(nxt)
                    or TABLE_ROW_RE.match(nxt)
                ):
                    break
                item_parts.append(nxt.strip())
                i += 1
            out.append("<li>" + render_inline(" ".join(item_parts)) + "</li>")
            continue

        close_lists()
        para_parts = [line.strip()]
        i += 1
        while i < n:
            nxt = lines[i]
            if not nxt.strip():
                break
            if (
                HEADING_RE.match(nxt)
                or BULLET_RE.match(nxt)
                or NUMBERED_RE.match(nxt)
                or FENCE_RE.match(nxt)
                or TABLE_ROW_RE.match(nxt)
            ):
                break
            para_parts.append(nxt.strip())
            i += 1
        out.append("<p>" + render_inline(" ".join(para_parts)) + "</p>")

    close_lists()
    return "\n".join(out)


HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<title>{title}</title>
<link rel="stylesheet" href="{css_prefix}css/site.css"/>
</head>
<body>
{body}
</body>
</html>
"""


def derive_title(md: str, fallback: str) -> str:
    for line in md.splitlines():
        m = HEADING_RE.match(line)
        if m and len(m.group(1)) == 1:
            return m.group(2).strip()
    return fallback


def relative_prefix(rel_path: Path) -> str:
    """Number of `../` needed for a page at rel_path to reach the doc root."""
    depth = max(len(rel_path.parts) - 1, 0)
    return "../" * depth


def main(argv: list[str]) -> int:
    if len(argv) != 4:
        print(
            f"usage: {argv[0]} <input.md> <output.html> <rel-path-from-doc-root>",
            file=sys.stderr,
        )
        return 2
    src = Path(argv[1])
    dst = Path(argv[2])
    rel = Path(argv[3])
    text = src.read_text(encoding="utf-8")
    body = render(text)
    title = derive_title(text, src.stem)
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(
        HTML_TEMPLATE.format(
            title=escape(title), body=body, css_prefix=relative_prefix(rel)
        ),
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
