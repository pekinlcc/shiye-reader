package com.pekinlcc.reader;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import org.json.*;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/**
 * Brings a book the user picked with the system file picker into the on-device library.
 * This is the in-app counterpart of scripts/prepare_library.py and writes the same
 * catalog.json shape, so books added either way sit on the same shelf.
 */
class Importer {

    interface Progress { void step(String message); }

    static final long MAX_ENTRY = 256L * 1024 * 1024;   // per-file guard while unzipping
    static final long MAX_TOTAL = 1024L * 1024 * 1024;  // whole archive, book-shaped
    static final int  MAX_ENTRIES = 20000;              // a zip bomb can be all tiny files
    static final int  MAX_NAME = 512;

    // ---- entry point ---------------------------------------------------------

    static synchronized JSONObject ingest(Context ctx, File library, Uri uri, Progress p) throws Exception {
        String name = displayName(ctx, uri);
        p.step("正在读取 " + name);
        File tmp = File.createTempFile("import", ".bin", ctx.getCacheDir());
        File staging = null;
        try {
            String sha = copyAndDigest(ctx, uri, tmp);
            // Many pickers hand back a name with no extension, so fall back to the
            // provider's MIME type and finally to the file's own magic bytes.
            String ext = extension(name);
            if (!ext.equals("epub") && !ext.equals("pdf")) ext = fromMime(ctx.getContentResolver().getType(uri));
            if (!ext.equals("epub") && !ext.equals("pdf")) ext = sniff(tmp);
            if (!ext.equals("epub") && !ext.equals("pdf"))
                throw new IOException("暂不支持这个文件，目前可导入 EPUB 和 PDF"
                        + (extension(name).equals("mobi") ? "（MOBI 需要用电脑脚本转换）" : ""));

            String id = sha.substring(0, 16);
            File dest = new File(library, id);
            // Unpack beside the real directory: a failed import must not destroy a
            // book that is already on the shelf under the same id.
            staging = new File(library, id + ".incoming");
            deleteTree(staging);
            if (!staging.mkdirs()) throw new IOException("无法创建书籍目录");

            JSONObject entry = new JSONObject();
            entry.put("id", id);
            entry.put("source", name);
            entry.put("sha256", sha);
            entry.put("format", ext.toUpperCase(Locale.ROOT));

            if (ext.equals("pdf")) {
                if (!tmp.renameTo(new File(staging, "book.pdf"))) copy(tmp, new File(staging, "book.pdf"));
                entry.put("title", fromFilename(name));
                entry.put("author", "");
                entry.put("kind", "pdf");
                entry.put("path", "book.pdf");
                entry.put("cover", "");
            } else {
                p.step("正在解包 " + name);
                unzip(tmp, staging);
                p.step("正在读取目录结构");
                describeEpub(staging, fromFilename(name), entry, p);
                entry.put("kind", "epub");
            }
            deleteTree(dest);
            if (!staging.renameTo(dest)) throw new IOException("无法写入书库目录");
            staging = null;
            return entry;
        } finally {
            deleteTree(staging);                       // no-op on success
            if (tmp.exists() && !tmp.delete()) tmp.deleteOnExit();
        }
    }

    private static String fromMime(String mime) {
        if (mime == null) return "";
        if (mime.startsWith("application/pdf")) return "pdf";
        if (mime.startsWith("application/epub")) return "epub";
        return "";
    }

    /** Last-resort format detection from the file's own bytes. */
    private static String sniff(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[4];
            if (in.read(b) < 4) return "";
            if (b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') return "pdf";
            if (b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4) {
                try (ZipFile z = new ZipFile(f)) {
                    if (z.getEntry("META-INF/container.xml") != null) return "epub";
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return "";
    }

    /** Drops a book from the shelf and deletes its files. */
    static synchronized void remove(File library, String id) throws Exception {
        File f = new File(library, "catalog.json");
        if (f.isFile()) {                              // drop the card first, then the bytes
            JSONArray arr = new JSONArray(readText(f));
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!id.equals(o.optString("id"))) out.put(o);
            }
            writeAtomic(f, out.toString());
        }
        deleteTree(new File(library, id));
    }

    /** Adds or replaces one entry in catalog.json. */
    static synchronized void merge(File library, JSONObject entry) throws Exception {
        File f = new File(library, "catalog.json");
        JSONArray arr = f.isFile() ? new JSONArray(readText(f)) : new JSONArray();
        JSONArray out = new JSONArray();
        String id = entry.getString("id");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (!id.equals(o.optString("id"))) out.put(o);
        }
        out.put(entry);
        writeAtomic(f, out.toString());
    }

    // ---- EPUB ----------------------------------------------------------------

    private static void describeEpub(File dir, String fallbackTitle, JSONObject entry, Progress p) throws Exception {
        Document container = xml(new File(dir, "META-INF/container.xml"));
        String opf = null;
        for (Element e : byName(container, "rootfile")) {
            String v = e.getAttribute("full-path");
            if (v != null && !v.isEmpty()) { opf = resolve("", v); break; }
        }
        if (opf == null || opf.isEmpty()) throw new IOException("EPUB 缺少 container.xml 中的 rootfile");
        File opfFile = new File(dir, opf);
        // full-path comes from the archive; keep it inside this book's directory.
        if (!opfFile.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator))
            throw new IOException("EPUB 的 rootfile 路径不安全");
        if (!opfFile.isFile()) throw new IOException("EPUB 缺少 " + opf);
        Document doc = xml(opfFile);
        String base = dirname(opf);

        String title = "";
        for (Element e : byName(doc, "title")) { title = clean(text(e)); if (!title.isEmpty()) break; }
        if (title.isEmpty()) title = fallbackTitle;

        List<String> authors = new ArrayList<>();
        for (Element e : byName(doc, "creator")) { String a = clean(text(e)); if (!a.isEmpty()) authors.add(a); }

        Map<String, Element> manifest = new LinkedHashMap<>();
        for (Element e : byName(doc, "item")) {
            String id = e.getAttribute("id");
            if (id != null && !id.isEmpty()) manifest.put(id, e);
        }

        JSONArray chapters = new JSONArray();
        int skipped = 0;
        for (Element ref : byName(doc, "itemref")) {
            String linear = ref.getAttribute("linear");
            if ("no".equalsIgnoreCase(linear)) continue;
            Element item = manifest.get(ref.getAttribute("idref"));
            if (item == null) continue;
            String path = resolve(base, item.getAttribute("href"));
            File f = new File(dir, path);
            if (!f.isFile()) { skipped++; continue; }         // tolerate a broken spine entry
            String label = labelOf(f);
            if (label.isEmpty()) label = "第 " + (chapters.length() + 1) + " 节";
            chapters.put(new JSONObject().put("path", path).put("title", label));
            if (chapters.length() % 40 == 0) p.step("已读取 " + chapters.length() + " 章");
        }
        if (chapters.length() == 0) throw new IOException("这本 EPUB 没有可读的正文");

        JSONArray toc = readToc(dir, base, manifest, doc);
        entry.put("title", title);
        entry.put("author", join(authors, " / "));
        entry.put("chapters", chapters);
        entry.put("toc", toc.length() > 0 ? toc : chapters);
        entry.put("cover", findCover(dir, base, manifest, doc));
        if (skipped > 0) entry.put("skipped", skipped);
    }

    private static JSONArray readToc(File dir, String base, Map<String, Element> manifest, Document doc) {
        JSONArray toc = new JSONArray();
        // EPUB 3 navigation document
        for (Element item : manifest.values()) {
            if (!Arrays.asList(item.getAttribute("properties").split("\\s+")).contains("nav")) continue;
            try {
                String navPath = resolve(base, item.getAttribute("href"));
                Document nd = xml(new File(dir, navPath));
                List<Element> navs = byName(nd, "nav");
                Element node = null;
                for (Element n : navs) {
                    NamedNodeMap at = n.getAttributes();
                    for (int i = 0; i < at.getLength(); i++)
                        if ("toc".equals(at.item(i).getNodeValue())) { node = n; break; }
                    if (node != null) break;
                }
                if (node == null && !navs.isEmpty()) node = navs.get(0);
                if (node == null) break;
                String navDir = dirname(navPath);
                for (Element a : byName(node, "a")) {
                    String href = a.getAttribute("href");
                    if (href == null || href.isEmpty()) continue;
                    toc.put(new JSONObject().put("title", clean(text(a))).put("path", resolveKeepHash(navDir, href)));
                }
            } catch (Exception ignored) { }
            break;
        }
        if (toc.length() > 0) return toc;
        // EPUB 2 NCX
        for (Element item : manifest.values()) {
            if (!"application/x-dtbncx+xml".equals(item.getAttribute("media-type"))) continue;
            try {
                String ncxPath = resolve(base, item.getAttribute("href"));
                Document nd = xml(new File(dir, ncxPath));
                String ncxDir = dirname(ncxPath);
                for (Element pt : byName(nd, "navPoint")) {
                    String label = "", src = null;
                    for (Element c : children(pt)) {
                        if (local(c).equals("navLabel")) label = clean(text(c));
                        else if (local(c).equals("content")) src = c.getAttribute("src");
                    }
                    if (src != null && !src.isEmpty())
                        toc.put(new JSONObject().put("title", label).put("path", resolveKeepHash(ncxDir, src)));
                }
            } catch (Exception ignored) { }
            break;
        }
        return toc;
    }

    private static String findCover(File dir, String base, Map<String, Element> manifest, Document doc) {
        Element cover = null;
        for (Element item : manifest.values())
            if (Arrays.asList(item.getAttribute("properties").split("\\s+")).contains("cover-image")) { cover = item; break; }
        if (cover == null) {
            String cid = null;
            for (Element m : byName(doc, "meta"))
                if ("cover".equals(m.getAttribute("name"))) { cid = m.getAttribute("content"); break; }
            if (cid != null) cover = manifest.get(cid);
        }
        if (cover == null)
            for (Element item : manifest.values()) {
                String href = item.getAttribute("href").toLowerCase(Locale.ROOT);
                if (href.contains("cover") && item.getAttribute("media-type").startsWith("image/")) { cover = item; break; }
            }
        if (cover == null) return "";
        String path = resolve(base, cover.getAttribute("href"));
        return new File(dir, path).isFile() ? path : "";
    }

    /** Cheap chapter title: first heading or <title>, read from the head of the file. */
    private static String labelOf(File f) {
        try {
            String head = readHead(f, 16384);
            String h = headingText(head);
            if (h.isEmpty()) h = textOf(head, "<title", "</title>");
            return h;
        } catch (Exception e) { return ""; }
    }

    /** First heading whose text is actually non-empty — a heading holding only an image does not count. */
    private static String headingText(String s) {
        int from = 0;
        for (int guard = 0; guard < 24; guard++) {
            int best = -1; String tag = null;
            for (String t : new String[]{"<h1", "<h2", "<h3"}) {
                int i = idx(s, t, from);
                if (i >= 0 && (best < 0 || i < best)) { best = i; tag = t; }
            }
            if (best < 0) return "";
            String txt = textOf(s.substring(best), tag, "</" + tag.substring(1) + ">");
            if (!txt.isEmpty()) return txt;
            from = best + 3;
        }
        return "";
    }

    private static String textOf(String s, String open, String close) {
        return clean(unescape(stripTags(between(s, open, close))));
    }

    /** Reads the head of an XHTML file, honouring a BOM or a declared encoding. */
    private static String readHead(File f, int max) throws IOException {
        byte[] buf = new byte[max];
        int n = 0, r;
        try (InputStream in = new FileInputStream(f)) {
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
        }
        if (n >= 2) {
            if ((buf[0] & 0xff) == 0xFE && (buf[1] & 0xff) == 0xFF) return new String(buf, 2, n - 2, "UTF-16BE");
            if ((buf[0] & 0xff) == 0xFF && (buf[1] & 0xff) == 0xFE) return new String(buf, 2, n - 2, "UTF-16LE");
        }
        int skip = (n >= 3 && (buf[0] & 0xff) == 0xEF && (buf[1] & 0xff) == 0xBB && (buf[2] & 0xff) == 0xBF) ? 3 : 0;
        String probe = new String(buf, skip, Math.min(n - skip, 1024), StandardCharsets.ISO_8859_1);
        String enc = attrIn(probe, "encoding") ;
        if (enc.isEmpty()) enc = attrIn(probe, "charset");
        if (!enc.isEmpty()) {
            try { if (java.nio.charset.Charset.isSupported(enc)) return new String(buf, skip, n - skip, enc); }
            catch (Exception ignored) { }
        }
        return new String(buf, skip, n - skip, StandardCharsets.UTF_8);
    }

    /** Pulls name="value" / name=\'value\' out of an XML declaration or meta tag. */
    private static String attrIn(String s, String name) {
        int i = idx(s, name + "=", 0);
        if (i < 0) return "";
        int q = i + name.length() + 1;
        if (q >= s.length()) return "";
        char quote = s.charAt(q);
        if (quote != '"' && quote != '\'') return "";
        int end = s.indexOf(quote, q + 1);
        return end < 0 ? "" : s.substring(q + 1, end).trim();
    }

    /**
     * Case-insensitive indexOf that never shifts offsets — unlike searching a
     * toLowerCase() copy, whose length can differ from the original.
     */
    private static int idx(String s, String lowerNeedle, int from) {
        int n = s.length(), m = lowerNeedle.length();
        for (int i = Math.max(0, from); i + m <= n; i++)
            if (s.regionMatches(true, i, lowerNeedle, 0, m)) return i;
        return -1;
    }

    private static String between(String s, String open, String close) {
        int a = idx(s, open, 0);
        if (a < 0) return "";
        int gt = s.indexOf('>', a);
        if (gt < 0) return "";
        int b = idx(s, close, gt);
        if (b < 0) return "";
        return s.substring(gt + 1, b);
    }

    private static String stripTags(String s) { return s.replaceAll("<[^>]*>", " "); }

    /** Decodes the handful of entities that actually show up in chapter headings. */
    private static String unescape(String s) {
        if (s == null || s.indexOf('&') < 0) return s == null ? "" : s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '&') { out.append(c); continue; }
            int semi = s.indexOf(';', i + 1);
            if (semi < 0 || semi - i > 12) { out.append(c); continue; }
            String name = s.substring(i + 1, semi);
            String rep = null;
            if (name.startsWith("#")) {
                try {
                    int cp = name.startsWith("#x") || name.startsWith("#X")
                            ? Integer.parseInt(name.substring(2), 16)
                            : Integer.parseInt(name.substring(1));
                    if (cp > 0 && cp <= 0x10FFFF) rep = new String(Character.toChars(cp));
                } catch (Exception ignored) { }
            } else if (name.equals("amp")) rep = "&";
            else if (name.equals("lt")) rep = "<";
            else if (name.equals("gt")) rep = ">";
            else if (name.equals("quot")) rep = "\"";
            else if (name.equals("apos")) rep = "'";
            else if (name.equals("nbsp")) rep = " ";
            if (rep == null) { out.append(c); continue; }
            out.append(rep);
            i = semi;
        }
        return out.toString();
    }

    // ---- zip -----------------------------------------------------------------

    private static void unzip(File zipFile, File dest) throws Exception {
        String root = dest.getCanonicalPath() + File.separator;
        long total = 0;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            int entries = 0;
            while ((e = zin.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("压缩包条目过多，已中止");
                if (e.getName().length() > MAX_NAME) throw new IOException("压缩包路径过长，已中止");
                File out = new File(dest, e.getName());
                if (!out.getCanonicalPath().startsWith(root)) throw new IOException("压缩包路径不安全：" + e.getName());
                if (e.isDirectory()) { out.mkdirs(); continue; }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                long written = 0;
                try (OutputStream o = new BufferedOutputStream(new FileOutputStream(out))) {
                    int r;
                    while ((r = zin.read(buf)) > 0) {
                        written += r;
                        total += r;
                        if (written > MAX_ENTRY || total > MAX_TOTAL) throw new IOException("文件过大，已中止");
                        o.write(buf, 0, r);
                    }
                }
            }
        }
    }

    // ---- io helpers ----------------------------------------------------------

    private static String copyAndDigest(Context ctx, Uri uri, File to) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(to))) {
            if (in == null) throw new IOException("无法读取所选文件");
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) { md.update(buf, 0, r); out.write(buf, 0, r); }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        return sb.toString();
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new BufferedOutputStream(new FileOutputStream(to))) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeAtomic(File f, String text) throws IOException {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(tmp))) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) { copy(tmp, f); tmp.delete(); }
    }

    static String displayName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.trim().isEmpty()) return n.trim();
            }
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? "未命名" : last;
    }

    // ---- small utilities -----------------------------------------------------

    private static Document xml(File f) throws Exception {
        // Android's DocumentBuilderFactory rejects the usual XXE feature names, so those
        // calls would throw and be swallowed. Refuse a DTD outright instead — an EPUB's
        // OPX/NCX/nav documents never need one, and this closes billion-laughs too.
        if (hasDoctype(f)) throw new IOException("EPUB 的 XML 含有 DOCTYPE 声明，已拒绝解析");
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        try { dbf.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true); } catch (Exception ignored) { }
        DocumentBuilder db = dbf.newDocumentBuilder();
        db.setEntityResolver((publicId, systemId) -> new org.xml.sax.InputSource(new StringReader("")));
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            return db.parse(in);
        }
    }

    /**
     * Only an INTERNAL subset can declare entities, which is what a billion-laughs
     * bomb needs. A plain external DOCTYPE is what every EPUB 2 NCX ships, so
     * rejecting those would throw away the table of contents of most older books.
     */
    private static boolean hasDoctype(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            String head = new String(buf, 0, n, StandardCharsets.UTF_8);
            if (idx(head, "<!entity", 0) >= 0) return true;      // entity declaration anywhere: refuse
            int i = idx(head, "<!doctype", 0);
            if (i < 0) return false;
            for (int j = i; j < head.length(); j++) {
                char c = head.charAt(j);
                if (c == '[') return true;                       // internal subset present
                if (c == '>') return false;                      // plain external doctype: fine
            }
            return true;                                         // never terminated in our window
        } catch (Exception e) { return false; }
    }

    private static String local(Node n) {
        String q = n.getNodeName();
        int i = q.indexOf(':');
        return i < 0 ? q : q.substring(i + 1);
    }

    private static List<Element> byName(Node root, String name) {
        List<Element> out = new ArrayList<>();
        collect(root, name, out);
        return out;
    }

    private static void collect(Node n, String name, List<Element> out) {
        if (n.getNodeType() == Node.ELEMENT_NODE && local(n).equals(name)) out.add((Element) n);
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) collect(c, name, out);
    }

    private static List<Element> children(Node n) {
        List<Element> out = new ArrayList<>();
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling())
            if (c.getNodeType() == Node.ELEMENT_NODE) out.add((Element) c);
        return out;
    }

    private static String text(Node n) {
        StringBuilder sb = new StringBuilder();
        if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) sb.append(n.getNodeValue());
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) sb.append(text(c));
        return sb.toString();
    }

    // Java's \\s is ASCII-only; Python's is Unicode-aware, so match it explicitly
    // (U+3000 ideographic space shows up constantly in Chinese headings).
    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\u00a0\\u3000\\u2000-\\u200a\\u202f\\u205f\\ufeff]+", " ").trim();
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) { if (sb.length() > 0) sb.append(sep); sb.append(p); }
        return sb.toString();
    }

    private static String dirname(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    /** Joins and normalises an OPF-relative href, dropping any fragment. */
    static String resolve(String base, String href) {
        String raw = href == null ? "" : href;
        int hash = raw.indexOf('#');
        if (hash >= 0) raw = raw.substring(0, hash);
        String h = pct(raw);
        return normalize(base.isEmpty() ? h : base + "/" + h);
    }

    /** Same, but keeps the fragment so the reader can jump to an anchor. */
    static String resolveKeepHash(String base, String href) {
        String raw = href == null ? "" : href;
        int hash = raw.indexOf('#');
        String frag = hash >= 0 ? "#" + pct(raw.substring(hash + 1)) : "";
        String h = pct(hash >= 0 ? raw.substring(0, hash) : raw);
        return normalize(base.isEmpty() ? h : base + "/" + h) + frag;
    }

    private static String normalize(String path) {
        Deque<String> out = new ArrayDeque<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) { if (!out.isEmpty()) out.removeLast(); continue; }
            out.addLast(seg);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : out) { if (sb.length() > 0) sb.append('/'); sb.append(s); }
        return sb.toString();
    }

    /** Percent-decoding that leaves '+' alone, unlike URLDecoder. */
    private static String pct(String s) {
        if (s == null) return "";
        if (s.indexOf('%') < 0) return s;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16), lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) { b.write((hi << 4) | lo); i += 2; continue; }
            }
            byte[] cb = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            b.write(cb, 0, cb.length);
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String extension(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    /** 'Title (Author) (Source).epub' -> 'Title' */
    static String fromFilename(String name) {
        String t = name;
        for (String ext : new String[]{".epub", ".pdf", ".mobi"})
            if (t.toLowerCase(Locale.ROOT).endsWith(ext)) { t = t.substring(0, t.length() - ext.length()); break; }
        int i = t.indexOf(" (");
        if (i > 0) t = t.substring(0, i);
        t = clean(t);
        return t.isEmpty() ? clean(name) : t;
    }
}
