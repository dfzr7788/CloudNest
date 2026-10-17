import { Marked } from "marked";
import DOMPurify from "dompurify";
import renderMath from "katex/contrib/auto-render";
import "katex/dist/katex.min.css";
import hljs from "highlight.js/lib/core";
import java from "highlight.js/lib/languages/java";
import cpp from "highlight.js/lib/languages/cpp";
import python from "highlight.js/lib/languages/python";
import javascript from "highlight.js/lib/languages/javascript";
import typescript from "highlight.js/lib/languages/typescript";
import json from "highlight.js/lib/languages/json";
import bash from "highlight.js/lib/languages/bash";
import sql from "highlight.js/lib/languages/sql";
import "highlight.js/styles/github.css";
for (const [name, language] of Object.entries({
  java,
  cpp,
  python,
  javascript,
  typescript,
  json,
  bash,
  sql,
}))
  hljs.registerLanguage(name, language);

export function renderMarkdown(source: string, attachment: string) {
  const parser = new Marked({ gfm: true });
  const relative = (href: string) =>
    !/^(?:[a-z][a-z0-9+.-]*:|\/|#)/i.test(href);
  const asset = (href: string) => {
    try {
      return attachment + encodeURIComponent(decodeURIComponent(href));
    } catch {
      return attachment + encodeURIComponent(href);
    }
  };
  parser.use({
    renderer: {
      code({ text, lang }) {
        const language = (lang || "").split(/\s/)[0]!;
        const code =
          language && hljs.getLanguage(language)
            ? hljs.highlight(text, { language }).value
            : text
                .replace(/&/g, "&amp;")
                .replace(/</g, "&lt;")
                .replace(/>/g, "&gt;");
        return `<pre><code class="hljs">${code}</code></pre>`;
      },
      image({ href, text }) {
        if (!relative(href))
          return `<span>［外部图片：${text.replace(/[<>&]/g, "")}］</span>`;
        return `<img alt="" src="${asset(href)}">`;
      },
      link({ href, tokens }) {
        const safe = relative(href) ? asset(href) : href;
        return `<a href="${safe.replace(/&/g, "&amp;").replace(/"/g, "&quot;")}" target="_blank" rel="noopener noreferrer">${this.parser.parseInline(tokens)}</a>`;
      },
    },
  });
  const host = document.createElement("article");
  host.innerHTML = DOMPurify.sanitize(parser.parse(source, { async: false }), {
    ALLOWED_TAGS: [
      "p",
      "br",
      "hr",
      "h1",
      "h2",
      "h3",
      "h4",
      "h5",
      "h6",
      "strong",
      "em",
      "del",
      "blockquote",
      "ul",
      "ol",
      "li",
      "pre",
      "code",
      "table",
      "thead",
      "tbody",
      "tr",
      "th",
      "td",
      "a",
      "span",
      "img",
    ],
    ALLOWED_ATTR: [
      "href",
      "title",
      "start",
      "align",
      "class",
      "src",
      "alt",
      "target",
      "rel",
    ],
  });
  for (const image of host.querySelectorAll("img"))
    if (!image.getAttribute("src")?.startsWith(attachment)) image.remove();
  // Only trusted KaTeX output is inserted after sanitizing user HTML. No trust-enabled commands.
  renderMath(host, {
    delimiters: [
      { left: "$$", right: "$$", display: true },
      { left: "$", right: "$", display: false },
      { left: "\\[", right: "\\]", display: true },
      { left: "\\(", right: "\\)", display: false },
    ],
    throwOnError: false,
    trust: false,
    maxExpand: 500,
    maxSize: 20,
    output: "html",
  });
  return host.innerHTML;
}
