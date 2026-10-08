import { describe, expect, it } from 'vitest'
import { renderMarkdown } from './markdown'

describe('renderMarkdown', () => {
  it('renders common markdown blocks and preserves line breaks', () => {
    const html = renderMarkdown('# Title\n\n- one\n- two\n\n```java\nreturn true;\n```')

    expect(html).toContain('<h1>Title</h1>')
    expect(html).toContain('<ul><li>one</li><li>two</li></ul>')
    expect(html).toContain('<pre><code class="language-java">return true;</code></pre>')
  })

  it('escapes HTML and rejects unsafe links', () => {
    const html = renderMarkdown('<script>alert(1)</script> [bad](javascript:alert(1)) [ok](https://example.com)')

    expect(html).toContain('&lt;script&gt;alert(1)&lt;/script&gt;')
    expect(html).not.toContain('<script>')
    expect(html).toContain('[bad](javascript:alert(1))')
    expect(html).toContain('<a href="https://example.com"')
  })
})
