/**
 * 极简 Markdown 渲染器（无第三方依赖）。
 *
 * 覆盖模型实际会输出的子集：标题、围栏代码块、行内代码、粗体/斜体/删除线、
 * 有序/无序列表、引用、分割线、表格、链接。
 *
 * 安全：**先对整段文本做 HTML 转义，再往上套标签**，因此模型输出里的
 * HTML/脚本不会被执行。这也是不引 markdown 库反而更省事的原因——
 * 引库还得再配一个 sanitizer。链接只放行 http(s)，其余当纯文本。
 *
 * 局限：不支持嵌套列表、脚注、HTML 块；够聊天用即可。
 */

const ESCAPE_MAP = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;'
}

function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (c) => ESCAPE_MAP[c])
}

/** 行内代码占位符，形如 @@MD_CODE_0@@；正文里几乎不可能正好出现这个串 */
const CODE_SLOT_PREFIX = '@@MD_CODE_'
const CODE_SLOT_PATTERN = /@@MD_CODE_(\d+)@@/g

/**
 * 行内规则。
 * 行内代码先抠出来占位，避免代码里的 `*` `_` 被当成强调符号。
 */
function renderInline(escapedText) {
  const codes = []
  let text = escapedText.replace(/`([^`]+)`/g, (_, code) => {
    codes.push(code)
    return `${CODE_SLOT_PREFIX}${codes.length - 1}@@`
  })

  text = text
    .replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
    .replace(/__([^_\n]+)__/g, '<strong>$1</strong>')
    .replace(/~~([^~\n]+)~~/g, '<del>$1</del>')
    .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
    .replace(/\[([^\]\n]+)\]\(([^)\s]+)\)/g, (whole, label, href) => {
      // 只放行 http(s)，其余（javascript: 等）原样当文本
      if (!/^https?:\/\//i.test(href)) return whole
      return `<a href="${href}" target="_blank" rel="noopener noreferrer">${label}</a>`
    })

  return text.replace(CODE_SLOT_PATTERN, (_, i) => `<code>${codes[Number(i)]}</code>`)
}

/** 表格分隔行，例如 |---|:--:|---| */
function isTableDivider(line) {
  return /^\s*\|?[\s:|-]*-[\s:|-]*\|?\s*$/.test(line) && line.includes('-')
}

function splitTableRow(line) {
  return line
    .replace(/^\s*\|/, '')
    .replace(/\|\s*$/, '')
    .split('|')
    .map((cell) => cell.trim())
}

export function renderMarkdown(source) {
  if (source == null || source === '') return ''
  const text = escapeHtml(String(source).replace(/\r\n?/g, '\n'))
  const lines = text.split('\n')
  const html = []

  let paragraph = []
  let listType = null // 'ul' | 'ol'
  // 当前列表项的内容行。列表项可以跨多行（CommonMark 的 lazy continuation）：
  //   - **标题**
  //   说明文字……
  // 第二行没有缩进，但它属于上面那一项。不这样处理的话，说明文字会掉成独立段落，
  // 与圆点脱节、贴着气泡左边缘，整段看起来就是"挤在一起"。
  let liLines = []

  const flushParagraph = () => {
    if (paragraph.length) {
      html.push(`<p>${paragraph.map(renderInline).join('<br>')}</p>`)
      paragraph = []
    }
  }
  const flushListItem = () => {
    if (liLines.length) {
      html.push(`<li>${liLines.map(renderInline).join('<br>')}</li>`)
      liLines = []
    }
  }
  const closeList = () => {
    flushListItem()
    if (listType) {
      html.push(`</${listType}>`)
      listType = null
    }
  }
  const openList = (type) => {
    if (listType !== type) {
      closeList()
      html.push(`<${type}>`)
      listType = type
    }
  }

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]

    // 围栏代码块：内容原样保留（已转义），不再套行内规则
    const fence = line.match(/^\s*```\s*([\w+-]*)\s*$/)
    if (fence) {
      flushParagraph()
      closeList()
      const lang = fence[1]
      const body = []
      i++
      while (i < lines.length && !/^\s*```\s*$/.test(lines[i])) {
        body.push(lines[i])
        i++
      }
      const langAttr = lang ? ` class="language-${lang}"` : ''
      html.push(`<pre><code${langAttr}>${body.join('\n')}</code></pre>`)
      continue
    }

    // 表格：当前行含 |，且下一行是分隔行
    if (line.includes('|') && i + 1 < lines.length && isTableDivider(lines[i + 1])) {
      flushParagraph()
      closeList()
      const head = splitTableRow(line)
      const rows = []
      i += 2
      while (i < lines.length && lines[i].includes('|') && lines[i].trim() !== '') {
        rows.push(splitTableRow(lines[i]))
        i++
      }
      i--
      html.push(
        '<table><thead><tr>'
        + head.map((c) => `<th>${renderInline(c)}</th>`).join('')
        + '</tr></thead><tbody>'
        + rows.map((r) => `<tr>${r.map((c) => `<td>${renderInline(c)}</td>`).join('')}</tr>`).join('')
        + '</tbody></table>'
      )
      continue
    }

    const heading = line.match(/^(#{1,6})\s+(.*)$/)
    if (heading) {
      flushParagraph()
      closeList()
      const level = heading[1].length
      html.push(`<h${level}>${renderInline(heading[2].trim())}</h${level}>`)
      continue
    }

    if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
      flushParagraph()
      closeList()
      html.push('<hr>')
      continue
    }

    // 引用：'>' 已被转义成 '&gt;'
    const quote = line.match(/^\s*&gt;\s?(.*)$/)
    if (quote) {
      flushParagraph()
      closeList()
      html.push(`<blockquote>${renderInline(quote[1])}</blockquote>`)
      continue
    }

    const ul = line.match(/^\s*[-*+]\s+(.*)$/)
    if (ul) {
      flushParagraph()
      openList('ul')
      flushListItem()
      liLines = [ul[1]]
      continue
    }

    const ol = line.match(/^\s*\d+[.)]\s+(.*)$/)
    if (ol) {
      flushParagraph()
      openList('ol')
      flushListItem()
      liLines = [ol[1]]
      continue
    }

    if (line.trim() === '') {
      flushParagraph()
      closeList()
      continue
    }

    // 列表项内的续行：并进当前项，不要另起段落
    if (liLines.length) {
      liLines.push(line)
      continue
    }

    // 普通段落行：保留模型给的换行，聊天里比合并成一行更好读
    paragraph.push(line)
  }

  flushParagraph()
  closeList()
  return html.join('')
}

export default renderMarkdown
