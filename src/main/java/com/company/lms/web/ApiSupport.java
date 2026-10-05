package com.company.lms.web;

import org.springframework.security.core.Authentication;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

abstract class ApiSupport {
  @SuppressWarnings("unchecked")
  Map<String,Object> user(Authentication authentication) { return (Map<String,Object>) authentication.getPrincipal(); }

  static Object val(Map<String,Object> map, String key) {
    if (map == null) return null;
    if (map.containsKey(key)) return map.get(key);
    for (Map.Entry<String,Object> entry : map.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
    }
    return null;
  }

  long userId(Authentication authentication) {
    Object idObj = val(user(authentication), "id");
    return idObj != null ? ((Number) idObj).longValue() : 0L;
  }

  String role(Authentication authentication) {
    Object roleObj = val(user(authentication), "role");
    return roleObj != null ? roleObj.toString() : "";
  }

  boolean isAdmin(Authentication authentication) { return "admin".equalsIgnoreCase(role(authentication)); }

  Map<String,Object> ok(Object key, Object value) { Map<String,Object> response = new LinkedHashMap<>(); response.put("success", true); response.put(String.valueOf(key), value); return response; }
  Map<String,Object> message(String value) { return Map.of("success",true,"message",value); }
  RuntimeException bad(String text) { return new IllegalArgumentException(text); }

  // ---------- Input sanitisation ----------
  // Stored text is rendered with innerHTML across the console, so markup is neutralised on write.

  private static final Pattern DEADLY_ELEMENT = Pattern.compile(
      "<\\s*(script|style|iframe|object|embed|applet|form|base|link|meta|svg|math)[\\s\\S]*?(?:</\\s*\\1\\s*>|$)",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern COMMENT = Pattern.compile("<!--[\\s\\S]*?-->");
  private static final Pattern TAG = Pattern.compile("<(/?)\\s*([a-zA-Z][a-zA-Z0-9]*)\\s*((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>");
  private static final Pattern ATTRIBUTE = Pattern.compile(
      "([a-zA-Z][a-zA-Z0-9:-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");
  private static final Set<String> ALLOWED_TAGS = Set.of("p", "br", "hr", "b", "strong", "i", "em", "u", "s",
      "span", "div", "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote",
      "table", "thead", "tbody", "tfoot", "tr", "td", "th", "a", "img", "code", "pre");
  private static final Map<String, Set<String>> ALLOWED_ATTRIBUTES = Map.of(
      "a", Set.of("href"), "img", Set.of("src", "alt"), "td", Set.of("colspan", "rowspan"), "th", Set.of("colspan", "rowspan"));

  /** Single-line fields: every tag is removed, repeatedly, so reassembled fragments cannot survive. */
  static String plain(Object value, int maxLength) {
    if (value == null) return null;
    String text = stripTags(value.toString()).replace("\0", "").trim();
    return maxLength > 0 && text.length() > maxLength ? text.substring(0, maxLength) : text;
  }

  /** Lesson bodies are authored as HTML, so only a conservative allowlist of tags and attributes is kept. */
  static String richText(Object value, int maxLength) {
    if (value == null) return null;
    String html = DEADLY_ELEMENT.matcher(value.toString()).replaceAll("");
    html = COMMENT.matcher(html).replaceAll("");
    StringBuilder out = new StringBuilder();
    Matcher tags = TAG.matcher(html);
    while (tags.find()) {
      String name = tags.group(2).toLowerCase(Locale.ROOT);
      String replacement = "";
      if (ALLOWED_TAGS.contains(name)) {
        replacement = "/".equals(tags.group(1)) ? "</" + name + ">" : openTag(name, tags.group(3));
      }
      tags.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    tags.appendTail(out);
    String cleaned = out.toString().replace("\0", "").trim();
    return maxLength > 0 && cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
  }

  private static String openTag(String name, String rawAttributes) {
    StringBuilder tag = new StringBuilder("<").append(name);
    Set<String> allowed = ALLOWED_ATTRIBUTES.getOrDefault(name, Set.of());
    Matcher attributes = ATTRIBUTE.matcher(rawAttributes == null ? "" : rawAttributes);
    while (attributes.find()) {
      String attribute = attributes.group(1).toLowerCase(Locale.ROOT);
      if (!allowed.contains(attribute)) continue;
      String value = attributes.group(2) != null ? attributes.group(2)
          : attributes.group(3) != null ? attributes.group(3) : attributes.group(4);
      if (ALLOWED_URL_ATTRIBUTES.contains(attribute)) {
        String safe = safeUrl(value);
        if (safe == null) continue;
        value = safe;
      } else {
        value = stripTags(value).replace("\"", "&quot;");
      }
      tag.append(" ").append(attribute).append("=\"").append(value).append("\"");
    }
    return tag.append(">").toString();
  }

  private static final Set<String> ALLOWED_URL_ATTRIBUTES = Set.of("href", "src");

  private static String safeUrl(String url) {
    if (url == null) return null;
    String candidate = stripTags(url).trim();
    String compact = candidate.toLowerCase(Locale.ROOT).replaceAll("\\s", "");
    if (compact.startsWith("javascript:") || compact.startsWith("vbscript:") || compact.startsWith("data:")) return null;
    if (candidate.startsWith("#") || candidate.startsWith("/")) return candidate;
    return compact.startsWith("http://") || compact.startsWith("https://") || compact.startsWith("mailto:") ? candidate : null;
  }

  /**
   * Embeddable media must be an absolute HTTPS URL: a relative or scheme-less value would let an author
   * point an iframe at anything, and javascript: or data: URLs execute in this origin.
   */
  static String mediaUrl(Object value) {
    if (value == null) return null;
    String candidate = value.toString().trim();
    if (candidate.isEmpty()) return "";
    try {
      java.net.URI uri = new java.net.URI(candidate);
      return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isBlank() ? candidate : null;
    } catch (java.net.URISyntaxException e) {
      return null;
    }
  }

  /**
   * Both supported hosts only play at one specific address, while the link an author pastes is a different one:
   * Drive needs /preview, YouTube needs the nocookie /embed form. Rewriting on save is what makes a pasted link
   * play. A URL the host cannot turn into a player (a Drive folder, a YouTube channel page) is rejected here
   * rather than stored and rendered as a blank frame. Unknown hosts are stored unchanged.
   */
  static String videoEmbedUrl(Object value) {
    String url = mediaUrl(value);
    if (url == null || url.isEmpty()) return url;
    String host = java.net.URI.create(url).getHost().toLowerCase(Locale.ROOT);

    if (host.equals("drive.google.com")) {
      var drive = java.util.regex.Pattern.compile("(?:/file/d/|[?&]id=)([\\w-]{10,})").matcher(url);
      return drive.find() ? "https://drive.google.com/file/d/" + drive.group(1) + "/preview" : null;
    }
    if (YOUTUBE_HOSTS.contains(host)) {
      var youtube = YOUTUBE_VIDEO_ID.matcher(url);
      // YouTube video ids are exactly 11 characters; a looser match would rewrite playlist and channel URLs too.
      return youtube.find() ? "https://www.youtube-nocookie.com/embed/" + youtube.group(1) : null;
    }
    return url;
  }

  private static final Set<String> YOUTUBE_HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com",
      "youtube-nocookie.com", "www.youtube-nocookie.com", "youtu.be");

  private static final java.util.regex.Pattern YOUTUBE_VIDEO_ID = java.util.regex.Pattern
      .compile("(?:/watch\\?(?:[^&]+&)*v=|/(?:embed|shorts|live)/|youtu\\.be/)([\\w-]{11})(?![\\w-])");

  private static String stripTags(String input) {
    String current = input, previous;
    do {
      previous = current;
      current = TAG.matcher(current).replaceAll("");
    } while (!current.equals(previous));
    return current;
  }
}
