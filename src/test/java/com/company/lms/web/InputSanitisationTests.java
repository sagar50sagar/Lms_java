package com.company.lms.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The sanitiser is a deny-by-default gate in front of innerHTML sinks, so its bypass cases are the contract. */
class InputSanitisationTests {

  @Test
  @DisplayName("plain text loses every tag, including fragments that reassemble into one")
  void plainStripsMarkup() {
    assertEquals("alert(1)", ApiSupport.plain("<script>alert(1)</script>", 0));
    assertEquals("ipt>alert(1)", ApiSupport.plain("<scr<script>ipt>alert(1)</script>", 0));
    assertEquals("x", ApiSupport.plain("<img src=x onerror=alert(1)>x", 0));
    assertFalse(ApiSupport.plain("<b onmouseover=\"a()\">t</b>", 0).contains("<"));
  }

  @Test
  @DisplayName("plain text is length bounded and null preserving")
  void plainBoundsLength() {
    assertNull(ApiSupport.plain(null, 10));
    assertEquals(12, ApiSupport.plain("a".repeat(40), 12).length());
  }

  @Test
  @DisplayName("lesson HTML keeps formatting")
  void richKeepsSafeMarkup() {
    assertEquals("<h3>Heading</h3><p>Body text.</p>",
        ApiSupport.richText("<h3>Heading</h3><p>Body text.</p>", 0));
    assertEquals("<a href=\"https://example.com/policy\">policy</a>",
        ApiSupport.richText("<a href=\"https://example.com/policy\" onclick=\"steal()\">policy</a>", 0));
  }

  @Test
  @DisplayName("lesson HTML drops executable constructs")
  void richRemovesExecutables() {
    assertEquals("", ApiSupport.richText("<script>alert(document.cookie)</script>", 0));
    assertEquals("", ApiSupport.richText("<svg onload=alert(1)>", 0));
    assertEquals("", ApiSupport.richText("<iframe src=\"https://evil.example\"></iframe>", 0));
    assertEquals("<p>hi</p>", ApiSupport.richText("<!--<script>bad()</script>--><p>hi</p>", 0));
    assertEquals("<a>click</a>", ApiSupport.richText("<a href=\"javascript:alert(1)\">click</a>", 0));
    assertEquals("<a>click</a>", ApiSupport.richText("<a href=\"JaVaScRiPt:alert(1)\">click</a>", 0));
    assertEquals("<img>", ApiSupport.richText("<img src=x onerror=alert(1)>", 0));
    assertFalse(ApiSupport.richText("<p style=\"background:url(javascript:alert(1))\">t</p>", 0).contains("javascript"));
  }

  @Test
  @DisplayName("embeddable media must be an absolute HTTPS URL")
  void mediaUrlRequiresHttps() {
    assertEquals("https://www.youtube.com/embed/abc", ApiSupport.mediaUrl("https://www.youtube.com/embed/abc"));
    assertNull(ApiSupport.mediaUrl("http://insecure.example/v.mp4"));
    assertNull(ApiSupport.mediaUrl("javascript:alert(1)"));
    assertNull(ApiSupport.mediaUrl("data:text/html,<script>alert(1)</script>"));
    assertNull(ApiSupport.mediaUrl("/relative/path.mp4"));
    assertEquals("", ApiSupport.mediaUrl("  "));
    assertNull(ApiSupport.mediaUrl(null));
  }

  @Test
  @DisplayName("a pasted Google Drive share link is stored as the form that actually plays")
  void driveShareLinksBecomePreviews() {
    String id = "1AbCDEFghij_-1234567890";
    assertEquals("https://drive.google.com/file/d/" + id + "/preview",
        ApiSupport.videoEmbedUrl("https://drive.google.com/file/d/" + id + "/view?usp=sharing"));
    assertEquals("https://drive.google.com/file/d/" + id + "/preview",
        ApiSupport.videoEmbedUrl("https://drive.google.com/open?id=" + id));
    // A folder has no file id, so there is nothing to frame.
    assertNull(ApiSupport.videoEmbedUrl("https://drive.google.com/drive/folders/1234567890abc"));
    assertNull(ApiSupport.videoEmbedUrl("http://drive.google.com/file/d/" + id + "/view"));
    assertEquals("", ApiSupport.videoEmbedUrl("   "));
    // An unrelated host is stored exactly as pasted, since only these two hosts are rewritten.
    assertEquals("https://media.example.com/training.mp4",
        ApiSupport.videoEmbedUrl("https://media.example.com/training.mp4"));
  }

  @Test
  @DisplayName("every pasted YouTube link shape is stored as the nocookie embed")
  void youtubeLinksBecomeNocookieEmbeds() {
    String id = "dQw4w9WgXcQ";
    String embed = "https://www.youtube-nocookie.com/embed/" + id;
    assertEquals(embed, ApiSupport.videoEmbedUrl("https://www.youtube.com/watch?v=" + id));
    assertEquals(embed, ApiSupport.videoEmbedUrl("https://www.youtube.com/watch?app=desktop&v=" + id + "&t=42"));
    assertEquals(embed, ApiSupport.videoEmbedUrl("https://m.youtube.com/shorts/" + id));
    assertEquals(embed, ApiSupport.videoEmbedUrl("https://www.youtube.com/live/" + id));
    assertEquals(embed, ApiSupport.videoEmbedUrl("https://youtu.be/" + id));
    assertEquals(embed, ApiSupport.videoEmbedUrl("https://youtube.com/embed/" + id));
    assertEquals(embed, ApiSupport.videoEmbedUrl(embed), "already-correct links must stay unchanged");

    // A channel, playlist or bare host has no single video to frame, so it fails loudly instead of a blank box.
    assertNull(ApiSupport.videoEmbedUrl("https://www.youtube.com/@someteam"));
    assertNull(ApiSupport.videoEmbedUrl("https://www.youtube.com/watch"));
    assertNull(ApiSupport.videoEmbedUrl("https://www.youtube.com/playlist?list=PL1234567890ab"));
    assertNull(ApiSupport.videoEmbedUrl("https://youtu.be/short"), "too short to be a video id");
    assertNull(ApiSupport.videoEmbedUrl("https://youtu.be/" + id + "extra"), "too long to be one video id");
  }
}
