"use client";

import React from "react";

/**
 * Tiny, dependency-free, XSS-safe Markdown → React renderer.
 *
 * It builds real React elements (never sets innerHTML), and covers the subset
 * Claude actually emits: fenced code blocks, inline code, bold/italic, links,
 * headings, blockquotes, ordered/unordered lists, horizontal rules, and
 * paragraphs (single newlines become <br>).
 */

// ---- inline (bold / italic / code / links) ----------------------------------

interface InlinePattern {
  re: RegExp;
  render: (m: RegExpMatchArray, key: string) => React.ReactNode;
}

const INLINE: InlinePattern[] = [
  { re: /`([^`]+)`/, render: (m, k) => <code key={k} className="md-code-inline">{m[1]}</code> },
  { re: /\*\*([^*]+)\*\*/, render: (m, k) => <strong key={k}>{parseInline(m[1])}</strong> },
  { re: /__([^_]+)__/, render: (m, k) => <strong key={k}>{parseInline(m[1])}</strong> },
  { re: /\*([^*\n]+)\*/, render: (m, k) => <em key={k}>{parseInline(m[1])}</em> },
  { re: /_([^_\n]+)_/, render: (m, k) => <em key={k}>{parseInline(m[1])}</em> },
  {
    re: /\[([^\]]+)\]\(([^)\s]+)\)/,
    render: (m, k) => (
      <a key={k} href={m[2]} target="_blank" rel="noreferrer" className="md-link">
        {m[1]}
      </a>
    ),
  },
];

function parseInline(text: string): React.ReactNode[] {
  const out: React.ReactNode[] = [];
  let rest = text;
  let key = 0;

  while (rest.length) {
    let best: { idx: number; len: number; node: React.ReactNode } | null = null;
    for (const p of INLINE) {
      const m = rest.match(p.re);
      if (m && m.index !== undefined) {
        if (!best || m.index < best.idx) {
          best = { idx: m.index, len: m[0].length, node: p.render(m, `i${key}`) };
        }
      }
    }
    if (!best) {
      out.push(rest);
      break;
    }
    if (best.idx > 0) out.push(rest.slice(0, best.idx));
    out.push(best.node);
    rest = rest.slice(best.idx + best.len);
    key++;
  }
  return out;
}

function withBreaks(lines: string[], keyBase: string): React.ReactNode[] {
  const out: React.ReactNode[] = [];
  lines.forEach((ln, i) => {
    if (i > 0) out.push(<br key={`${keyBase}-br${i}`} />);
    out.push(...parseInline(ln));
  });
  return out;
}

// ---- blocks -----------------------------------------------------------------

type Align = "left" | "center" | "right" | null;

type Block =
  | { type: "code"; lang: string; text: string }
  | { type: "heading"; level: number; text: string }
  | { type: "hr" }
  | { type: "quote"; lines: string[] }
  | { type: "ul"; items: string[] }
  | { type: "ol"; items: string[] }
  | { type: "table"; header: string[]; align: Align[]; rows: string[][] }
  | { type: "p"; lines: string[] };

// `| --- | :---: |` style delimiter row that marks the 2nd line of a table.
function isDelimRow(line: string): boolean {
  return /^\s*\|?(?:\s*:?-+:?\s*\|)+\s*:?-+:?\s*\|?\s*$/.test(line) || /^\s*\|(?:\s*:?-+:?\s*\|)+\s*$/.test(line);
}
function isTableStart(lines: string[], i: number): boolean {
  return (
    i + 1 < lines.length &&
    lines[i].includes("|") &&
    isDelimRow(lines[i + 1]) &&
    lines[i + 1].includes("-")
  );
}
function splitCells(line: string): string[] {
  let s = line.trim();
  if (s.startsWith("|")) s = s.slice(1);
  if (s.endsWith("|")) s = s.slice(0, -1);
  return s.split("|").map((c) => c.trim());
}

function parseBlocks(src: string): Block[] {
  const blocks: Block[] = [];
  // First, peel off fenced code blocks so their contents are never reparsed.
  const fence = /```(\w*)\n?([\s\S]*?)```/g;
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = fence.exec(src))) {
    if (m.index > last) parseProse(src.slice(last, m.index), blocks);
    blocks.push({ type: "code", lang: m[1] || "", text: m[2].replace(/\n$/, "") });
    last = fence.lastIndex;
  }
  if (last < src.length) parseProse(src.slice(last), blocks);
  return blocks;
}

function parseProse(src: string, blocks: Block[]): void {
  const lines = src.split("\n");
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];

    if (!line.trim()) {
      i++;
      continue;
    }

    if (isTableStart(lines, i)) {
      const header = splitCells(lines[i]);
      const align: Align[] = splitCells(lines[i + 1]).map((c) => {
        const l = c.startsWith(":");
        const r = c.endsWith(":");
        return l && r ? "center" : r ? "right" : l ? "left" : null;
      });
      i += 2;
      const rows: string[][] = [];
      while (i < lines.length && lines[i].trim() && lines[i].includes("|")) {
        rows.push(splitCells(lines[i]));
        i++;
      }
      blocks.push({ type: "table", header, align, rows });
      continue;
    }

    const h = line.match(/^(#{1,6})\s+(.*)$/);
    if (h) {
      blocks.push({ type: "heading", level: h[1].length, text: h[2] });
      i++;
      continue;
    }

    if (/^\s*([-*_])\1{2,}\s*$/.test(line)) {
      blocks.push({ type: "hr" });
      i++;
      continue;
    }

    if (/^\s*>\s?/.test(line)) {
      const q: string[] = [];
      while (i < lines.length && /^\s*>\s?/.test(lines[i])) {
        q.push(lines[i].replace(/^\s*>\s?/, ""));
        i++;
      }
      blocks.push({ type: "quote", lines: q });
      continue;
    }

    if (/^\s*[-*+]\s+/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\s*[-*+]\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\s*[-*+]\s+/, ""));
        i++;
      }
      blocks.push({ type: "ul", items });
      continue;
    }

    if (/^\s*\d+\.\s+/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\s*\d+\.\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\s*\d+\.\s+/, ""));
        i++;
      }
      blocks.push({ type: "ol", items });
      continue;
    }

    // paragraph: gather until blank line or a block-starting line
    const para: string[] = [];
    while (
      i < lines.length &&
      lines[i].trim() &&
      !isBlockStart(lines[i]) &&
      !isTableStart(lines, i)
    ) {
      para.push(lines[i]);
      i++;
    }
    blocks.push({ type: "p", lines: para });
  }
}

function isBlockStart(line: string): boolean {
  return (
    /^(#{1,6})\s+/.test(line) ||
    /^\s*([-*_])\1{2,}\s*$/.test(line) ||
    /^\s*>\s?/.test(line) ||
    /^\s*[-*+]\s+/.test(line) ||
    /^\s*\d+\.\s+/.test(line)
  );
}

// ---- render -----------------------------------------------------------------

function renderBlock(b: Block, key: string): React.ReactNode {
  switch (b.type) {
    case "code":
      return (
        <div key={key} className="md-code-block">
          {b.lang ? <div className="md-code-lang">{b.lang}</div> : null}
          <pre>
            <code>{b.text}</code>
          </pre>
        </div>
      );
    case "heading": {
      const Tag = `h${Math.min(b.level, 6)}` as keyof React.JSX.IntrinsicElements;
      return (
        <Tag key={key} className={`md-h md-h${b.level}`}>
          {parseInline(b.text)}
        </Tag>
      );
    }
    case "hr":
      return <hr key={key} className="md-hr" />;
    case "quote":
      return (
        <blockquote key={key} className="md-quote">
          {withBreaks(b.lines, key)}
        </blockquote>
      );
    case "ul":
      return (
        <ul key={key} className="md-list">
          {b.items.map((it, j) => (
            <li key={j}>{parseInline(it)}</li>
          ))}
        </ul>
      );
    case "ol":
      return (
        <ol key={key} className="md-list">
          {b.items.map((it, j) => (
            <li key={j}>{parseInline(it)}</li>
          ))}
        </ol>
      );
    case "table":
      return (
        <div key={key} className="md-table-wrap">
          <table className="md-table">
            <thead>
              <tr>
                {b.header.map((h, j) => (
                  <th key={j} style={{ textAlign: b.align[j] ?? undefined }}>
                    {parseInline(h)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {b.rows.map((r, ri) => (
                <tr key={ri}>
                  {b.header.map((_, ci) => (
                    <td key={ci} style={{ textAlign: b.align[ci] ?? undefined }}>
                      {parseInline(r[ci] ?? "")}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
    case "p":
      return (
        <p key={key} className="md-p">
          {withBreaks(b.lines, key)}
        </p>
      );
  }
}

export default function Markdown({ text }: { text: string }) {
  const blocks = React.useMemo(() => parseBlocks(text), [text]);
  return <div className="md">{blocks.map((b, i) => renderBlock(b, `b${i}`))}</div>;
}
