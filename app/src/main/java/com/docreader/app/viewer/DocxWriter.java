package com.docreader.app.viewer;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * يكتب ملف Word (.docx) من نموذج JSON يصدّره محرّر word.html:
 * فقرات (عناوين، محاذاة، اتجاه RTL، مسافات)، نص منسّق، قوائم نقطية ومرقّمة، روابط، صور، جداول (دمج، تظليل، حدود)،
 * فواصل صفحات، ترويسة/تذييل، ترقيم صفحات، حجم الورقة والهوامش.
 */
public final class DocxWriter {

    private final Map<String, Object> model;
    private final boolean docRtl;
    private final double defSz;
    private final String defFont;
    private final StringBuilder body = new StringBuilder(1 << 16);
    private final List<String[]> rels = new ArrayList<>();     // {id, type, target, external}
    private final List<Object[]> media = new ArrayList<>();    // {name, bytes}
    private final Map<Integer, Integer> olNum = new LinkedHashMap<>();   // lid -> numId
    private final Map<Integer, String> olFmt = new HashMap<>();
    private int nextRel = 10;
    private int nextDocPr = 1;
    private int nextNum = 2;      // 1 = نقاط
    private double contentWidthTw;

    private DocxWriter(Map<String, Object> model) {
        this.model = model;
        this.docRtl = "rtl".equals(MiniJson.str(model, "dir"));
        this.defSz = MiniJson.num(model, "defSz", 11);
        String f = MiniJson.str(model, "defFont");
        this.defFont = (f == null || f.isEmpty()) ? "Calibri" : f;
    }

    public static void write(String json, OutputStream os) throws Exception {
        Map<String, Object> m = MiniJson.obj(MiniJson.parse(json));
        new DocxWriter(m).run(os);
    }

    // ------------------------------------------------------------------ أدوات

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default:
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int tw(double px) {
        return (int) Math.round(px * 15);
    }

    private static String hex(String c) {
        if (c == null) return null;
        String h = c.startsWith("#") ? c.substring(1) : c;
        return h.matches("[0-9A-Fa-f]{6}") ? h.toUpperCase(Locale.ROOT) : null;
    }

    private static boolean hasArabic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x0590 && c <= 0x08FF) || (c >= 0xFB1D && c <= 0xFEFF)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ التشغيل

    private void run(OutputStream os) throws Exception {
        Map<String, Object> pg = MiniJson.obj(model.get("page"));
        String size = MiniJson.str(pg, "size");
        boolean land = MiniJson.flag(pg, "land");
        double marCm = MiniJson.num(pg, "mar", 2.54);
        int pw = 11906, ph = 16838;
        if ("Letter".equals(size)) {
            pw = 12240;
            ph = 15840;
        } else if ("A5".equals(size)) {
            pw = 8391;
            ph = 11906;
        }
        if (land) {
            int t = pw;
            pw = ph;
            ph = t;
        }
        int mar = (int) Math.round(marCm * 567);
        contentWidthTw = pw - 2 * mar;

        for (Object o : MiniJson.arr(model.get("blocks"))) block(MiniJson.obj(o), body, 0);
        // يجب أن ينتهي المتن بفقرة
        if (body.length() == 0 || body.toString().endsWith("</w:tbl>")) body.append("<w:p/>");

        String hdr = MiniJson.str(model, "hdr"), ftr = MiniJson.str(model, "ftr");
        boolean pn = MiniJson.flag(model, "pn");
        boolean hasHdr = hdr != null && !hdr.trim().isEmpty();
        boolean hasFtr = (ftr != null && !ftr.trim().isEmpty()) || pn;

        StringBuilder sect = new StringBuilder("<w:sectPr>");
        if (hasHdr) sect.append("<w:headerReference w:type=\"default\" r:id=\"rIdHdr\"/>");
        if (hasFtr) sect.append("<w:footerReference w:type=\"default\" r:id=\"rIdFtr\"/>");
        sect.append("<w:pgSz w:w=\"").append(pw).append("\" w:h=\"").append(ph).append('"');
        if (land) sect.append(" w:orient=\"landscape\"");
        sect.append("/><w:pgMar w:top=\"").append(mar).append("\" w:right=\"").append(mar).append("\" w:bottom=\"").append(mar)
                .append("\" w:left=\"").append(mar).append("\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>");
        if (docRtl) sect.append("<w:bidi/>");
        sect.append("</w:sectPr>");

        String ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
                + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"";
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<w:document " + ns + "><w:body>" + body + sect + "</w:body></w:document>";

        StringBuilder ct = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/><Default Extension=\"gif\" ContentType=\"image/gif\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>");
        if (hasHdr) ct.append("<Override PartName=\"/word/header1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml\"/>");
        if (hasFtr) ct.append("<Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/>");
        ct.append("</Types>");

        StringBuilder rl = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rIdSt\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "<Relationship Id=\"rIdNum\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/>");
        if (hasHdr) rl.append("<Relationship Id=\"rIdHdr\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/header\" Target=\"header1.xml\"/>");
        if (hasFtr) rl.append("<Relationship Id=\"rIdFtr\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer\" Target=\"footer1.xml\"/>");
        for (String[] r : rels) {
            rl.append("<Relationship Id=\"").append(r[0]).append("\" Type=\"").append(r[1]).append("\" Target=\"").append(esc(r[2])).append('"');
            if ("1".equals(r[3])) rl.append(" TargetMode=\"External\"");
            rl.append("/>");
        }
        rl.append("</Relationships>");

        String rootRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>";

        try (ZipOutputStream zos = new ZipOutputStream(os)) {
            put(zos, "[Content_Types].xml", ct.toString());
            put(zos, "_rels/.rels", rootRels);
            put(zos, "word/document.xml", xml);
            put(zos, "word/_rels/document.xml.rels", rl.toString());
            put(zos, "word/styles.xml", styles());
            put(zos, "word/numbering.xml", numbering());
            if (hasHdr) put(zos, "word/header1.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<w:hdr " + ns + ">"
                    + hfPara(hdr, false) + "</w:hdr>");
            if (hasFtr) put(zos, "word/footer1.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<w:ftr " + ns + ">"
                    + hfPara(ftr == null ? "" : ftr, pn) + "</w:ftr>");
            for (Object[] m : media) {
                zos.putNextEntry(new ZipEntry("word/media/" + m[0]));
                zos.write((byte[]) m[1]);
                zos.closeEntry();
            }
        }
    }

    private String hfPara(String text, boolean pageNo) {
        StringBuilder p = new StringBuilder("<w:p><w:pPr>");
        if (docRtl) p.append("<w:bidi/>");
        p.append("<w:jc w:val=\"center\"/></w:pPr>");
        if (text != null && !text.isEmpty()) {
            p.append("<w:r><w:rPr><w:sz w:val=\"18\"/>").append(hasArabic(text) ? "<w:rtl/>" : "").append("</w:rPr><w:t xml:space=\"preserve\">").append(esc(text)).append("</w:t></w:r>");
        }
        if (pageNo) {
            if (text != null && !text.isEmpty()) p.append("<w:r><w:t xml:space=\"preserve\">  —  </w:t></w:r>");
            p.append("<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>1</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r>");
        }
        return p.append("</w:p>").toString();
    }

    private static void put(ZipOutputStream zos, String name, String data) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    // ------------------------------------------------------------------ الكتل

    private void block(Map<String, Object> b, StringBuilder out, int depth) {
        String t = MiniJson.str(b, "t");
        if ("p".equals(t)) {
            paragraph(b, out);
        } else if ("tbl".equals(t)) {
            if (depth == 0 && out.toString().endsWith("</w:tbl>")) out.append("<w:p/>");
            table(b, out, depth);
        } else if ("pb".equals(t)) {
            out.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
        } else if ("hr".equals(t)) {
            out.append("<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"8\" w:space=\"1\" w:color=\"808080\"/></w:pBdr></w:pPr></w:p>");
        }
    }

    private void paragraph(Map<String, Object> p, StringBuilder out) {
        boolean rtl = "rtl".equals(MiniJson.str(p, "dir"));
        StringBuilder pr = new StringBuilder("<w:pPr>");
        String st = MiniJson.str(p, "st");
        if (st != null) {
            String id = "h1".equals(st) ? "Heading1" : "h2".equals(st) ? "Heading2" : "h3".equals(st) ? "Heading3" : "title".equals(st) ? "Title" : "quote".equals(st) ? "Quote" : null;
            if (id != null) pr.append("<w:pStyle w:val=\"").append(id).append("\"/>");
        }
        if (MiniJson.flag(p, "pbb")) pr.append("<w:pageBreakBefore/>");
        String list = MiniJson.str(p, "list");
        if (list != null) {
            int lvl = Math.max(0, Math.min(8, MiniJson.integer(p, "lvl", 0)));
            int numId = 1;
            if ("ol".equals(list)) {
                int lid = MiniJson.integer(p, "lid", 1);
                Integer id = olNum.get(lid);
                if (id == null) {
                    id = nextNum++;
                    olNum.put(lid, id);
                    String fmt = MiniJson.str(p, "fmt");
                    olFmt.put(id, fmt == null ? "decimal" : fmt);
                }
                numId = id;
            }
            pr.append("<w:numPr><w:ilvl w:val=\"").append(lvl).append("\"/><w:numId w:val=\"").append(numId).append("\"/></w:numPr>");
        }
        if (rtl) pr.append("<w:bidi/>");
        else if (docRtl) pr.append("<w:bidi w:val=\"0\"/>");
        String shd = hex(MiniJson.str(p, "shd"));
        if (shd != null) pr.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(shd).append("\"/>");
        // المسافات
        double mt = MiniJson.num(p, "mt", -1), mb = MiniJson.num(p, "mb", -1), lh = MiniJson.num(p, "lh", 0);
        if (mt >= 0 || mb >= 0 || lh > 0) {
            pr.append("<w:spacing");
            if (mt >= 0) pr.append(" w:before=\"").append(tw(mt)).append('"');
            if (mb >= 0) pr.append(" w:after=\"").append(tw(mb)).append('"');
            if (lh > 0) pr.append(" w:line=\"").append((int) Math.round(240 * lh)).append("\" w:lineRule=\"auto\"");
            pr.append("/>");
        }
        double ind = MiniJson.num(p, "ind", 0);
        if (ind > 0 && list == null) pr.append("<w:ind w:left=\"").append(tw(ind)).append("\"/>");
        String al = MiniJson.str(p, "al");
        if (al != null) {
            String jc = "end".equals(al) ? "right" : "start".equals(al) ? "left" : "both".equals(al) ? "both" : "center";
            pr.append("<w:jc w:val=\"").append(jc).append("\"/>");
        }
        pr.append("</w:pPr>");
        out.append("<w:p>").append(pr);
        for (Object o : MiniJson.arr(p.get("runs"))) run(MiniJson.obj(o), rtl, out);
        out.append("</w:p>");
    }

    private void run(Map<String, Object> r, boolean rtlPara, StringBuilder out) {
        if (MiniJson.flag(r, "pb")) {
            out.append("<w:r><w:br w:type=\"page\"/></w:r>");
            return;
        }
        if (MiniJson.flag(r, "br")) {
            out.append("<w:r><w:br/></w:r>");
            return;
        }
        String img = MiniJson.str(r, "img");
        if (img != null) {
            image(img, MiniJson.num(r, "w", 0), MiniJson.num(r, "h", 0), out);
            return;
        }
        String x = MiniJson.str(r, "x");
        if (x == null || x.isEmpty()) return;
        boolean ar = hasArabic(x);
        StringBuilder rp = new StringBuilder();
        String font = MiniJson.str(r, "f");
        String a = MiniJson.str(r, "a");
        if (a != null) rp.append("<w:rStyle w:val=\"Hyperlink\"/>");
        if (font != null && !font.isEmpty()) {
            String fe = esc(font);
            rp.append("<w:rFonts w:ascii=\"").append(fe).append("\" w:hAnsi=\"").append(fe).append("\" w:cs=\"").append(fe).append("\"/>");
        }
        boolean b = MiniJson.flag(r, "b"), i = MiniJson.flag(r, "i");
        if (b) rp.append("<w:b/>");
        if (b && ar) rp.append("<w:bCs/>");
        if (i) rp.append("<w:i/>");
        if (i && ar) rp.append("<w:iCs/>");
        if (MiniJson.flag(r, "caps")) rp.append("<w:caps/>");
        if (MiniJson.flag(r, "s")) rp.append("<w:strike/>");
        String c = hex(MiniJson.str(r, "c"));
        if (c != null) rp.append("<w:color w:val=\"").append(c).append("\"/>");
        double sz = MiniJson.num(r, "sz", 0);
        if (sz > 0) {
            int hp = (int) Math.round(sz * 2);
            rp.append("<w:sz w:val=\"").append(hp).append("\"/><w:szCs w:val=\"").append(hp).append("\"/>");
        }
        String h = hex(MiniJson.str(r, "h"));
        if (h != null) rp.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(h).append("\"/>");
        if (MiniJson.flag(r, "u") && a == null) rp.append("<w:u w:val=\"single\"/>");
        if (MiniJson.flag(r, "sup")) rp.append("<w:vertAlign w:val=\"superscript\"/>");
        else if (MiniJson.flag(r, "sub")) rp.append("<w:vertAlign w:val=\"subscript\"/>");
        if (ar) rp.append("<w:rtl/>");
        String run = "<w:r>" + (rp.length() > 0 ? "<w:rPr>" + rp + "</w:rPr>" : "") + "<w:t xml:space=\"preserve\">" + esc(x) + "</w:t></w:r>";
        if (a != null) {
            String id = "rId" + (nextRel++);
            rels.add(new String[]{id, "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink", a, "1"});
            out.append("<w:hyperlink r:id=\"").append(id).append("\" w:history=\"1\">").append(run).append("</w:hyperlink>");
        } else {
            out.append(run);
        }
    }

    // ------------------------------------------------------------------ الصور

    private void image(String dataUri, double wpx, double hpx, StringBuilder out) {
        int comma = dataUri.indexOf(',');
        if (!dataUri.startsWith("data:image/") || comma < 0) return;
        String meta = dataUri.substring(5, comma).toLowerCase(Locale.ROOT);
        String ext;
        if (meta.startsWith("image/png")) ext = "png";
        else if (meta.startsWith("image/jpeg") || meta.startsWith("image/jpg")) ext = "jpeg";
        else if (meta.startsWith("image/gif")) ext = "gif";
        else return;
        byte[] data;
        try {
            data = Base64.getMimeDecoder().decode(dataUri.substring(comma + 1));
        } catch (IllegalArgumentException e) {
            return;
        }
        if (data.length == 0) return;
        if (wpx <= 0 || hpx <= 0) {
            int[] d = dims(data, ext);
            wpx = d[0] > 0 ? d[0] : 200;
            hpx = d[1] > 0 ? d[1] : 150;
        }
        double maxW = contentWidthTw / 15.0;
        if (wpx > maxW) {
            hpx = hpx * maxW / wpx;
            wpx = maxW;
        }
        int n = media.size() + 1;
        String name = "image" + n + "." + ext;
        media.add(new Object[]{name, data});
        String id = "rId" + (nextRel++);
        rels.add(new String[]{id, "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image", "media/" + name, "0"});
        long cx = Math.round(wpx * 9525), cy = Math.round(hpx * 9525);
        int pid = nextDocPr++;
        out.append("<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"").append(cx).append("\" cy=\"").append(cy)
                .append("\"/><wp:docPr id=\"").append(pid).append("\" name=\"Picture ").append(pid).append("\"/><wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>")
                .append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr id=\"").append(pid)
                .append("\" name=\"").append(name).append("\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"").append(id)
                .append("\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(cx).append("\" cy=\"").append(cy)
                .append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>");
    }

    private static int[] dims(byte[] d, String ext) {
        try {
            if (ext.equals("png") && d.length > 24) {
                return new int[]{u32(d, 16), u32(d, 20)};
            }
            if (ext.equals("gif") && d.length > 10) {
                return new int[]{(d[6] & 0xff) | ((d[7] & 0xff) << 8), (d[8] & 0xff) | ((d[9] & 0xff) << 8)};
            }
            if (ext.equals("jpeg")) {
                int i = 2;
                while (i + 9 < d.length) {
                    if ((d[i] & 0xff) != 0xFF) {
                        i++;
                        continue;
                    }
                    int mk = d[i + 1] & 0xff;
                    if (mk >= 0xC0 && mk <= 0xCF && mk != 0xC4 && mk != 0xC8 && mk != 0xCC) {
                        return new int[]{((d[i + 7] & 0xff) << 8) | (d[i + 8] & 0xff), ((d[i + 5] & 0xff) << 8) | (d[i + 6] & 0xff)};
                    }
                    i += 2 + (((d[i + 2] & 0xff) << 8) | (d[i + 3] & 0xff));
                }
            }
        } catch (RuntimeException ignored) {
        }
        return new int[]{0, 0};
    }

    private static int u32(byte[] d, int o) {
        return ((d[o] & 0xff) << 24) | ((d[o + 1] & 0xff) << 16) | ((d[o + 2] & 0xff) << 8) | (d[o + 3] & 0xff);
    }

    // ------------------------------------------------------------------ الجداول

    private void table(Map<String, Object> t, StringBuilder out, int depth) {
        List<Object> rows = MiniJson.arr(t.get("rows"));
        if (rows.isEmpty()) return;
        boolean rtl = "rtl".equals(MiniJson.str(t, "dir"));
        boolean border = MiniJson.flag(t, "border");
        String bc = hex(MiniJson.str(t, "bc"));
        if (bc == null) bc = "808080";

        // شبكة الأعمدة مع احتساب rowspan
        int nrows = rows.size();
        List<List<Object[]>> placed = new ArrayList<>();     // لكل صف: {cell, col, span, continuation?, originSpan}
        int maxCols = 0;
        int[][] occGrid = new int[nrows][64];
        Map<Integer, Map<String, Object>> byId = new HashMap<>();
        int idc = 0;
        for (int r = 0; r < nrows; r++) {
            List<Object[]> pl = new ArrayList<>();
            List<Object> cells = MiniJson.arr(rows.get(r));
            int col = 0;
            for (Object co : cells) {
                Map<String, Object> cell = MiniJson.obj(co);
                while (col < 64 && occGrid[r][col] != 0) {
                    // خلية مستمرة من صف سابق
                    Map<String, Object> origin = byId.get(occGrid[r][col]);
                    int span = Math.max(1, MiniJson.integer(origin, "cs", 1));
                    pl.add(new Object[]{origin, col, span, Boolean.TRUE});
                    col += span;
                }
                if (col >= 60) break;
                int cs = Math.max(1, Math.min(60 - col, MiniJson.integer(cell, "cs", 1)));
                int rs = Math.max(1, Math.min(nrows - r, MiniJson.integer(cell, "rs", 1)));
                idc++;
                byId.put(idc, cell);
                for (int rr = r + 1; rr < r + rs; rr++) for (int cc = col; cc < col + cs; cc++) occGrid[rr][cc] = idc;
                pl.add(new Object[]{cell, col, cs, Boolean.FALSE, rs});
                col += cs;
            }
            while (col < 64 && col < 60) {
                if (occGrid[r][col] == 0) {
                    // بقية الأعمدة المشغولة بدمج رأسي
                    boolean any = false;
                    for (int cc = col; cc < 60; cc++) if (occGrid[r][cc] != 0) any = true;
                    if (!any) break;
                    col++;
                    continue;
                }
                Map<String, Object> origin = byId.get(occGrid[r][col]);
                int span = Math.max(1, MiniJson.integer(origin, "cs", 1));
                pl.add(new Object[]{origin, col, span, Boolean.TRUE});
                col += span;
            }
            maxCols = Math.max(maxCols, col);
            placed.add(pl);
        }
        if (maxCols == 0) return;

        // عرض الأعمدة
        double total = Math.min(MiniJson.num(t, "w", 0) * 15, contentWidthTw);
        double[] cw = new double[maxCols];
        for (List<Object[]> pl : placed) {
            for (Object[] pc : pl) {
                if ((Boolean) pc[3]) continue;
                Map<String, Object> cell = (Map<String, Object>) pc[0];
                int c0 = (Integer) pc[1], sp = (Integer) pc[2];
                double w = MiniJson.num(cell, "w", 0) * 15;
                if (sp == 1 && w > 0 && cw[c0] == 0) cw[c0] = w;
            }
        }
        double known = 0;
        int unknown = 0;
        for (double w : cw) {
            if (w > 0) known += w;
            else unknown++;
        }
        double avail = total > 0 ? total : contentWidthTw;
        double fill = unknown > 0 ? Math.max(600, (avail - known) / unknown) : 0;
        double sum = 0;
        for (int i = 0; i < maxCols; i++) {
            if (cw[i] <= 0) cw[i] = fill;
            sum += cw[i];
        }
        double maxW = depth == 0 ? contentWidthTw : Math.max(1500, contentWidthTw / 2);
        if (sum > maxW) {
            double k = maxW / sum;
            for (int i = 0; i < maxCols; i++) cw[i] *= k;
            sum = maxW;
        }

        out.append("<w:tbl><w:tblPr><w:tblW w:w=\"").append((int) Math.round(sum)).append("\" w:type=\"dxa\"/>");
        if (rtl) out.append("<w:bidiVisual/>");
        if (border) {
            out.append("<w:tblBorders>");
            for (String s : new String[]{"top", "left", "bottom", "right", "insideH", "insideV"})
                out.append('<').append("w:").append(s).append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"").append(bc).append("\"/>");
            out.append("</w:tblBorders>");
        }
        out.append("<w:tblLayout w:type=\"fixed\"/><w:tblCellMar><w:left w:w=\"85\" w:type=\"dxa\"/><w:right w:w=\"85\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>");
        for (int i = 0; i < maxCols; i++) out.append("<w:gridCol w:w=\"").append((int) Math.round(cw[i])).append("\"/>");
        out.append("</w:tblGrid>");

        for (List<Object[]> pl : placed) {
            out.append("<w:tr>");
            for (Object[] pc : pl) {
                @SuppressWarnings("unchecked") Map<String, Object> cell = (Map<String, Object>) pc[0];
                int c0 = (Integer) pc[1], sp = (Integer) pc[2];
                boolean cont = (Boolean) pc[3];
                double w = 0;
                for (int i = c0; i < Math.min(maxCols, c0 + sp); i++) w += cw[i];
                out.append("<w:tc><w:tcPr><w:tcW w:w=\"").append((int) Math.round(w)).append("\" w:type=\"dxa\"/>");
                if (sp > 1) out.append("<w:gridSpan w:val=\"").append(sp).append("\"/>");
                int rs = cont ? 1 : (pc.length > 4 ? (Integer) pc[4] : 1);
                if (cont) out.append("<w:vMerge/>");
                else if (rs > 1) out.append("<w:vMerge w:val=\"restart\"/>");
                String bg = hex(MiniJson.str(cell, "bg"));
                if (bg != null) out.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(bg).append("\"/>");
                out.append("</w:tcPr>");
                if (cont) {
                    out.append("<w:p/>");
                } else {
                    StringBuilder cb = new StringBuilder();
                    for (Object bo : MiniJson.arr(cell.get("blocks"))) block(MiniJson.obj(bo), cb, depth + 1);
                    if (cb.length() == 0 || cb.toString().endsWith("</w:tbl>")) cb.append("<w:p/>");
                    out.append(cb);
                }
                out.append("</w:tc>");
            }
            out.append("</w:tr>");
        }
        out.append("</w:tbl>");
    }

    // ------------------------------------------------------------------ الأنماط والترقيم

    private String styles() {
        int sz = (int) Math.round(defSz * 2);
        String f = esc(defFont);
        StringBuilder s = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">");
        s.append("<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"").append(f).append("\" w:hAnsi=\"").append(f).append("\" w:eastAsia=\"").append(f).append("\" w:cs=\"").append(f)
                .append("\"/><w:sz w:val=\"").append(sz).append("\"/><w:szCs w:val=\"").append(sz).append("\"/><w:lang w:val=\"en-US\" w:eastAsia=\"en-US\" w:bidi=\"ar-SA\"/></w:rPr></w:rPrDefault>")
                .append("<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"360\" w:lineRule=\"auto\"/>").append(docRtl ? "<w:bidi/>" : "").append("</w:pPr></w:pPrDefault></w:docDefaults>");
        s.append("<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>");
        s.append("<w:style w:type=\"character\" w:default=\"1\" w:styleId=\"DefaultParagraphFont\"><w:name w:val=\"Default Paragraph Font\"/><w:uiPriority w:val=\"1\"/><w:semiHidden/></w:style>");
        s.append("<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal Table\"/><w:uiPriority w:val=\"99\"/><w:semiHidden/><w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/><w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"108\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>");
        s.append(head("Heading1", "heading 1", 40, 0, true));
        s.append(head("Heading2", "heading 2", 32, 1, true));
        s.append(head("Heading3", "heading 3", 26, 2, true));
        s.append("<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:spacing w:after=\"200\"/></w:pPr><w:rPr><w:sz w:val=\"52\"/><w:szCs w:val=\"52\"/></w:rPr></w:style>");
        s.append("<w:style w:type=\"paragraph\" w:styleId=\"Quote\"><w:name w:val=\"Quote\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:ind w:left=\"720\" w:right=\"720\"/></w:pPr><w:rPr><w:i/><w:iCs/><w:color w:val=\"555555\"/></w:rPr></w:style>");
        s.append("<w:style w:type=\"character\" w:styleId=\"Hyperlink\"><w:name w:val=\"Hyperlink\"/><w:basedOn w:val=\"DefaultParagraphFont\"/><w:uiPriority w:val=\"99\"/><w:unhideWhenUsed/><w:rPr><w:color w:val=\"0563C1\"/><w:u w:val=\"single\"/></w:rPr></w:style>");
        return s.append("</w:styles>").toString();
    }

    private static String head(String id, String name, int sz, int outline, boolean bold) {
        return "<w:style w:type=\"paragraph\" w:styleId=\"" + id + "\"><w:name w:val=\"" + name + "\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>"
                + "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"80\"/><w:outlineLvl w:val=\"" + outline + "\"/></w:pPr>"
                + "<w:rPr>" + (bold ? "<w:b/><w:bCs/>" : "") + "<w:sz w:val=\"" + sz + "\"/><w:szCs w:val=\"" + sz + "\"/></w:rPr></w:style>";
    }

    private static final String[] BULLETS = {"•", "◦", "▪"};

    private String numbering() {
        StringBuilder n = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">");
        // abstractNum 0: نقاط
        n.append("<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"hybridMultilevel\"/>");
        for (int l = 0; l < 9; l++) {
            n.append("<w:lvl w:ilvl=\"").append(l).append("\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"").append(BULLETS[l % 3])
                    .append("\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"").append(720 * (l + 1)).append("\" w:hanging=\"360\"/></w:pPr></w:lvl>");
        }
        n.append("</w:abstractNum>");
        // abstractNum لكل نمط ترقيم مستخدم
        String[] fmts = {"decimal", "lowerLetter", "upperLetter", "lowerRoman", "upperRoman"};
        for (int k = 0; k < fmts.length; k++) {
            n.append("<w:abstractNum w:abstractNumId=\"").append(k + 1).append("\"><w:multiLevelType w:val=\"hybridMultilevel\"/>");
            for (int l = 0; l < 9; l++) {
                String fmt = l == 0 ? fmts[k] : (l % 3 == 1 ? "lowerLetter" : l % 3 == 2 ? "lowerRoman" : "decimal");
                n.append("<w:lvl w:ilvl=\"").append(l).append("\"><w:start w:val=\"1\"/><w:numFmt w:val=\"").append(fmt).append("\"/><w:lvlText w:val=\"%").append(l + 1)
                        .append(".\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"").append(720 * (l + 1)).append("\" w:hanging=\"360\"/></w:pPr></w:lvl>");
            }
            n.append("</w:abstractNum>");
        }
        n.append("<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>");
        for (Map.Entry<Integer, Integer> e : olNum.entrySet()) {
            int numId = e.getValue();
            String fmt = olFmt.get(numId);
            int abs = 1;
            for (int k = 0; k < fmts.length; k++) if (fmts[k].equals(fmt)) abs = k + 1;
            n.append("<w:num w:numId=\"").append(numId).append("\"><w:abstractNumId w:val=\"").append(abs).append("\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/></w:lvlOverride></w:num>");
        }
        return n.append("</w:numbering>").toString();
    }
}
