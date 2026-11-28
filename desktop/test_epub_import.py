import io
import unittest
import zipfile
from epub_import import extract_epub, extract_epub_images


class EpubImportTests(unittest.TestCase):
    def test_extracts_manifest_images_without_inlining_them_in_text(self):
        target = io.BytesIO()
        with zipfile.ZipFile(target, 'w') as book:
            book.writestr('META-INF/container.xml', '<container><rootfile full-path="OPS/book.opf"/></container>')
            book.writestr('OPS/book.opf', '<package><manifest><item id="c" href="c.xhtml"/><item id="i" href="pic.png" media-type="image/png"/></manifest><spine><itemref idref="c"/></spine></package>')
            book.writestr('OPS/c.xhtml', '<html><body><p>文字</p><img src="pic.png"/></body></html>')
            book.writestr('OPS/pic.png', b'png-test')
        images = extract_epub_images(target.getvalue())
        self.assertEqual(len(images), 1)
        self.assertEqual(images[0]['content'], b'png-test')
        self.assertNotIn('png-test', extract_epub(target.getvalue()))

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
