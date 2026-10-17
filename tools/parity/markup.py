"""HTML compared as markup rather than bytes: one line per tag or text run, attributes decoded and
sorted, whitespace collapsed, `<x/>` and `<x>` alike, boolean attributes by presence alone, and the
markup inside `<script type="text/template">` tokenized too. jte's HTML mode and Rails' ERB produce the
same elements with different spelling.
"""
from html.parser import HTMLParser

# Where HTML reads only presence: Rails writes `autofocus="true"`, jte a bare `autofocus`.
BOOLEAN = {'allowfullscreen', 'async', 'autofocus', 'autoplay', 'checked', 'controls', 'default', 'defer', 'disabled',
           'formnovalidate', 'hidden', 'inert', 'ismap', 'itemscope', 'loop', 'multiple', 'muted', 'nomodule', 'novalidate',
           'open', 'playsinline', 'readonly', 'required', 'reversed', 'selected'}


class _Tokens(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.out = []

    def handle_starttag(self, tag, attrs):
        parts = []
        for name, value in sorted(attrs):
            value = '' if value is None or value == name or name in BOOLEAN else ' '.join(value.split())
            parts.append(f' {name}="{value}"' if value else f' {name}')
        self.out.append(f'<{tag}{"".join(parts)}>')
        self.template = tag == 'script' and ('type', 'text/template') in attrs

    handle_startendtag = handle_starttag

    def handle_endtag(self, tag):
        self.out.append(f'</{tag}>')

    def handle_data(self, data):
        if getattr(self, 'template', False) and looks_like_markup(data):
            self.out.extend(tokens(data))
            return
        text = ' '.join(data.split())
        if text:
            self.out.append(text)

    def handle_comment(self, data):
        self.out.append(f'<!--{" ".join(data.split())}-->')

    def handle_decl(self, decl):
        self.out.append(f'<!{decl.lower()}>')


def tokens(html):
    parser = _Tokens()
    parser.feed(html)
    parser.close()
    return parser.out


def looks_like_markup(text):
    return text.lstrip()[:1] == '<'
