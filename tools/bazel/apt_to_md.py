#!/usr/bin/env python3
"""Convert Maven APT files under archiva-docs to Markdown.

The Maven APT (Almost Plain Text) format is described at
http://maven.apache.org/doxia/references/apt-format.html. This converter
implements the subset that appears in archiva-docs/src/site/apt: header
blocks, section titles, paragraphs, lists, code blocks, tables, links,
images, and inline emphasis.

One-shot migration tool: the produced Markdown files are committed.
The Bazel rule in archiva-docs/BUILD.bazel renders them to HTML at
build time.

Usage:
    python3 tools/bazel/apt_to_md.py <input-dir> <output-dir>
"""

import re
import sys
from pathlib import Path


HEADER_SEP_RE = re.compile(r"^\s+-{3,}\s*$")
COMMENT_RE = re.compile(r"^\s*~~")
CODE_FENCE_PLUS_RE = re.compile(r"^\+-+\+?\s*$")
CODE_FENCE_DASH_RE = re.compile(r"^-{3,}\s*$")
HEADING_RE = re.compile(r"^(\*+)\s+(.*\S)\s*$")
BULLET_ITEM_RE = re.compile(r"^(\s+)\*\s+(.*)$")
NUMBERED_BRACKET_RE = re.compile(r"^(\s+)\[\[(\d+)\]\]\s*(.*)$")
NUMBERED_DOT_RE = re.compile(r"^(\s+)(\d+)[.)]\s+(.*)$")
LIST_END_RE = re.compile(r"^\s+\[\]\s*$")
ANCHOR_LINE_RE = re.compile(r"^\{([^{}]+)\}\s*$")
IMAGE_RE = re.compile(
    r"^\s*\[([^\]]+\.(?:png|jpg|jpeg|gif|svg))\](.*)$", re.IGNORECASE
)
TABLE_SEP_RE = re.compile(r"^\*[-+]+\s*$")


def convert_inline(text: str) -> str:
    """Convert APT inline markup to Markdown inline markup."""
    text = (
        text.replace(r"\<", "\x01")
        .replace(r"\>", "\x02")
        .replace(r"\{", "\x03")
        .replace(r"\}", "\x04")
        .replace(r"\$", "\x05")
    )

    # Convert links to placeholders first so URLs don't get matched by the
    # subsequent angle-bracket emphasis rules.
    placeholders: list[str] = []

    # Stash `${...}` Velocity expressions so their braces don't confuse the
    # APT brace-link regex below. They're put back unchanged.
    def stash_velocity(m: re.Match[str]) -> str:
        placeholders.append(m.group(0))
        return f"\x06{len(placeholders) - 1}\x07"

    text = re.sub(r"\$\{[^{}]*\}", stash_velocity, text)

    def park(s: str) -> str:
        placeholders.append(s)
        return f"\x06{len(placeholders) - 1}\x07"

    def link_triple(m: re.Match[str]) -> str:
        url = m.group(1).strip()
        label = m.group(2).strip() or url
        return park(f"[{label}]({url})")

    text = re.sub(
        r"\{\{\{\s*([^{}]+?)\s*\}\s*([^{}]*?)\s*\}\}", link_triple, text
    )
    text = re.sub(
        r"\{\{\s*([^{}]+?)\s*\}\}",
        lambda m: park(f"<{m.group(1).strip()}>"),
        text,
    )

    text = re.sub(r"<<<([^<>]+?)>>>", r"`\1`", text)
    text = re.sub(r"<<([^<>]+?)>>", r"**\1**", text)
    text = re.sub(r"<([^<>\s][^<>]*?)>", r"*\1*", text)

    text = re.sub(
        r"\x06(\d+)\x07", lambda m: placeholders[int(m.group(1))], text
    )

    text = (
        text.replace("\x01", "<")
        .replace("\x02", ">")
        .replace("\x03", "{")
        .replace("\x04", "}")
        .replace("\x05", "$")
    )
    return text


def join_wrapped_links(lines: list[str]) -> list[str]:
    """Join physical lines where an APT link or table cell wraps.

    `{{{url} label spanning\n  more text}}` and the trailing-`|` table-row
    pattern both span multiple input lines. The line-oriented main loop
    can't see across them, so we pre-merge here.
    """
    out: list[str] = []
    i = 0
    while i < len(lines):
        cur = lines[i]
        while True:
            opens = cur.count("{{") - cur.count("\\{")
            closes = cur.count("}}") - cur.count("\\}")
            if opens <= closes:
                break
            if i + 1 >= len(lines):
                break
            nxt = lines[i + 1].strip()
            if not nxt:
                break
            cur = cur + " " + nxt
            i += 1
        out.append(cur)
        i += 1
    return out


def split_table_row(line: str) -> list[str]:
    """Split a `| a | b | c` (optional trailing `|`) row into cell strings."""
    stripped = line.rstrip()
    parts = stripped.split("|")
    # leading "" before first |, optional trailing "" after last |
    parts = parts[1:]
    if parts and stripped.endswith("|"):
        parts = parts[:-1]
    return [p.strip() for p in parts]


def parse_table(lines: list[str], start: int) -> tuple[list[str], int]:
    """Parse an APT table starting at lines[start] (a `*-+-+` separator).

    Returns (markdown_lines, next_index_to_process).
    """
    i = start + 1
    rows: list[list[str]] = []
    current: list[str] | None = None
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        if TABLE_SEP_RE.match(stripped):
            if current is not None:
                rows.append(current)
                current = None
            i += 1
            continue
        if line.lstrip().startswith("|"):
            cells = split_table_row(line)
            if current is None:
                current = list(cells)
            else:
                while len(current) < len(cells):
                    current.append("")
                for j, c in enumerate(cells):
                    if c:
                        current[j] = (current[j] + " " + c).strip() if current[j] else c
            i += 1
            continue
        break
    if current is not None:
        rows.append(current)

    if not rows:
        return [], i

    width = max(len(r) for r in rows)
    for r in rows:
        while len(r) < width:
            r.append("")

    md: list[str] = []
    header = rows[0]
    body = rows[1:]
    md.append("| " + " | ".join(convert_inline(c) for c in header) + " |")
    md.append("|" + "|".join(" --- " for _ in header) + "|")
    for r in body:
        md.append("| " + " | ".join(convert_inline(c) for c in r) + " |")
    md.append("")
    return md, i


def convert(text: str) -> str:
    lines = join_wrapped_links(text.splitlines())
    out: list[str] = []
    i, n = 0, len(lines)

    while i < n and not lines[i].strip():
        i += 1

    title = None
    if i < n and HEADER_SEP_RE.match(lines[i]):
        i += 1
        if i < n and lines[i].strip() and not HEADER_SEP_RE.match(lines[i]):
            title = lines[i].strip()
            i += 1
        while i < n:
            if HEADER_SEP_RE.match(lines[i]):
                i += 1
            elif not lines[i].strip():
                i += 1
                break
            else:
                i += 1

    title_pending = False
    if title:
        out.append("# " + convert_inline(title))
        out.append("")
        title_pending = True

    in_code = False
    code_lang = ""

    def push_blank() -> None:
        if out and out[-1] != "":
            out.append("")

    while i < n:
        line = lines[i]
        stripped = line.strip()

        if in_code:
            if (code_lang == "plus" and CODE_FENCE_PLUS_RE.match(stripped)) or (
                code_lang == "dash" and CODE_FENCE_DASH_RE.match(stripped)
            ):
                out.append("```")
                in_code = False
                i += 1
                continue
            out.append(line)
            i += 1
            continue

        if COMMENT_RE.match(line):
            i += 1
            continue

        if not line.startswith(" ") and CODE_FENCE_PLUS_RE.match(stripped):
            push_blank()
            out.append("```")
            in_code = True
            code_lang = "plus"
            i += 1
            continue
        if not line.startswith(" ") and CODE_FENCE_DASH_RE.match(stripped):
            push_blank()
            out.append("```")
            in_code = True
            code_lang = "dash"
            i += 1
            continue

        if not line.startswith(" ") and TABLE_SEP_RE.match(stripped):
            push_blank()
            md, i = parse_table(lines, i)
            out.extend(md)
            continue

        if not stripped:
            push_blank()
            i += 1
            continue

        if LIST_END_RE.match(line):
            push_blank()
            i += 1
            continue

        m_img = IMAGE_RE.match(line)
        if m_img:
            path = m_img.group(1).strip()
            caption = m_img.group(2).strip()
            alt = convert_inline(caption) if caption else Path(path).stem
            push_blank()
            out.append(f"![{alt}]({path})")
            out.append("")
            i += 1
            continue

        m_h = HEADING_RE.match(line)
        if m_h and not line.startswith(" "):
            level = min(len(m_h.group(1)) + 1, 6)
            heading = convert_inline(m_h.group(2).strip())
            push_blank()
            out.append("#" * level + " " + heading)
            out.append("")
            title_pending = False
            i += 1
            continue

        m_anchor = ANCHOR_LINE_RE.match(line)
        if m_anchor and not line.startswith(" "):
            heading = convert_inline(m_anchor.group(1).strip())
            push_blank()
            out.append("## " + heading)
            out.append("")
            title_pending = False
            i += 1
            continue

        if line and not line.startswith(" "):
            heading = convert_inline(stripped)
            if title and stripped == title and title_pending:
                title_pending = False
                i += 1
                continue
            push_blank()
            out.append("## " + heading)
            out.append("")
            title_pending = False
            i += 1
            continue

        m_n = NUMBERED_BRACKET_RE.match(line)
        if m_n:
            out.append(f"{m_n.group(2)}. {convert_inline(m_n.group(3).strip())}")
            i += 1
            continue

        m_b = BULLET_ITEM_RE.match(line)
        if m_b:
            out.append(f"- {convert_inline(m_b.group(2).strip())}")
            i += 1
            continue

        m_d = NUMBERED_DOT_RE.match(line)
        if m_d:
            out.append(f"{m_d.group(2)}. {convert_inline(m_d.group(3).strip())}")
            i += 1
            continue

        out.append(convert_inline(stripped))
        i += 1

    if in_code:
        out.append("```")

    cleaned: list[str] = []
    prev_blank = False
    for ln in out:
        is_blank = ln == ""
        if is_blank and prev_blank:
            continue
        cleaned.append(ln)
        prev_blank = is_blank
    while cleaned and cleaned[-1] == "":
        cleaned.pop()
    cleaned.append("")
    return "\n".join(cleaned)


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(f"usage: {argv[0]} <input-dir> <output-dir>", file=sys.stderr)
        return 2
    in_dir = Path(argv[1])
    out_dir = Path(argv[2])
    if not in_dir.is_dir():
        print(f"not a directory: {in_dir}", file=sys.stderr)
        return 2
    for src in sorted(in_dir.rglob("*")):
        if not src.is_file():
            continue
        if not (src.suffix == ".apt" or src.name.endswith(".apt.vm")):
            continue
        rel = src.relative_to(in_dir)
        name = rel.name
        if name.endswith(".apt.vm"):
            name = name[: -len(".apt.vm")] + ".md"
        else:
            name = rel.stem + ".md"
        dst = out_dir / rel.parent / name
        dst.parent.mkdir(parents=True, exist_ok=True)
        text = src.read_text(encoding="utf-8")
        dst.write_text(convert(text), encoding="utf-8")
        print(f"{src} -> {dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
