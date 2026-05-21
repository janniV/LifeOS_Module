package lifesuite;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Wandelt den eingegebenen Markdown-Text über Suchmuster in HTML um und baut daraus das
// vollständige Vorschaudokument für die WebView. Die Konvertierung arbeitet in mehreren
// Schritten: Zuerst werden Bereiche mit Sonderbehandlung (Code, Stickies, Formeln) gegen
// Platzhalter getauscht, dann wird der restliche Text HTML-sicher gemacht, in Blöcke und
// Inline-Auszeichnungen zerlegt und am Ende werden die Platzhalter wieder eingesetzt.
public final class MarkdownRenderer {

    // Suchmuster für Überschriften, damit Inhaltsverzeichnis und Vorschau dieselbe Erkennung nutzen.
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

    // Begrenzungszeichen für Platzhalter. Es werden Zeichen aus dem privaten Unicode-Bereich
    // verwendet, da diese in normalem Markdown-Text nicht vorkommen und weder vom HTML-Escaping
    // noch von den Markdown-Suchmustern verändert werden.
    private static final String TOKEN_OPEN = "";
    private static final String TOKEN_CLOSE = "";

    // Suchmuster zum Wiederauffinden eingesetzter Platzhalter.
    private static final Pattern TOKEN = Pattern.compile(TOKEN_OPEN + "(\\d+)" + TOKEN_CLOSE);

    private MarkdownRenderer() {
        // Werkzeugklasse ohne Instanzen.
    }

    // Beschreibt eine erkannte Überschrift für das Live-Inhaltsverzeichnis.
    public static final class Heading {
        public final int level;
        public final String text;
        public final String id;
        public final int line;

        Heading(int level, String text, String id, int line) {
            this.level = level;
            this.text = text;
            this.id = id;
            this.line = line;
        }

        @Override
        public String toString() {
            // Die Einrückung im Inhaltsverzeichnis ergibt sich aus der Überschriftenebene.
            StringBuilder prefix = new StringBuilder();
            for (int i = 1; i < level; i++) {
                prefix.append("    ");
            }
            return prefix + text;
        }
    }

    // Liest alle Überschriften aus dem Markdown-Text aus. Zeilen innerhalb von Code-Blöcken
    // werden übersprungen, damit dort stehende Rauten nicht als Überschrift gewertet werden.
    public static List<Heading> extractHeadings(String markdown) {
        List<Heading> result = new ArrayList<>();
        if (markdown == null) {
            return result;
        }
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        boolean insideCode = false;
        int counter = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("```")) {
                insideCode = !insideCode;
                continue;
            }
            if (insideCode) {
                continue;
            }
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                int level = m.group(1).length();
                String text = m.group(2).trim();
                result.add(new Heading(level, text, "lsh-" + counter, i));
                counter++;
            }
        }
        return result;
    }

    // Erzeugt das vollständige HTML-Dokument für die WebView-Vorschau.
    // Die Layout-Eigenschaften (Seitenformat, Ränder, Schrift, Ausrichtung, Silbentrennung)
    // stammen aus dem Dokumentmodell; dark steuert die Farbgebung; presentation schaltet auf
    // die Foliendarstellung des Präsentationsmodus um.
    public static String buildPreviewDocument(EditorDocument doc, boolean dark, boolean presentation) {
        String body = presentation
            ? buildSlides(doc.getContent())
            : "<div class=\"page\">" + renderBody(doc.getContent()) + "</div>";
        return "<!DOCTYPE html><html lang=\"de\"><head><meta charset=\"UTF-8\">"
            + buildStyle(doc, dark, presentation)
            + buildMathJax()
            + "</head><body class=\"" + (presentation ? "presentation" : "document") + "\">"
            + body
            + buildScript(presentation)
            + "</body></html>";
    }

    // Wandelt einen Markdown-Text in den inneren HTML-Rumpf um (ohne Seitenrahmen und Kopf).
    public static String renderBody(String markdown) {
        if (markdown == null) {
            return "";
        }
        List<String> stash = new ArrayList<>();
        String text = markdown.replace("\r\n", "\n");

        // Sonderbereiche werden vor dem HTML-Escaping gegen Platzhalter getauscht, damit ihr
        // Inhalt nicht durch die Markdown-Inline-Regeln verändert wird.
        text = stashFencedCode(text, stash);
        text = stashStickies(text, stash);
        text = stashInlineCode(text, stash);
        text = stashMath(text, stash);

        text = escapeHtml(text);
        String html = renderBlocks(text, stash);
        return restore(html, stash);
    }

    // Tauscht eingezäunte Code-Blöcke gegen Block-Platzhalter aus.
    private static String stashFencedCode(String text, List<String> stash) {
        Pattern p = Pattern.compile("(?s)```[^\\n]*\\n(.*?)```");
        Matcher m = p.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String code = m.group(1);
            if (code.endsWith("\n")) {
                code = code.substring(0, code.length() - 1);
            }
            String value = "<pre class=\"code-block\"><code>" + escapeHtml(code) + "</code></pre>";
            m.appendReplacement(sb, Matcher.quoteReplacement(blockToken(stash, value)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // Tauscht Sticky-Blöcke (:::sticky ... :::) gegen Block-Platzhalter aus.
    private static String stashStickies(String text, List<String> stash) {
        Pattern p = Pattern.compile("(?s):::sticky\\s*\\n(.*?)\\n:::");
        Matcher m = p.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String inner = escapeHtml(m.group(1).trim()).replace("\n", "<br>");
            String value = "<div class=\"sticky-note\">" + inner + "</div>";
            m.appendReplacement(sb, Matcher.quoteReplacement(blockToken(stash, value)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // Tauscht Inline-Code gegen Inline-Platzhalter aus.
    private static String stashInlineCode(String text, List<String> stash) {
        Pattern p = Pattern.compile("`([^`\\n]+?)`");
        Matcher m = p.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = "<code class=\"inline-code\">" + escapeHtml(m.group(1)) + "</code>";
            m.appendReplacement(sb, Matcher.quoteReplacement(inlineToken(stash, value)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // Tauscht Block- und Inline-Formeln gegen Platzhalter aus. Die Formeln bleiben als rohe
    // MathJax-Syntax erhalten, damit das in der Vorschau geladene MathJax sie setzen kann.
    private static String stashMath(String text, List<String> stash) {
        Pattern display = Pattern.compile("(?s)\\$\\$(.+?)\\$\\$");
        Matcher dm = display.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (dm.find()) {
            String value = "<span class=\"math-display\">$$" + dm.group(1) + "$$</span>";
            dm.appendReplacement(sb, Matcher.quoteReplacement(inlineToken(stash, value)));
        }
        dm.appendTail(sb);

        Pattern inline = Pattern.compile("\\$([^$\\n]+?)\\$");
        Matcher im = inline.matcher(sb.toString());
        StringBuilder out = new StringBuilder();
        while (im.find()) {
            String value = "<span class=\"math-inline\">$" + im.group(1) + "$</span>";
            im.appendReplacement(out, Matcher.quoteReplacement(inlineToken(stash, value)));
        }
        im.appendTail(out);
        return out.toString();
    }

    // Legt einen Wert im Zwischenspeicher ab und liefert einen alleinstehenden Block-Platzhalter.
    private static String blockToken(List<String> stash, String value) {
        stash.add(value);
        return "\n" + TOKEN_OPEN + (stash.size() - 1) + TOKEN_CLOSE + "\n";
    }

    // Legt einen Wert im Zwischenspeicher ab und liefert einen Inline-Platzhalter.
    private static String inlineToken(List<String> stash, String value) {
        stash.add(value);
        return TOKEN_OPEN + (stash.size() - 1) + TOKEN_CLOSE;
    }

    // Setzt alle zwischengespeicherten Werte wieder in das erzeugte HTML ein.
    private static String restore(String html, List<String> stash) {
        Matcher m = TOKEN.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int index = Integer.parseInt(m.group(1));
            String value = index < stash.size() ? stash.get(index) : "";
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // Zerlegt den Text in Blöcke (Überschriften, Zitate, Listen, Tabellen, Absätze) und
    // erzeugt daraus das HTML-Gerüst.
    private static String renderBlocks(String text, List<String> stash) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        List<String> paragraph = new ArrayList<>();
        int headingCounter = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Ein alleinstehender Block-Platzhalter wird unverändert übernommen und später ersetzt.
            if (TOKEN.matcher(trimmed).matches()) {
                flushParagraph(out, paragraph);
                out.append(trimmed);
                continue;
            }
            if (trimmed.isEmpty()) {
                flushParagraph(out, paragraph);
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                flushParagraph(out, paragraph);
                int level = heading.group(1).length();
                String id = "lsh-" + headingCounter;
                headingCounter++;
                out.append("<h").append(level).append(" id=\"").append(id).append("\">")
                   .append(applyInline(heading.group(2).trim()))
                   .append("</h").append(level).append(">");
                continue;
            }
            if (trimmed.matches("(-{3,}|\\*{3,}|_{3,})")) {
                flushParagraph(out, paragraph);
                out.append("<hr>");
                continue;
            }
            // Das Zitatzeichen liegt nach dem HTML-Escaping als &gt; vor und wird so erkannt.
            if (trimmed.startsWith("&gt;")) {
                flushParagraph(out, paragraph);
                List<String> quote = new ArrayList<>();
                while (i < lines.length && lines[i].trim().startsWith("&gt;")) {
                    quote.add(lines[i].trim().replaceFirst("^&gt;\\s?", ""));
                    i++;
                }
                i--;
                out.append("<blockquote>").append(applyInline(String.join("<br>", quote)))
                   .append("</blockquote>");
                continue;
            }
            if (isTableStart(lines, i)) {
                flushParagraph(out, paragraph);
                i = renderTable(lines, i, out);
                continue;
            }
            if (trimmed.matches("[-*+]\\s+.*")) {
                flushParagraph(out, paragraph);
                out.append("<ul>");
                while (i < lines.length && lines[i].trim().matches("[-*+]\\s+.*")) {
                    String item = lines[i].trim().replaceFirst("^[-*+]\\s+", "");
                    out.append("<li>").append(applyInline(item)).append("</li>");
                    i++;
                }
                i--;
                out.append("</ul>");
                continue;
            }
            if (trimmed.matches("\\d+\\.\\s+.*")) {
                flushParagraph(out, paragraph);
                out.append("<ol>");
                while (i < lines.length && lines[i].trim().matches("\\d+\\.\\s+.*")) {
                    String item = lines[i].trim().replaceFirst("^\\d+\\.\\s+", "");
                    out.append("<li>").append(applyInline(item)).append("</li>");
                    i++;
                }
                i--;
                out.append("</ol>");
                continue;
            }
            paragraph.add(trimmed);
        }
        flushParagraph(out, paragraph);
        return out.toString();
    }

    // Schreibt einen gesammelten Absatz als <p>-Element und leert den Sammelpuffer.
    private static void flushParagraph(StringBuilder out, List<String> paragraph) {
        if (paragraph.isEmpty()) {
            return;
        }
        out.append("<p>").append(applyInline(String.join(" ", paragraph))).append("</p>");
        paragraph.clear();
    }

    // Prüft, ob an der angegebenen Zeile eine Markdown-Tabelle beginnt. Eine Tabelle liegt vor,
    // wenn die Folgezeile aus Trennstrichen mit senkrechten Strichen besteht.
    private static boolean isTableStart(String[] lines, int index) {
        if (index + 1 >= lines.length) {
            return false;
        }
        String header = lines[index].trim();
        String separator = lines[index + 1].trim();
        return header.contains("|")
            && separator.matches("\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?");
    }

    // Erzeugt aus den Tabellenzeilen ein HTML-Tabellenelement und liefert den Index der letzten
    // verarbeiteten Zeile zurück.
    private static int renderTable(String[] lines, int index, StringBuilder out) {
        out.append("<table class=\"doc-table\"><thead><tr>");
        for (String cell : splitTableRow(lines[index])) {
            out.append("<th>").append(applyInline(cell.trim())).append("</th>");
        }
        out.append("</tr></thead><tbody>");
        int i = index + 2;
        while (i < lines.length && lines[i].contains("|") && !lines[i].trim().isEmpty()) {
            out.append("<tr>");
            for (String cell : splitTableRow(lines[i])) {
                out.append("<td>").append(applyInline(cell.trim())).append("</td>");
            }
            out.append("</tr>");
            i++;
        }
        out.append("</tbody></table>");
        return i - 1;
    }

    // Zerlegt eine Tabellenzeile an den senkrechten Strichen in einzelne Zellen.
    private static String[] splitTableRow(String row) {
        String trimmed = row.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.split("\\|", -1);
    }

    // Wendet die Inline-Auszeichnungen (Bilder, Links, Wiki-Verweise, fett, kursiv, unterstrichen)
    // auf einen bereits HTML-sicheren Textabschnitt an.
    private static String applyInline(String text) {
        String result = text;
        result = result.replaceAll("!\\[([^\\]]*)\\]\\(([^)]+)\\)",
            "<img src=\"$2\" alt=\"$1\">");
        result = result.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)",
            "<a href=\"$2\">$1</a>");
        // Erkannte Literaturverweise werden als Wiki-Link sichtbar hervorgehoben.
        result = result.replaceAll("\\[\\[([^\\]]+)\\]\\]",
            "<span class=\"wiki-link\">$1</span>");
        result = result.replaceAll("\\*\\*([^*]+?)\\*\\*", "<strong>$1</strong>");
        result = result.replaceAll("\\+\\+([^+]+?)\\+\\+", "<u>$1</u>");
        result = result.replaceAll("(?<![*\\w])\\*([^*\\n]+?)\\*(?![*\\w])", "<em>$1</em>");
        return result;
    }

    // Ersetzt die HTML-Sonderzeichen, damit eingegebener Text nicht als Markup interpretiert wird.
    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;");
    }

    // Teilt den Markdown-Text für den Präsentationsmodus an den Trennlinien in Folien auf.
    private static String buildSlides(String markdown) {
        String text = markdown == null ? "" : markdown.replace("\r\n", "\n");
        String[] parts = text.split("(?m)^---\\s*$");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            sb.append("<section class=\"slide").append(i == 0 ? " active" : "").append("\">")
              .append("<div class=\"slide-inner\">")
              .append(renderBody(parts[i]))
              .append("</div></section>");
        }
        sb.append("<div class=\"slide-indicator\"><span id=\"slide-pos\">1</span> / ")
          .append(parts.length).append("</div>");
        return sb.toString();
    }

    // Baut den eingebetteten CSS-Block des Vorschaudokuments. Sämtliche Layout-Eigenschaften
    // des Dokuments werden hier in konkrete Stilregeln übersetzt.
    private static String buildStyle(EditorDocument doc, boolean dark, boolean presentation) {
        String outerBg = dark ? "#1a1714" : "#e9e5dd";
        String pageBg = dark ? "#262220" : "#ffffff";
        String defaultText = dark ? "#e8e0d4" : "#222222";
        String text = (doc.getTextColor() == null || doc.getTextColor().isBlank())
            ? defaultText : doc.getTextColor();
        String accent = dark ? "#e07840" : "#c75f29";
        String muted = dark ? "#9a9080" : "#6b6055";
        String hyphens = doc.isHyphenation()
            ? "hyphens:auto;-webkit-hyphens:auto;" : "hyphens:manual;";

        return "<style>"
            + "html,body{margin:0;padding:0;}"
            + "body{background:" + outerBg + ";font-family:'" + doc.getFontFamily()
            + "',sans-serif;}"
            + "body.document{padding:24px;display:flex;justify-content:center;align-items:flex-start;}"
            + ".page{box-sizing:border-box;background:" + pageBg + ";color:" + text + ";"
            + "width:" + doc.pageWidthMm() + "mm;min-height:" + doc.pageHeightMm() + "mm;"
            + "padding:" + doc.getMarginMm() + "mm;"
            + "font-size:" + doc.getFontSizePt() + "pt;text-align:" + doc.getAlignment() + ";"
            + hyphens
            + "box-shadow:0 6px 28px rgba(0,0,0,0.28);line-height:1.6;}"
            + ".page h1,.page h2,.page h3,.page h4{font-family:'Merriweather',serif;line-height:1.25;}"
            + ".page h1{font-size:1.9em;border-bottom:2px solid " + accent + ";padding-bottom:.2em;}"
            + ".page h2{font-size:1.5em;}"
            + ".page h3{font-size:1.25em;}"
            + "blockquote{margin:1em 0;padding:.4em 1em;border-left:4px solid " + accent + ";"
            + "color:" + muted + ";background:rgba(199,95,41,0.07);}"
            + "pre.code-block{background:" + (dark ? "#1a1714" : "#f1ebe1") + ";color:" + text + ";"
            + "padding:.8em 1em;border-radius:6px;overflow-x:auto;font-family:monospace;"
            + "text-align:left;}"
            + "code.inline-code{background:rgba(127,127,127,0.18);padding:.1em .35em;"
            + "border-radius:4px;font-family:monospace;}"
            + "table.doc-table{border-collapse:collapse;width:100%;margin:1em 0;}"
            + "table.doc-table th,table.doc-table td{border:1px solid " + (dark ? "#3a352f" : "#d8cfc0")
            + ";padding:.4em .6em;text-align:left;}"
            + "table.doc-table th{background:" + (dark ? "#322c27" : "#f1ebe1") + ";}"
            + ".wiki-link{color:" + accent + ";border-bottom:1px dashed " + accent + ";"
            + "background:rgba(199,95,41,0.10);padding:0 .15em;border-radius:3px;}"
            + "a{color:" + accent + ";}"
            + "img{max-width:100%;height:auto;}"
            + ".sticky-note{display:inline-block;position:relative;margin:.6em;padding:1em;"
            + "min-width:120px;max-width:240px;background:#ffe8a3;color:#3a3320;"
            + "box-shadow:0 4px 12px rgba(0,0,0,0.25);transform:rotate(-1.4deg);cursor:grab;"
            + "font-family:sans-serif;text-align:left;}"
            + buildPresentationStyle(presentation, dark, accent)
            + "</style>";
    }

    // Liefert die zusätzlichen Stilregeln des Präsentationsmodus.
    private static String buildPresentationStyle(boolean presentation, boolean dark, String accent) {
        if (!presentation) {
            return "";
        }
        return "body.presentation{height:100vh;overflow:hidden;background:"
            + (dark ? "#14110f" : "#2a2520") + ";}"
            + ".slide{display:none;height:100vh;box-sizing:border-box;padding:8vh 10vw;"
            + "color:" + (dark ? "#e8e0d4" : "#f5efe6") + ";font-size:24pt;}"
            + ".slide.active{display:flex;align-items:center;}"
            + ".slide-inner{width:100%;}"
            + ".slide h1{color:" + accent + ";font-size:2.2em;}"
            + ".slide-indicator{position:fixed;bottom:16px;right:24px;color:#9a9080;"
            + "font-family:sans-serif;font-size:14pt;}";
    }

    // Bindet MathJax für den Satz mathematischer Formeln ein.
    private static String buildMathJax() {
        return "<script>window.MathJax={tex:{inlineMath:[['$','$']],"
            + "displayMath:[['$$','$$']]},startup:{typeset:true}};</script>"
            + "<script async src=\"https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js\">"
            + "</script>";
    }

    // Baut das Skript der Vorschau. Es meldet die Inhaltshöhe über die JavaBridge zurück,
    // macht Stickies verschiebbar und steuert im Präsentationsmodus die Foliennavigation.
    private static String buildScript(boolean presentation) {
        StringBuilder sb = new StringBuilder("<script>");
        sb.append("function lsReportHeight(){try{if(window.javaBridge){")
          .append("window.javaBridge.reportHeight(document.documentElement.scrollHeight);}}")
          .append("catch(e){}}");
        sb.append("window.addEventListener('load',function(){lsReportHeight();");
        sb.append("setTimeout(lsReportHeight,400);setTimeout(lsReportHeight,1500);});");
        // Stickies werden innerhalb der Seite frei verschiebbar gemacht.
        sb.append("document.querySelectorAll('.sticky-note').forEach(function(n){")
          .append("var dx=0,dy=0,ox=0,oy=0;")
          .append("n.addEventListener('mousedown',function(e){ox=e.clientX-dx;oy=e.clientY-dy;")
          .append("function mv(ev){dx=ev.clientX-ox;dy=ev.clientY-oy;")
          .append("n.style.left=dx+'px';n.style.top=dy+'px';}")
          .append("function up(){document.removeEventListener('mousemove',mv);")
          .append("document.removeEventListener('mouseup',up);}")
          .append("document.addEventListener('mousemove',mv);")
          .append("document.addEventListener('mouseup',up);e.preventDefault();});});");
        if (presentation) {
            // Die Foliennavigation reagiert auf die Pfeiltasten und die Leertaste.
            sb.append("var lsSlides=document.querySelectorAll('.slide');var lsIdx=0;")
              .append("function lsShow(i){if(i<0||i>=lsSlides.length)return;")
              .append("lsSlides[lsIdx].classList.remove('active');lsIdx=i;")
              .append("lsSlides[lsIdx].classList.add('active');")
              .append("var p=document.getElementById('slide-pos');if(p)p.textContent=(i+1);}")
              .append("document.addEventListener('keydown',function(e){")
              .append("if(e.key==='ArrowRight'||e.key==='PageDown'||e.key===' ')lsShow(lsIdx+1);")
              .append("if(e.key==='ArrowLeft'||e.key==='PageUp')lsShow(lsIdx-1);});");
        }
        sb.append("</script>");
        return sb.toString();
    }
}
