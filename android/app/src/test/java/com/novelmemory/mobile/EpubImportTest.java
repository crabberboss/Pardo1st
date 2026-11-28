package com.novelmemory.mobile;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class EpubImportTest {
    private static void entry(ZipOutputStream zip,String path,String text) throws Exception {
        zip.putNextEntry(new ZipEntry(path));zip.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
    }
    @Test public void extractsSpineInOrder() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)){
            entry(zip,"META-INF/container.xml","<container><rootfile full-path='OPS/book.opf'/></container>");
            entry(zip,"OPS/book.opf","<package><manifest><item id='b' href='b.xhtml'/><item id='a' href='a.xhtml'/></manifest><spine><itemref idref='a'/><itemref idref='b'/></spine></package>");
            entry(zip,"OPS/a.xhtml","<html><body><p>开端。</p></body></html>");
            entry(zip,"OPS/b.xhtml","<html><body><p>结尾。</p></body></html>");
            entry(zip,"OPS/pic.png","image-bytes");
        }
        String text=EpubImport.extract(new ByteArrayInputStream(bytes.toByteArray()));
        assertTrue(text.indexOf("开端")<text.indexOf("结尾"));
        assertTrue(text.contains("第2章"));
        assertEquals(1,EpubImport.extractImages(new ByteArrayInputStream(bytes.toByteArray())).size());
    }
}
