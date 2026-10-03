package com.docreader.app.viewer;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * نموذج مصنّف Excel للمحرّر: قراءة .xlsx إلى JSON (يفهمه excel.html) وكتابة JSON إلى .xlsx.
 * الصيغ تُخزَّن مع قيمتها المحسوبة. ما لا يدعمه النموذج (مخططات، تنسيق شرطي، صور...) لا يُحفظ.
 */
public final class XlsxModel {

    private XlsxModel() {
    }

    private static final int MAX_XML = 40 * 1024 * 1024;
    private static final int MAX_ROW = 1048576, MAX_COL = 16384;

    private static final String[] INDEXED = {
            "000000", "FFFFFF", "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF",
            "000000", "FFFFFF", "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF",
            "800000", "008000", "000080", "808000", "800080", "008080", "C0C0C0", "808080",
            "9999FF", "993366", "FFFFCC", "CCFFFF", "660066", "FF8080", "0066CC", "CCCCFF",
            "000080", "FF00FF", "FFFF00", "00FFFF", "800080", "800000", "008080", "0000FF",
            "00CCFF", "CCFFFF", "CCFFCC", "FFFF99", "99CCFF", "FF99CC", "CC99FF", "FFCC99",
            "3366FF", "33CCCC", "99CC00", "FFCC00", "FF9900", "FF6600", "666699", "969696",
            "003366", "339966", "003300", "333300", "993300", "993366", "333399", "333333"};

    private static final Map<Integer, String> BUILTIN_NF = new HashMap<>();

    static {
        BUILTIN_NF.put(1, "0");
        BUILTIN_NF.put(2, "0.00");
        BUILTIN_NF.put(3, "#,##0");
        BUILTIN_NF.put(4, "#,##0.00");
        BUILTIN_NF.put(9, "0%");
        BUILTIN_NF.put(10, "0.00%");
        BUILTIN_NF.put(11, "0.00E+00");
        BUILTIN_NF.put(14, "mm-dd-yy");
        BUILTIN_NF.put(15, "d-mmm-yy");
        BUILTIN_NF.put(16, "d-mmm");
        BUILTIN_NF.put(17, "mmm-yy");
        BUILTIN_NF.put(18, "h:mm AM/PM");
        BUILTIN_NF.put(19, "h:mm:ss AM/PM");
        BUILTIN_NF.put(20, "h:mm");
        BUILTIN_NF.put(21, "h:mm:ss");
        BUILTIN_NF.put(22, "m/d/yy h:mm");
        BUILTIN_NF.put(37, "#,##0 ;(#,##0)");
        BUILTIN_NF.put(38, "#,##0 ;[Red](#,##0)");
        BUILTIN_NF.put(39, "#,##0.00;(#,##0.00)");
        BUILTIN_NF.put(40, "#,##0.00;[Red](#,##0.00)");
        BUILTIN_NF.put(45, "mm:ss");
        BUILTIN_NF.put(46, "[h]:mm:ss");
        BUILTIN_NF.put(47, "mmss.0");
        BUILTIN_NF.put(48, "##0.0E+0");
        BUILTIN_NF.put(49, "@");
    }

    // ===================================================================== القراءة

    public static String read(File file) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            return new Reader(zip).run();
        }
    }

    /** مصنّف فارغ بورقة واحدة. */
    public static String empty() {
        return "{\"d1904\":false,\"styles\":[{}],\"sheets\":[{\"name\":\"Sheet1\",\"cells\":[]}]}";
    }

    private static final class Reader {
        final ZipFile zip;
        final String[] theme = {"FFFFFF", "000000", "E7E6E6", "44546A", "4472C4", "ED7D31", "A5A5A5", "FFC000", "5B9BD5", "70AD47", "0563C1", "954F72"};
        String[] palette = INDEXED;
        final Map<Integer, String> numFmts = new HashMap<>();
        final List<String> sst = new ArrayList<>();
        final List<String> styles = new ArrayList<>();   // JSON لكل xf
        String mainPath;

        Reader(ZipFile zip) {
            this.zip = zip;
        }

        String run() throws Exception {
            Map<String, OoxmlUtil.Rel> root = OoxmlUtil.readRels(zip, "");
            for (OoxmlUtil.Rel r : root.values()) {
                if ("officeDocument".equals(OoxmlUtil.relType(r)) && r.target != null && !r.external) {
                    mainPath = OoxmlUtil.resolve("", r.target);
                    break;
                }
            }
            if (mainPath == null || !OoxmlUtil.has(zip, mainPath)) mainPath = "xl/workbook.xml";
            Document wbDoc = OoxmlUtil.parsePart(zip, mainPath, MAX_XML);
            if (wbDoc == null) throw new IllegalStateException("workbook.xml missing");
            Element wb = wbDoc.getDocumentElement();
            String dir = OoxmlUtil.dirOf(mainPath);
            Map<String, OoxmlUtil.Rel> rels = OoxmlUtil.readRels(zip, mainPath);
            String stylesPath = null, themePath = null, sstPath = null;
            for (OoxmlUtil.Rel r : rels.values()) {
                if (r.external || r.target == null) continue;
                String t = OoxmlUtil.relType(r);
                String p = OoxmlUtil.resolve(dir, r.target);
                if (t.equals("styles")) stylesPath = p;
                else if (t.equals("theme")) themePath = p;
                else if (t.equals("sharedStrings")) sstPath = p;
            }
            if (themePath != null) parseTheme(themePath);
            if (sstPath != null) parseShared(sstPath);
            if (stylesPath != null) parseStyles(stylesPath);
            if (styles.isEmpty()) styles.add("{}");

            boolean d1904 = false;
            Element pr = OoxmlUtil.child(wb, "workbookPr");
            if (pr != null) {
                String v = OoxmlUtil.attr(pr, "date1904");
                d1904 = "1".equals(v) || "true".equalsIgnoreCase(v);
            }
            StringBuilder out = new StringBuilder(1 << 16);
            out.append("{\"d1904\":").append(d1904).append(",\"styles\":[");
            for (int i = 0; i < styles.size(); i++) {
                if (i > 0) out.append(',');
                out.append(styles.get(i));
            }
            out.append("],\"sheets\":[");
            Element sheetsEl = OoxmlUtil.child(wb, "sheets");
            int n = 0;
            if (sheetsEl != null) {
                for (Element s : OoxmlUtil.childEls(sheetsEl, "sheet")) {
                    OoxmlUtil.Rel r = rels.get(OoxmlUtil.attr(s, "id"));
                    if (r == null || r.target == null) continue;
                    String type = OoxmlUtil.relType(r);
                    if (type.equals("chartsheet") || type.equals("dialogsheet")) continue;
                    String name = OoxmlUtil.attr(s, "name");
                    if (name == null) name = "Sheet" + (n + 1);
                    String path = OoxmlUtil.resolve(dir, r.target);
                    if (n > 0) out.append(',');
                    readSheet(out, name, path);
                    n++;
                }
            }
            if (n == 0) out.append("{\"name\":\"Sheet1\",\"cells\":[]}");
            out.append("]}");
            return out.toString();
        }

        // ---------------- الألوان

        void parseTheme(String path) {
            Document d = OoxmlUtil.parsePart(zip, path, MAX_XML);
            if (d == null) return;
            Element scheme = OoxmlUtil.descendant(d.getDocumentElement(), "clrScheme");
            if (scheme == null) return;
            Map<String, String> m = new HashMap<>();
            for (Element c : OoxmlUtil.childEls(scheme)) {
                String name = OoxmlUtil.local(c);
                for (Element x : OoxmlUtil.childEls(c)) {
                    String v = OoxmlUtil.attr(x, "val");
                    if (OoxmlUtil.local(x).equals("sysClr")) v = OoxmlUtil.attr(x, "lastClr");
                    if (v != null && v.matches("[0-9A-Fa-f]{6}")) m.put(name, v.toUpperCase(Locale.ROOT));
                    break;
                }
            }
            String[] order = {"lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink"};
            for (int i = 0; i < order.length; i++) {
                String v = m.get(order[i]);
                if (v != null) theme[i] = v;
            }
        }

        /** يعيد "#RRGGBB" أو null. */
        String color(Element e) {
            if (e == null) return null;
            String base = null;
            String rgb = OoxmlUtil.attr(e, "rgb");
            if (rgb != null) {
                String h = rgb.length() == 8 ? rgb.substring(2) : rgb;
                if (h.matches("[0-9A-Fa-f]{6}")) base = "#" + h.toUpperCase(Locale.ROOT);
            } else if (OoxmlUtil.attr(e, "theme") != null) {
                Integer t = OoxmlUtil.intAttr(e, "theme");
                if (t != null && t >= 0 && t < theme.length) base = "#" + theme[t];
            } else if (OoxmlUtil.attr(e, "indexed") != null) {
                Integer ix = OoxmlUtil.intAttr(e, "indexed");
                if (ix != null && ix >= 0 && ix < palette.length && palette[ix] != null) base = "#" + palette[ix].replace("#", "");
                else if (ix != null && (ix == 64)) base = "#000000";
            }
            String tint = OoxmlUtil.attr(e, "tint");
            if (base != null && tint != null) {
                try {
                    double tv = Double.parseDouble(tint);
                    if (tv != 0) base = OoxmlUtil.excelTint(base, tv);
                } catch (NumberFormatException ignore) {
                }
            }
            return base;
        }

        // ---------------- النصوص المشتركة والأنماط

        void parseShared(String path) {
            Document d = OoxmlUtil.parsePart(zip, path, MAX_XML);
            if (d == null) return;
            for (Element si : OoxmlUtil.childEls(d.getDocumentElement(), "si")) sst.add(siText(si));
        }

        String siText(Element si) {
            StringBuilder sb = new StringBuilder();
            for (Element c : OoxmlUtil.childEls(si)) {
                String ln = OoxmlUtil.local(c);
                if (ln.equals("t")) sb.append(c.getTextContent());
                else if (ln.equals("r")) {
                    Element t = OoxmlUtil.child(c, "t");
                    if (t != null) sb.append(t.getTextContent());
                }
            }
            return sb.toString();
        }

        void parseStyles(String path) {
            Document d = OoxmlUtil.parsePart(zip, path, MAX_XML);
            if (d == null) return;
            Element root = d.getDocumentElement();
            Element colors = OoxmlUtil.child(root, "colors");
            if (colors != null) {
                Element ind = OoxmlUtil.child(colors, "indexedColors");
                if (ind != null) {
                    List<Element> l = OoxmlUtil.childEls(ind, "rgbColor");
                    String[] p = new String[Math.max(l.size(), INDEXED.length)];
                    System.arraycopy(INDEXED, 0, p, 0, INDEXED.length);
                    for (int i = 0; i < l.size(); i++) {
                        String v = OoxmlUtil.attr(l.get(i), "rgb");
                        if (v != null) p[i] = v.length() == 8 ? v.substring(2) : v;
                    }
                    palette = p;
                }
            }
            Element nf = OoxmlUtil.child(root, "numFmts");
            if (nf != null) {
                for (Element e : OoxmlUtil.childEls(nf, "numFmt")) {
                    Integer id = OoxmlUtil.intAttr(e, "numFmtId");
                    String code = OoxmlUtil.attr(e, "formatCode");
                    if (id != null && code != null) numFmts.put(id, code);
                }
            }
            List<Element> fonts = new ArrayList<>();
            Element fe = OoxmlUtil.child(root, "fonts");
            if (fe != null) fonts.addAll(OoxmlUtil.childEls(fe, "font"));
            String defName = null;
            double defSz = 11;
            if (!fonts.isEmpty()) {
                Element f0 = fonts.get(0);
                Element nm = OoxmlUtil.child(f0, "name");
                if (nm != null) defName = OoxmlUtil.attr(nm, "val");
                Double sz = OoxmlUtil.dblAttr(OoxmlUtil.child(f0, "sz"), "val");
                if (sz != null) defSz = sz;
            }
            List<String> fills = new ArrayList<>();
            Element fl = OoxmlUtil.child(root, "fills");
            if (fl != null) {
                for (Element f : OoxmlUtil.childEls(fl, "fill")) {
                    String c = null;
                    Element pf = OoxmlUtil.child(f, "patternFill");
                    if (pf != null) {
                        String pt = OoxmlUtil.attr(pf, "patternType");
                        if ("solid".equals(pt)) {
                            c = color(OoxmlUtil.child(pf, "fgColor"));
                        } else if (pt != null && !pt.equals("none")) {
                            c = color(OoxmlUtil.child(pf, "fgColor"));
                            String bgc = color(OoxmlUtil.child(pf, "bgColor"));
                            if (c != null && bgc != null) c = OoxmlUtil.mix(c, bgc, 0.5);
                        }
                    }
                    fills.add(c);
                }
            }
            List<String> borders = new ArrayList<>();
            Element bl = OoxmlUtil.child(root, "borders");
            if (bl != null) {
                for (Element b : OoxmlUtil.childEls(bl, "border")) {
                    StringBuilder sb = new StringBuilder();
                    String[][] sides = {{"t", "top"}, {"b", "bottom"}, {"l", "left"}, {"r", "right"}};
                    for (String[] sd : sides) {
                        Element e = OoxmlUtil.child(b, sd[1]);
                        if (e == null && sd[0].equals("l")) e = OoxmlUtil.child(b, "start");
                        if (e == null && sd[0].equals("r")) e = OoxmlUtil.child(b, "end");
                        if (e == null) continue;
                        String st = OoxmlUtil.attr(e, "style");
                        if (st == null || st.isEmpty() || st.equals("none")) continue;
                        String c = color(OoxmlUtil.child(e, "color"));
                        if (sb.length() > 0) sb.append(',');
                        sb.append('"').append(sd[0]).append("\":{\"s\":").append(q(st)).append(",\"c\":").append(q(c == null ? "#000000" : c)).append('}');
                    }
                    borders.add(sb.length() == 0 ? null : "{" + sb + "}");
                }
            }
            Element cx = OoxmlUtil.child(root, "cellXfs");
            if (cx != null) {
                for (Element x : OoxmlUtil.childEls(cx, "xf")) {
                    StringBuilder sb = new StringBuilder();
                    Integer nid = OoxmlUtil.intAttr(x, "numFmtId");
                    if (nid != null && nid != 0) {
                        String code = numFmts.get(nid);
                        if (code == null) code = BUILTIN_NF.get(nid);
                        if (code != null && !code.equalsIgnoreCase("General")) add(sb, "\"nf\":" + q(code));
                    }
                    Integer fid = OoxmlUtil.intAttr(x, "fontId");
                    if (fid != null && fid >= 0 && fid < fonts.size()) {
                        Element f = fonts.get(fid);
                        if (on(OoxmlUtil.child(f, "b"))) add(sb, "\"b\":true");
                        if (on(OoxmlUtil.child(f, "i"))) add(sb, "\"i\":true");
                        Element u = OoxmlUtil.child(f, "u");
                        if (u != null && !"none".equals(OoxmlUtil.attr(u, "val"))) add(sb, "\"u\":true");
                        if (on(OoxmlUtil.child(f, "strike"))) add(sb, "\"s\":true");
                        Double sz = OoxmlUtil.dblAttr(OoxmlUtil.child(f, "sz"), "val");
                        if (sz != null && Math.abs(sz - defSz) > 0.01) add(sb, "\"sz\":" + trim(sz));
                        Element nm = OoxmlUtil.child(f, "name");
                        String fname = nm == null ? null : OoxmlUtil.attr(nm, "val");
                        if (fname != null && !fname.equals(defName)) add(sb, "\"fn\":" + q(fname));
                        String fc = color(OoxmlUtil.child(f, "color"));
                        if (fc != null && !fc.equals("#000000")) add(sb, "\"fc\":" + q(fc));
                    }
                    Integer fl2 = OoxmlUtil.intAttr(x, "fillId");
                    if (fl2 != null && fl2 >= 0 && fl2 < fills.size() && fills.get(fl2) != null) add(sb, "\"bg\":" + q(fills.get(fl2)));
                    Integer bi = OoxmlUtil.intAttr(x, "borderId");
                    if (bi != null && bi >= 0 && bi < borders.size() && borders.get(bi) != null) add(sb, "\"bd\":" + borders.get(bi));
                    Element al = OoxmlUtil.child(x, "alignment");
                    if (al != null) {
                        String h = OoxmlUtil.attr(al, "horizontal");
                        if (h != null && !h.equals("general")) {
                            if (h.equals("centerContinuous") || h.equals("distributed")) h = "center";
                            else if (h.equals("fill")) h = "left";
                            add(sb, "\"h\":" + q(h));
                        }
                        String v = OoxmlUtil.attr(al, "vertical");
                        if (v != null && !v.equals("bottom")) add(sb, "\"v\":" + q(v.equals("distributed") || v.equals("justify") ? "center" : v));
                        String w = OoxmlUtil.attr(al, "wrapText");
                        if ("1".equals(w) || "true".equalsIgnoreCase(w)) add(sb, "\"wrap\":true");
                    }
                    styles.add("{" + sb + "}");
                }
            }
        }

        // ---------------- الورقة

        void readSheet(StringBuilder out, String name, String path) {
            Document d = OoxmlUtil.parsePart(zip, path, MAX_XML);
            out.append("{\"name\":").append(q(name));
            if (d == null) {
                out.append(",\"cells\":[]}");
                return;
            }
            Element root = d.getDocumentElement();
            StringBuilder colW = new StringBuilder(), rowH = new StringBuilder(), hiddenRows = new StringBuilder(), hiddenCols = new StringBuilder();
            double defColW = 64, defRowH = 20;
            Element fp = OoxmlUtil.child(root, "sheetFormatPr");
            if (fp != null) {
                Double dh = OoxmlUtil.dblAttr(fp, "defaultRowHeight");
                if (dh != null && dh > 0) defRowH = Math.round(dh * 4 / 3.0);
                Double dw = OoxmlUtil.dblAttr(fp, "defaultColWidth");
                if (dw != null && dw > 0) defColW = Math.round(dw * 7 + 5);
                else {
                    Double bw = OoxmlUtil.dblAttr(fp, "baseColWidth");
                    if (bw != null && bw > 0) defColW = Math.round(bw * 7 + 5);
                }
            }
            Element cols = OoxmlUtil.child(root, "cols");
            if (cols != null) {
                for (Element c : OoxmlUtil.childEls(cols, "col")) {
                    Integer mn = OoxmlUtil.intAttr(c, "min"), mx = OoxmlUtil.intAttr(c, "max");
                    Double w = OoxmlUtil.dblAttr(c, "width");
                    if (mn == null || mx == null) continue;
                    mx = Math.min(mx, mn + 300);
                    String hv = OoxmlUtil.attr(c, "hidden");
                    boolean hid = "1".equals(hv) || "true".equalsIgnoreCase(hv);
                    for (int i = mn; i <= mx && i <= MAX_COL; i++) {
                        if (w != null && w > 0) {
                            if (colW.length() > 0) colW.append(',');
                            colW.append('"').append(i).append("\":").append(Math.round(w * 7 + 5));
                        }
                        if (hid) {
                            if (hiddenCols.length() > 0) hiddenCols.append(',');
                            hiddenCols.append(i);
                        }
                    }
                }
            }
            StringBuilder cells = new StringBuilder(1 << 14);
            int ncell = 0;
            Element sd = OoxmlUtil.child(root, "sheetData");
            Map<String, int[]> sharedMasters = new HashMap<>();
            if (sd != null) {
                int rowAuto = 0;
                for (Element row : OoxmlUtil.childEls(sd, "row")) {
                    Integer rn = OoxmlUtil.intAttr(row, "r");
                    int r = rn != null ? rn : rowAuto + 1;
                    rowAuto = r;
                    if (r < 1 || r > MAX_ROW) continue;
                    String ch = OoxmlUtil.attr(row, "customHeight");
                    Double ht = OoxmlUtil.dblAttr(row, "ht");
                    if (ht != null && ("1".equals(ch) || "true".equalsIgnoreCase(ch) || Math.abs(Math.round(ht * 4 / 3.0) - defRowH) > 1)) {
                        if (rowH.length() > 0) rowH.append(',');
                        rowH.append('"').append(r).append("\":").append(Math.round(ht * 4 / 3.0));
                    }
                    String hv = OoxmlUtil.attr(row, "hidden");
                    if ("1".equals(hv) || "true".equalsIgnoreCase(hv)) {
                        if (hiddenRows.length() > 0) hiddenRows.append(',');
                        hiddenRows.append(r);
                    }
                    int colAuto = 0;
                    for (Element c : OoxmlUtil.childEls(row, "c")) {
                        String ref = OoxmlUtil.attr(c, "r");
                        int col = colAuto + 1;
                        if (ref != null) {
                            int[] rc = parseRef(ref);
                            if (rc != null) {
                                col = rc[1];
                                if (rc[0] != r) r = rc[0];
                            }
                        }
                        colAuto = col;
                        if (col < 1 || col > MAX_COL) continue;
                        String type = OoxmlUtil.attr(c, "t");
                        Integer si = OoxmlUtil.intAttr(c, "s");
                        Element fEl = OoxmlUtil.child(c, "f");
                        Element vEl = OoxmlUtil.child(c, "v");
                        String vText = vEl == null ? null : vEl.getTextContent();
                        StringBuilder o = new StringBuilder();
                        o.append("{\"r\":").append(r).append(",\"c\":").append(col);
                        if (si != null && si > 0 && si < styles.size()) o.append(",\"s\":").append(si);
                        boolean hasVal = false;
                        if ("inlineStr".equals(type)) {
                            Element is = OoxmlUtil.child(c, "is");
                            if (is != null) {
                                o.append(",\"v\":").append(q(siText(is)));
                                hasVal = true;
                            }
                        } else if (vText != null) {
                            if ("s".equals(type)) {
                                try {
                                    int idx = Integer.parseInt(vText.trim());
                                    if (idx >= 0 && idx < sst.size()) {
                                        o.append(",\"v\":").append(q(sst.get(idx)));
                                        hasVal = true;
                                    }
                                } catch (NumberFormatException ignore) {
                                }
                            } else if ("str".equals(type)) {
                                o.append(",\"v\":").append(q(vText));
                                hasVal = true;
                            } else if ("b".equals(type)) {
                                o.append(",\"v\":").append("1".equals(vText.trim()) ? "true" : "false");
                                hasVal = true;
                            } else if ("e".equals(type)) {
                                o.append(",\"e\":").append(q(vText.trim()));
                                hasVal = true;
                            } else {
                                try {
                                    double dv = Double.parseDouble(vText.trim());
                                    if (!Double.isNaN(dv) && !Double.isInfinite(dv)) {
                                        o.append(",\"v\":").append(trim(dv));
                                        hasVal = true;
                                    }
                                } catch (NumberFormatException ignore) {
                                }
                            }
                        }
                        if (fEl != null) {
                            String ft = OoxmlUtil.attr(fEl, "t");
                            String text = fEl.getTextContent();
                            String si2 = OoxmlUtil.attr(fEl, "si");
                            if ("shared".equals(ft) && si2 != null) {
                                if (text != null && !text.trim().isEmpty()) {
                                    sharedMasters.put(si2, new int[]{r, col});
                                    o.append(",\"f\":").append(q(stripEq(text)));
                                } else {
                                    int[] m = sharedMasters.get(si2);
                                    if (m != null) o.append(",\"sh\":[").append(m[0]).append(',').append(m[1]).append(']');
                                }
                            } else if (text != null && !text.trim().isEmpty()) {
                                o.append(",\"f\":").append(q(stripEq(text)));
                            }
                        }
                        o.append('}');
                        if (!hasVal && fEl == null && (si == null || si == 0)) continue;
                        if (ncell++ > 0) cells.append(',');
                        cells.append(o);
                    }
                }
            }
            out.append(",\"cells\":[").append(cells).append(']');
            if (colW.length() > 0) out.append(",\"colW\":{").append(colW).append('}');
            if (rowH.length() > 0) out.append(",\"rowH\":{").append(rowH).append('}');
            out.append(",\"defColW\":").append(trim(defColW)).append(",\"defRowH\":").append(trim(defRowH));
            // الدمج
            Element mc = OoxmlUtil.child(root, "mergeCells");
            if (mc != null) {
                StringBuilder ms = new StringBuilder();
                for (Element m : OoxmlUtil.childEls(mc, "mergeCell")) {
                    String ref = OoxmlUtil.attr(m, "ref");
                    if (ref == null) continue;
                    String[] p = ref.split(":");
                    int[] a = parseRef(p[0]);
                    int[] b = p.length > 1 ? parseRef(p[1]) : a;
                    if (a == null || b == null) continue;
                    if (ms.length() > 0) ms.append(',');
                    ms.append('[').append(a[0]).append(',').append(a[1]).append(',').append(b[0]).append(',').append(b[1]).append(']');
                }
                if (ms.length() > 0) out.append(",\"merges\":[").append(ms).append(']');
            }
            // العرض: تجميد، اتجاه، شبكة
            Element svs = OoxmlUtil.child(root, "sheetViews");
            Element sv = svs == null ? null : OoxmlUtil.child(svs, "sheetView");
            if (sv != null) {
                String rtl = OoxmlUtil.attr(sv, "rightToLeft");
                if ("1".equals(rtl) || "true".equalsIgnoreCase(rtl)) out.append(",\"rtl\":true");
                String sg = OoxmlUtil.attr(sv, "showGridLines");
                if ("0".equals(sg) || "false".equalsIgnoreCase(sg)) out.append(",\"showGrid\":false");
                Element pane = OoxmlUtil.child(sv, "pane");
                if (pane != null) {
                    String state = OoxmlUtil.attr(pane, "state");
                    if (state != null && state.startsWith("frozen")) {
                        Integer xs = OoxmlUtil.intAttr(pane, "xSplit"), ys = OoxmlUtil.intAttr(pane, "ySplit");
                        if (ys != null && ys > 0) out.append(",\"freezeR\":").append(ys);
                        if (xs != null && xs > 0) out.append(",\"freezeC\":").append(xs);
                    }
                }
            }
            if (hiddenRows.length() > 0) out.append(",\"hiddenRows\":[").append(hiddenRows).append(']');
            if (hiddenCols.length() > 0) out.append(",\"hiddenCols\":[").append(hiddenCols).append(']');
            Element sp = OoxmlUtil.child(root, "sheetPr");
            Element tc = sp == null ? null : OoxmlUtil.child(sp, "tabColor");
            String tcol = color(tc);
            if (tcol != null) out.append(",\"tabColor\":").append(q(tcol));
            out.append('}');
        }
    }

    private static boolean on(Element e) {
        return e != null && OoxmlUtil.flag(e);
    }

    private static String stripEq(String f) {
        String t = f.trim();
        return t.startsWith("=") ? t.substring(1) : t;
    }

    private static void add(StringBuilder sb, String kv) {
        if (sb.length() > 0) sb.append(',');
        sb.append(kv);
    }

    static int[] parseRef(String ref) {
        int i = 0, col = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            col = col * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
        }
        if (i == 0 || i >= ref.length()) return null;
        try {
            return new int[]{Integer.parseInt(ref.substring(i).replace("$", "")), col};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String trim(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return Double.toString(d);
    }

    static String q(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<': sb.append("\\u003c"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // ===================================================================== الكتابة

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

    static String colName(int c) {
        StringBuilder sb = new StringBuilder();
        while (c > 0) {
            int m = (c - 1) % 26;
            sb.insert(0, (char) ('A' + m));
            c = (c - 1) / 26;
        }
        return sb.toString();
    }

    private static String argb(String hex) {
        if (hex == null) return "FF000000";
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        if (!h.matches("[0-9A-Fa-f]{6}")) return "FF000000";
        return "FF" + h.toUpperCase(Locale.ROOT);
    }

    public static void write(String json, OutputStream os) throws Exception {
        Map<String, Object> model = MiniJson.obj(MiniJson.parse(json));
        List<Object> styleList = MiniJson.arr(model.get("styles"));
        List<Object> sheetList = MiniJson.arr(model.get("sheets"));
        boolean d1904 = MiniJson.flag(model, "d1904");
        if (sheetList.isEmpty()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", "Sheet1");
            sheetList.add(s);
        }

        // ---- الأنماط
        List<String> fontXml = new ArrayList<>(), fillXml = new ArrayList<>(), borderXml = new ArrayList<>(), xfXml = new ArrayList<>();
        Map<String, Integer> fontIdx = new HashMap<>(), fillIdx = new HashMap<>(), borderIdx = new HashMap<>(), nfIdx = new HashMap<>();
        List<String> nfXml = new ArrayList<>();
        fillXml.add("<fill><patternFill patternType=\"none\"/></fill>");
        fillXml.add("<fill><patternFill patternType=\"gray125\"/></fill>");
        borderXml.add("<border><left/><right/><top/><bottom/><diagonal/></border>");
        borderIdx.put(borderXml.get(0), 0);
        String defFont = "<font><sz val=\"11\"/><color rgb=\"FF000000\"/><name val=\"Calibri\"/><family val=\"2\"/></font>";
        fontXml.add(defFont);
        fontIdx.put(defFont, 0);
        if (styleList.isEmpty()) styleList.add(new LinkedHashMap<String, Object>());

        for (int si = 0; si < styleList.size(); si++) {
            Map<String, Object> st = MiniJson.obj(styleList.get(si));
            // خط
            StringBuilder f = new StringBuilder("<font>");
            if (MiniJson.flag(st, "b")) f.append("<b/>");
            if (MiniJson.flag(st, "i")) f.append("<i/>");
            if (MiniJson.flag(st, "s")) f.append("<strike/>");
            if (MiniJson.flag(st, "u")) f.append("<u/>");
            double sz = MiniJson.num(st, "sz", 11);
            f.append("<sz val=\"").append(trim(sz)).append("\"/>");
            String fc = MiniJson.str(st, "fc");
            f.append("<color rgb=\"").append(argb(fc)).append("\"/>");
            String fn = MiniJson.str(st, "fn");
            f.append("<name val=\"").append(esc(fn == null || fn.isEmpty() ? "Calibri" : fn)).append("\"/><family val=\"2\"/></font>");
            String fx = f.toString();
            Integer fi = fontIdx.get(fx);
            if (fi == null) {
                fi = fontXml.size();
                fontXml.add(fx);
                fontIdx.put(fx, fi);
            }
            // تعبئة
            int fillId = 0;
            String bg = MiniJson.str(st, "bg");
            if (bg != null && !bg.isEmpty()) {
                String fl = "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"" + argb(bg) + "\"/><bgColor indexed=\"64\"/></patternFill></fill>";
                Integer id = fillIdx.get(fl);
                if (id == null) {
                    id = fillXml.size();
                    fillXml.add(fl);
                    fillIdx.put(fl, id);
                }
                fillId = id;
            }
            // حدود
            int borderId = 0;
            Object bdo = st.get("bd");
            if (bdo instanceof Map) {
                Map<String, Object> bd = MiniJson.obj(bdo);
                StringBuilder b = new StringBuilder("<border>");
                String[][] sides = {{"l", "left"}, {"r", "right"}, {"t", "top"}, {"b", "bottom"}};
                for (String[] sd : sides) {
                    Map<String, Object> e = MiniJson.obj(bd.get(sd[0]));
                    String style = MiniJson.str(e, "s");
                    if (style == null || style.isEmpty()) {
                        b.append('<').append(sd[1]).append("/>");
                    } else {
                        b.append('<').append(sd[1]).append(" style=\"").append(esc(style)).append("\"><color rgb=\"").append(argb(MiniJson.str(e, "c"))).append("\"/></").append(sd[1]).append('>');
                    }
                }
                b.append("<diagonal/></border>");
                String bx = b.toString();
                Integer id = borderIdx.get(bx);
                if (id == null) {
                    id = borderXml.size();
                    borderXml.add(bx);
                    borderIdx.put(bx, id);
                }
                borderId = id;
            }
            // تنسيق الأرقام
            int numFmtId = 0;
            String nf = MiniJson.str(st, "nf");
            if (nf != null && !nf.isEmpty() && !nf.equalsIgnoreCase("General")) {
                Integer builtin = null;
                for (Map.Entry<Integer, String> e : BUILTIN_NF.entrySet()) {
                    if (e.getValue().equals(nf)) {
                        builtin = e.getKey();
                        break;
                    }
                }
                if (builtin != null) {
                    numFmtId = builtin;
                } else {
                    Integer id = nfIdx.get(nf);
                    if (id == null) {
                        id = 164 + nfXml.size();
                        nfXml.add("<numFmt numFmtId=\"" + id + "\" formatCode=\"" + esc(nf) + "\"/>");
                        nfIdx.put(nf, id);
                    }
                    numFmtId = id;
                }
            }
            StringBuilder xf = new StringBuilder("<xf numFmtId=\"").append(numFmtId).append("\" fontId=\"").append(fi)
                    .append("\" fillId=\"").append(fillId).append("\" borderId=\"").append(borderId).append("\" xfId=\"0\"");
            if (numFmtId != 0) xf.append(" applyNumberFormat=\"1\"");
            if (fi != 0) xf.append(" applyFont=\"1\"");
            if (fillId != 0) xf.append(" applyFill=\"1\"");
            if (borderId != 0) xf.append(" applyBorder=\"1\"");
            String h = MiniJson.str(st, "h"), v = MiniJson.str(st, "v");
            boolean wrap = MiniJson.flag(st, "wrap");
            boolean hasH = h != null && !h.isEmpty() && !h.equals("general");
            boolean hasV = v != null && !v.isEmpty() && !v.equals("bottom");
            if (hasH || hasV || wrap) {
                xf.append(" applyAlignment=\"1\"><alignment");
                if (hasH) xf.append(" horizontal=\"").append(esc(h)).append('"');
                if (hasV) xf.append(" vertical=\"").append(esc(v)).append('"');
                if (wrap) xf.append(" wrapText=\"1\"");
                xf.append("/></xf>");
            } else {
                xf.append("/>");
            }
            xfXml.add(xf.toString());
        }

        StringBuilder styles = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        if (!nfXml.isEmpty()) {
            styles.append("<numFmts count=\"").append(nfXml.size()).append("\">");
            for (String s : nfXml) styles.append(s);
            styles.append("</numFmts>");
        }
        styles.append("<fonts count=\"").append(fontXml.size()).append("\">");
        for (String s : fontXml) styles.append(s);
        styles.append("</fonts><fills count=\"").append(fillXml.size()).append("\">");
        for (String s : fillXml) styles.append(s);
        styles.append("</fills><borders count=\"").append(borderXml.size()).append("\">");
        for (String s : borderXml) styles.append(s);
        styles.append("</borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"")
                .append(xfXml.size()).append("\">");
        for (String s : xfXml) styles.append(s);
        styles.append("</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");

        // ---- الأوراق + النصوص المشتركة
        List<String> sstList = new ArrayList<>();
        Map<String, Integer> sstIdx = new HashMap<>();
        List<String> sheetXml = new ArrayList<>();
        List<String> sheetNames = new ArrayList<>();
        Map<String, Boolean> usedNames = new HashMap<>();
        for (int k = 0; k < sheetList.size(); k++) {
            Map<String, Object> sh = MiniJson.obj(sheetList.get(k));
            String nm = MiniJson.str(sh, "name");
            if (nm == null || nm.isEmpty()) nm = "Sheet" + (k + 1);
            nm = nm.replaceAll("[\\\\/?*\\[\\]:]", "_");
            if (nm.length() > 31) nm = nm.substring(0, 31);
            String base = nm;
            int dup = 2;
            while (usedNames.containsKey(nm.toLowerCase(Locale.ROOT))) {
                String suffix = "(" + dup++ + ")";
                nm = base.substring(0, Math.min(base.length(), 31 - suffix.length())) + suffix;
            }
            usedNames.put(nm.toLowerCase(Locale.ROOT), true);
            sheetNames.add(nm);
            sheetXml.add(sheetToXml(sh, styleList.size(), sstList, sstIdx, k == 0));
        }

        StringBuilder sst = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"")
                .append(sstList.size()).append("\" uniqueCount=\"").append(sstList.size()).append("\">");
        for (String s : sstList) {
            sst.append("<si><t xml:space=\"preserve\">").append(esc(s)).append("</t></si>");
        }
        sst.append("</sst>");

        StringBuilder wb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
        wb.append("<workbookPr").append(d1904 ? " date1904=\"1\"" : "").append("/><bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>");
        StringBuilder wbRels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        StringBuilder ct = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/><Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>");
        for (int k = 0; k < sheetNames.size(); k++) {
            wb.append("<sheet name=\"").append(esc(sheetNames.get(k))).append("\" sheetId=\"").append(k + 1).append("\" r:id=\"rId").append(k + 1).append("\"/>");
            wbRels.append("<Relationship Id=\"rId").append(k + 1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(k + 1).append(".xml\"/>");
            ct.append("<Override PartName=\"/xl/worksheets/sheet").append(k + 1).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        int n = sheetNames.size();
        wb.append("</sheets><calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>");
        wbRels.append("<Relationship Id=\"rId").append(n + 1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
                .append("<Relationship Id=\"rId").append(n + 2).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/></Relationships>");
        ct.append("</Types>");
        String rootRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>";

        try (ZipOutputStream zos = new ZipOutputStream(os)) {
            put(zos, "[Content_Types].xml", ct.toString());
            put(zos, "_rels/.rels", rootRels);
            put(zos, "xl/workbook.xml", wb.toString());
            put(zos, "xl/_rels/workbook.xml.rels", wbRels.toString());
            put(zos, "xl/styles.xml", styles.toString());
            put(zos, "xl/sharedStrings.xml", sst.toString());
            for (int k = 0; k < sheetXml.size(); k++) put(zos, "xl/worksheets/sheet" + (k + 1) + ".xml", sheetXml.get(k));
        }
    }

    private static void put(ZipOutputStream zos, String name, String data) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String sheetToXml(Map<String, Object> sh, int nStyles, List<String> sstList, Map<String, Integer> sstIdx, boolean selected) {
        StringBuilder x = new StringBuilder(1 << 15);
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
        String tab = MiniJson.str(sh, "tabColor");
        if (tab != null && !tab.isEmpty()) x.append("<sheetPr><tabColor rgb=\"").append(argb(tab)).append("\"/></sheetPr>");
        // أبعاد
        List<Object> cells = MiniJson.arr(sh.get("cells"));
        int maxR = 1, maxC = 1;
        for (Object o : cells) {
            Map<String, Object> c = MiniJson.obj(o);
            maxR = Math.max(maxR, MiniJson.integer(c, "r", 1));
            maxC = Math.max(maxC, MiniJson.integer(c, "c", 1));
        }
        x.append("<dimension ref=\"A1:").append(colName(maxC)).append(maxR).append("\"/>");
        boolean rtl = MiniJson.flag(sh, "rtl");
        boolean showGrid = !(sh.containsKey("showGrid") && !MiniJson.flag(sh, "showGrid"));
        int fr = MiniJson.integer(sh, "freezeR", 0), fcn = MiniJson.integer(sh, "freezeC", 0);
        x.append("<sheetViews><sheetView workbookViewId=\"0\"");
        if (rtl) x.append(" rightToLeft=\"1\"");
        if (!showGrid) x.append(" showGridLines=\"0\"");
        if (selected) x.append(" tabSelected=\"1\"");
        if (fr > 0 || fcn > 0) {
            x.append("><pane");
            if (fcn > 0) x.append(" xSplit=\"").append(fcn).append('"');
            if (fr > 0) x.append(" ySplit=\"").append(fr).append('"');
            x.append(" topLeftCell=\"").append(colName(fcn + 1)).append(fr + 1).append("\" activePane=\"")
                    .append(fr > 0 && fcn > 0 ? "bottomRight" : fr > 0 ? "bottomLeft" : "topRight").append("\" state=\"frozen\"/></sheetView></sheetViews>");
        } else {
            x.append("/></sheetViews>");
        }
        double defColW = MiniJson.num(sh, "defColW", 64), defRowH = MiniJson.num(sh, "defRowH", 20);
        x.append("<sheetFormatPr defaultRowHeight=\"").append(trim(Math.round(defRowH * 3 / 4.0 * 100) / 100.0)).append("\"");
        if (Math.abs(defRowH - 20) > 0.5) x.append(" customHeight=\"1\"");
        x.append("/>");
        Map<String, Object> colW = MiniJson.obj(sh.get("colW"));
        java.util.TreeMap<Integer, Double> cw = new java.util.TreeMap<>();
        for (Map.Entry<String, Object> e : colW.entrySet()) {
            try {
                int ci = Integer.parseInt(e.getKey());
                if (ci >= 1 && ci <= MAX_COL && e.getValue() instanceof Double) cw.put(ci, (Double) e.getValue());
            } catch (NumberFormatException ignore) {
            }
        }
        java.util.HashSet<Integer> hidCols = new java.util.HashSet<>();
        for (Object o : MiniJson.arr(sh.get("hiddenCols"))) if (o instanceof Double) hidCols.add(((Double) o).intValue());
        java.util.HashSet<Integer> hidRows = new java.util.HashSet<>();
        for (Object o : MiniJson.arr(sh.get("hiddenRows"))) if (o instanceof Double) hidRows.add(((Double) o).intValue());
        java.util.TreeSet<Integer> colIdx = new java.util.TreeSet<>(cw.keySet());
        colIdx.addAll(hidCols);
        if (!colIdx.isEmpty() || Math.abs(defColW - 64) > 0.5) {
            x.append("<cols>");
            for (int ci : colIdx) {
                double wpx = cw.containsKey(ci) ? cw.get(ci) : defColW;
                double w = Math.max(0, Math.round((wpx - 5) / 7.0 * 100) / 100.0);
                x.append("<col min=\"").append(ci).append("\" max=\"").append(ci).append("\" width=\"").append(trim(w)).append("\" customWidth=\"1\"");
                if (hidCols.contains(ci)) x.append(" hidden=\"1\"");
                x.append("/>");
            }
            x.append("</cols>");
        }
        // الصفوف
        Map<String, Object> rowH = MiniJson.obj(sh.get("rowH"));
        java.util.TreeMap<Integer, List<Map<String, Object>>> rows = new java.util.TreeMap<>();
        for (Object o : cells) {
            Map<String, Object> c = MiniJson.obj(o);
            int r = MiniJson.integer(c, "r", 0), cc = MiniJson.integer(c, "c", 0);
            if (r < 1 || cc < 1 || r > MAX_ROW || cc > MAX_COL) continue;
            rows.computeIfAbsent(r, kk -> new ArrayList<>()).add(c);
        }
        for (String k : rowH.keySet()) {
            try {
                rows.computeIfAbsent(Integer.parseInt(k), kk -> new ArrayList<>());
            } catch (NumberFormatException ignore) {
            }
        }
        for (int r : hidRows) rows.computeIfAbsent(r, kk -> new ArrayList<>());
        x.append("<sheetData>");
        for (Map.Entry<Integer, List<Map<String, Object>>> re : rows.entrySet()) {
            int r = re.getKey();
            List<Map<String, Object>> rc = re.getValue();
            rc.sort((a, b) -> Integer.compare(MiniJson.integer(a, "c", 0), MiniJson.integer(b, "c", 0)));
            x.append("<row r=\"").append(r).append('"');
            Object hv = rowH.get(String.valueOf(r));
            if (hv instanceof Double) x.append(" ht=\"").append(trim(Math.round((Double) hv * 3 / 4.0 * 100) / 100.0)).append("\" customHeight=\"1\"");
            if (hidRows.contains(r)) x.append(" hidden=\"1\"");
            x.append('>');
            for (Map<String, Object> c : rc) {
                int col = MiniJson.integer(c, "c", 1);
                int s = MiniJson.integer(c, "s", 0);
                if (s < 0 || s >= nStyles) s = 0;
                String ref = colName(col) + r;
                Object val = c.get("v");
                String err = MiniJson.str(c, "e");
                String f = MiniJson.str(c, "f");
                x.append("<c r=\"").append(ref).append('"');
                if (s != 0) x.append(" s=\"").append(s).append('"');
                if (err != null) {
                    x.append(" t=\"e\">");
                    if (f != null) x.append("<f>").append(esc(f)).append("</f>");
                    x.append("<v>").append(esc(err)).append("</v></c>");
                } else if (val instanceof Boolean) {
                    x.append(" t=\"b\">");
                    if (f != null) x.append("<f>").append(esc(f)).append("</f>");
                    x.append("<v>").append(((Boolean) val) ? 1 : 0).append("</v></c>");
                } else if (val instanceof Double) {
                    x.append('>');
                    if (f != null) x.append("<f>").append(esc(f)).append("</f>");
                    x.append("<v>").append(trim((Double) val)).append("</v></c>");
                } else if (val instanceof String) {
                    String sv = (String) val;
                    if (f != null) {
                        x.append(" t=\"str\"><f>").append(esc(f)).append("</f><v>").append(esc(sv)).append("</v></c>");
                    } else {
                        Integer id = sstIdx.get(sv);
                        if (id == null) {
                            id = sstList.size();
                            sstList.add(sv);
                            sstIdx.put(sv, id);
                        }
                        x.append(" t=\"s\"><v>").append(id).append("</v></c>");
                    }
                } else if (f != null) {
                    x.append("><f>").append(esc(f)).append("</f></c>");
                } else {
                    x.append("/>");
                }
            }
            x.append("</row>");
        }
        x.append("</sheetData>");
        List<Object> merges = MiniJson.arr(sh.get("merges"));
        StringBuilder ms = new StringBuilder();
        int mcount = 0;
        for (Object o : merges) {
            List<Object> m = MiniJson.arr(o);
            if (m.size() < 4) continue;
            int r1 = ((Double) m.get(0)).intValue(), c1 = ((Double) m.get(1)).intValue(), r2 = ((Double) m.get(2)).intValue(), c2 = ((Double) m.get(3)).intValue();
            if (r1 == r2 && c1 == c2) continue;
            ms.append("<mergeCell ref=\"").append(colName(c1)).append(r1).append(':').append(colName(c2)).append(r2).append("\"/>");
            mcount++;
        }
        if (mcount > 0) x.append("<mergeCells count=\"").append(mcount).append("\">").append(ms).append("</mergeCells>");
        x.append("<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/></worksheet>");
        return x.toString();
    }
}
