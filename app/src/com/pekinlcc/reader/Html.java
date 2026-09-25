package com.pekinlcc.reader;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;

/**
 * EPUB chapters are XHTML, but the reader serves them as text/html so that the forgiving HTML
 * parser copes with the malformed markup real books ship. Two things break on that path:
 * <ul>
 * <li>a self-closing non-void tag such as {@code <a id="p12"/>} or {@code <title/>} opens an
 *     element that never closes, so the rest of the chapter turns into one big link, or
 *     disappears into the title;</li>
 * <li>the response declares UTF-8, which overrides the book's own {@code encoding="gbk"}.</li>
 * </ul>
 * This rewrites the chapter into UTF-8 HTML that parses the way its XML was meant to.
 */
final class Html {
    private Html() { }

    static final int MAX = 16 * 1024 * 1024;           // bigger files are passed through untouched

    private static final Set<String> VOID = new HashSet<>(Arrays.asList(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source",
            "track", "wbr", "param", "keygen", "basefont", "bgsound", "frame", "image"));

    // <name attr="v" attr='v' attr=v />, with quoted values allowed to contain '>' and '/'.
    private static final Pattern SELF_CLOSING = Pattern.compile(
            "<([A-Za-z][A-Za-z0-9:_.-]*+)((?:\\s++[^\\s\"'<>/=]++(?:\\s*+=\\s*+(?:\"[^\"]*+\"|'[^']*+'|[^\\s\"'<>]++))?+)*+)\\s*+/>");
    // XHTML's <br></br> is one break; the HTML parser reads </br> as a second one.
    private static final Pattern BR_CLOSE = Pattern.compile("</br\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DECLARED = Pattern.compile(
            "(?:encoding|charset)\\s*=\\s*[\"']?\\s*([A-Za-z0-9_.:-]+)", Pattern.CASE_INSENSITIVE);

    /** Buffers and normalizes a chapter; on anything unexpected the original bytes are served. */
    static InputStream normalize(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[65536];
        int r;
        while ((r = in.read(chunk)) > 0) {
            buf.write(chunk, 0, r);
            if (buf.size() > MAX) return new SequenceInputStream(new ByteArrayInputStream(buf.toByteArray()), in);
        }
        in.close();
        byte[] raw = buf.toByteArray();
        try { return new ByteArrayInputStream(normalize(raw)); }
        catch (Throwable t) { return new ByteArrayInputStream(raw); }
    }

    static byte[] normalize(byte[] raw) {
        return expand(decode(raw)).getBytes(StandardCharsets.UTF_8);
    }

    /** Spells out every self-closing non-void element as an explicit open/close pair. */
    static String expand(String s) {
        if (s.indexOf("/>") >= 0) {
            Matcher m = SELF_CLOSING.matcher(s);
            StringBuffer sb = new StringBuffer(s.length() + 256);
            while (m.find()) {
                String name = m.group(1);
                String local = name.substring(name.indexOf(':') + 1).toLowerCase(Locale.ROOT);
                String rep = VOID.contains(local) ? m.group() : "<" + name + m.group(2) + "></" + name + ">";
                m.appendReplacement(sb, Matcher.quoteReplacement(rep));
            }
            m.appendTail(sb);
            s = sb.toString();
        }
        return BR_CLOSE.matcher(s).replaceAll("");
    }

    /**
     * BOM first; then bytes that are valid UTF-8 are UTF-8 whatever they claim, because converters
     * often leave a stale charset="gb2312" behind after re-encoding. Otherwise the declared
     * charset, and for undeclared text that is clearly not UTF-8, GB18030 (a superset of GBK).
     */
    static String decode(byte[] b) {
        int n = b.length;
        if (n >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF)
            return new String(b, 3, n - 3, StandardCharsets.UTF_8);
        if (n >= 2 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF) return new String(b, 2, n - 2, StandardCharsets.UTF_16BE);
        if (n >= 2 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xFE) return new String(b, 2, n - 2, StandardCharsets.UTF_16LE);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException notUtf8) { /* fall through */ }
        Charset declared = declared(b);
        if (declared != null) return new String(b, declared);
        String utf = new String(b, StandardCharsets.UTF_8);
        int bad = 0, high = 0;
        for (int i = 0; i < utf.length(); i++) if (utf.charAt(i) == '\uFFFD') bad++;
        for (byte x : b) if (x < 0) high++;
        // A handful of broken sequences in otherwise UTF-8 text is damage, not another encoding.
        if (bad <= 2 || bad * 200 <= high) return utf;
        return new String(b, Charset.forName("GB18030"));
    }

    /** A non-UTF declared charset from the XML declaration or a meta tag, if Java supports it. */
    private static Charset declared(byte[] b) {
        String probe = new String(b, 0, Math.min(b.length, 1024), StandardCharsets.ISO_8859_1);
        Matcher m = DECLARED.matcher(probe);
        if (!m.find()) return null;
        String name = m.group(1).toLowerCase(Locale.ROOT);
        if (name.startsWith("utf")) return null;
        if (name.equals("gb2312") || name.equals("gbk") || name.equals("x-gbk") || name.equals("cp936") || name.startsWith("gb_2312"))
            name = "GB18030";
        try { return Charset.isSupported(name) ? Charset.forName(name) : null; }
        catch (Exception e) { return null; }
    }
}
