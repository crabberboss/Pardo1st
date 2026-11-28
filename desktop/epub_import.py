"""Extract reflowable EPUB spine text without executing book HTML."""
import io
import mimetypes
import posixpath
import zipfile
import xml.etree.ElementTree as ET
from html.parser import HTMLParser


class _Text(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []
        self.skip = 0

    def handle_starttag(self, tag, attrs):
        if tag in {"script", "style", "svg"}:
            self.skip += 1
        elif not self.skip and tag in {"p", "div", "br", "h1", "h2", "h3", "li"}:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag in {"script", "style", "svg"} and self.skip:
            self.skip -= 1
        elif not self.skip and tag in {"p", "div", "h1", "h2", "h3", "li"}:
            self.parts.append("\n")

    def handle_data(self, data):
        if not self.skip:
            self.parts.append(data)


def extract_epub(raw: bytes, max_chars: int = 30_000_000) -> str:
    if len(raw) > 100_000_000:
        raise ValueError("EPUB 文件超过 100 MB")
    try:
        book = zipfile.ZipFile(io.BytesIO(raw))
        container = ET.fromstring(book.read("META-INF/container.xml"))
        rootfile = next(node.attrib["full-path"] for node in container.iter() if node.tag.endswith("rootfile"))
        opf = ET.fromstring(book.read(rootfile))
        manifest = {node.attrib["id"]: node.attrib["href"] for node in opf.iter() if node.tag.endswith("item") and "id" in node.attrib}
        spine = [node.attrib["idref"] for node in opf.iter() if node.tag.endswith("itemref") and "idref" in node.attrib]
        base = posixpath.dirname(rootfile)
        sections = []
        total = 0
        for chapter_no, item_id in enumerate(spine, 1):
            href = manifest.get(item_id, "").split("#", 1)[0]
            path = posixpath.normpath(posixpath.join(base, href))
            if not href or path.startswith("../") or path not in book.namelist():
                continue
            info = book.getinfo(path)
            if info.file_size > 8_000_000 or total + info.file_size > max_chars * 4:
                raise ValueError("EPUB 章节过大")
            parser = _Text()
            parser.feed(book.read(path).decode("utf-8-sig", errors="replace"))
            body = "\n".join(line.strip() for line in "".join(parser.parts).splitlines() if line.strip())
            if body:
                sections.append(f"第{chapter_no}章\n{body}")
                total += len(body)
                if total > max_chars:
                    raise ValueError("EPUB 正文超过导入上限")
        if not sections:
            raise ValueError("EPUB 未找到可阅读的正文")
        return "\n\n".join(sections)
    except (zipfile.BadZipFile, KeyError, ET.ParseError, StopIteration) as exc:
        raise ValueError("EPUB 结构无效或缺少章节目录") from exc


def extract_epub_images(raw: bytes) -> list[dict]:
    """Return supported manifest images without evaluating book HTML."""
    with zipfile.ZipFile(io.BytesIO(raw)) as book:
        container = ET.fromstring(book.read("META-INF/container.xml"))
        rootfile = next(node.attrib["full-path"] for node in container.iter() if node.tag.endswith("rootfile"))
        opf = ET.fromstring(book.read(rootfile))
        base = posixpath.dirname(rootfile)
        images = []
        total = 0
        for item in opf.iter():
            if not item.tag.endswith("item"):
                continue
            href = item.attrib.get("href", "").split("#", 1)[0]
            path = posixpath.normpath(posixpath.join(base, href))
            mime = item.attrib.get("media-type") or mimetypes.guess_type(path)[0] or ""
            if mime not in {"image/jpeg", "image/png", "image/gif", "image/webp"}:
                continue
            if not href or path.startswith("../") or path not in book.namelist():
                continue
            info = book.getinfo(path)
            if info.file_size > 12_000_000 or total + info.file_size > 80_000_000:
                continue
            content = book.read(path)
            total += len(content)
            images.append({"filename": posixpath.basename(path), "mime": mime, "content": content})
        return images
