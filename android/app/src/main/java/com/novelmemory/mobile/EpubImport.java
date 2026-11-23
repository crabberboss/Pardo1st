package com.novelmemory.mobile;

import android.text.Html;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import java.io.StringReader;

final class EpubImport {
    private static Document xml(String data) throws Exception {
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(data)));
    }
    static String extract(InputStream input) throws Exception {
        Map<String,byte[]> files=new HashMap<>();long total=0;
        try(ZipInputStream zip=new ZipInputStream(input)){
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                String name=entry.getName();if(entry.isDirectory()||name.startsWith("/")||name.contains(".."))continue;
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[32768];int count;
                while((count=zip.read(block))!=-1){total+=count;if(total>120_000_000)throw new IllegalArgumentException("EPUB 解压内容超过上限");bytes.write(block,0,count);}
                files.put(name,bytes.toByteArray());
            }
        }
        byte[] container=files.get("META-INF/container.xml");if(container==null)throw new IllegalArgumentException("EPUB 缺少目录");
        NodeList roots=xml(new String(container,StandardCharsets.UTF_8)).getElementsByTagNameNS("*","rootfile");
        if(roots.getLength()==0)throw new IllegalArgumentException("EPUB 缺少书籍目录");
        String opfPath=((Element)roots.item(0)).getAttribute("full-path");
        byte[] opfBytes=files.get(opfPath);if(opfBytes==null)throw new IllegalArgumentException("EPUB 缺少 OPF 文件");
        Document opf=xml(new String(opfBytes,StandardCharsets.UTF_8));
        String base=opfPath.contains("/")?opfPath.substring(0,opfPath.lastIndexOf('/')+1):"";
        Map<String,String> manifest=new HashMap<>();NodeList items=opf.getElementsByTagNameNS("*","item");
        for(int i=0;i<items.getLength();i++){Element item=(Element)items.item(i);manifest.put(item.getAttribute("id"),item.getAttribute("href"));}
        StringBuilder text=new StringBuilder();NodeList spine=opf.getElementsByTagNameNS("*","itemref");int chapter=0;
        for(int i=0;i<spine.getLength();i++){
            String href=manifest.get(((Element)spine.item(i)).getAttribute("idref"));if(href==null)continue;
            String path=java.net.URI.create(base+href.split("#",2)[0]).normalize().getPath();byte[] html=files.get(path);if(html==null)continue;
            String body=Html.fromHtml(new String(html,StandardCharsets.UTF_8),Html.FROM_HTML_MODE_LEGACY).toString().trim();
            if(body.isEmpty())continue;
            text.append("\n\n第").append(++chapter).append("章\n").append(body);
            if(text.length()>30_000_000)throw new IllegalArgumentException("EPUB 正文超过导入上限");
        }
        if(chapter==0)throw new IllegalArgumentException("EPUB 未找到可阅读章节");
        return text.toString();
    }
}
