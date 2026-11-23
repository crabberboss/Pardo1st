import io
import unittest
import zipfile
from epub_import import extract_epub


class EpubImportTests(unittest.TestCase):
    def test_spine_order_and_script_removal(self):
        target = io.BytesIO()
        with zipfile.ZipFile(target, 'w') as book:
            book.writestr('META-INF/container.xml', '<container><rootfiles><rootfile full-path="OEBPS/book.opf"/></rootfiles></container>')
            book.writestr('OEBPS/book.opf', '<package><manifest><item id="b" href="b.xhtml"/><item id="a" href="a.xhtml"/></manifest><spine><itemref idref="a"/><itemref idref="b"/></spine></package>')
            book.writestr('OEBPS/a.xhtml', '<html><body><h1>开端</h1><p>第一段。</p><script>不能出现</script></body></html>')
            book.writestr('OEBPS/b.xhtml', '<html><body><p>后续情节。</p></body></html>')
        result = extract_epub(target.getvalue())
        self.assertLess(result.index('第一段'), result.index('后续情节'))
        self.assertNotIn('不能出现', result)
        self.assertIn('第2章', result)


if __name__ == '__main__':
    unittest.main()
