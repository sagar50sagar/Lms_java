package com.company.lms.web;
import org.xhtmlrenderer.pdf.ITextRenderer;
import org.springframework.core.io.ClassPathResource; import org.springframework.http.HttpHeaders; import org.springframework.http.MediaType; import org.springframework.http.ResponseEntity; import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.security.core.Authentication; import org.springframework.web.bind.annotation.*; import java.io.ByteArrayOutputStream; import java.io.InputStream; import java.time.format.DateTimeFormatter; import java.security.SecureRandom; import java.util.*;
@RestController @RequestMapping("/api/progress") public class ProgressController extends ApiSupport {
 private final JdbcTemplate db; private final SecureRandom random=new SecureRandom(); public ProgressController(JdbcTemplate db){this.db=db;}
 @PostMapping("/toggle-lesson") public ResponseEntity<?> toggle(@RequestBody Map<String,Object>b,Authentication a){Object course=b.get("course_id"),lesson=b.get("lesson_id");if(course==null||lesson==null)return ResponseEntity.badRequest().body(Map.of("success",false,"message","Course ID and Lesson ID are required."));boolean done=!Boolean.FALSE.equals(b.get("completed"));if(done)db.update("INSERT INTO lesson_progress(user_id,lesson_id,course_id,completed) VALUES(?,?,?,TRUE) ON CONFLICT(user_id,lesson_id) DO UPDATE SET completed=TRUE,completed_at=CURRENT_TIMESTAMP",userId(a),lesson,course);else db.update("DELETE FROM lesson_progress WHERE user_id=? AND lesson_id=?",userId(a),lesson);db.update("INSERT INTO course_assignments(course_id,user_id,assigned_by,status) VALUES(?,?,?,'in_progress') ON CONFLICT(course_id,user_id) DO UPDATE SET status='in_progress' WHERE course_assignments.status='enrolled'",course,userId(a),userId(a));int total=db.queryForObject("SELECT COUNT(*) FROM lessons WHERE course_id=?",Integer.class,course),completed=db.queryForObject("SELECT COUNT(*) FROM lesson_progress WHERE user_id=? AND course_id=? AND completed=TRUE",Integer.class,userId(a),course);boolean quizPassed=db.queryForObject("SELECT NOT EXISTS(SELECT 1 FROM quizzes q WHERE q.course_id=? AND NOT EXISTS(SELECT 1 FROM quiz_submissions s WHERE s.quiz_id=q.id AND s.user_id=? AND s.passed=TRUE))",Boolean.class,course,userId(a));String code=null;if(total>0&&completed>=total&&quizPassed){db.update("UPDATE course_assignments SET status='completed',completed_at=CURRENT_TIMESTAMP WHERE course_id=? AND user_id=? AND completed_at IS NULL",course,userId(a));code=certificate(course,userId(a));}Map<String,Object> progress=new LinkedHashMap<>();progress.put("total_lessons",total);progress.put("completed_lessons",completed);progress.put("percent",total==0?0:Math.round(completed*100.0/total));progress.put("all_lessons_done",total>0&&completed>=total);progress.put("quiz_passed",quizPassed);progress.put("certificate_issued",code!=null);progress.put("certificate_code",code);return ResponseEntity.ok(Map.of("success",true,"completed",done,"progress",progress));}
 @GetMapping("/certificates/my") public Map<String,Object> mine(Authentication a){return ok("certificates",db.queryForList("SELECT cert.id,cert.certificate_code,cert.issued_at,c.title course_title,c.category course_category,u.full_name employee_name,u.employee_id,d.name department_name FROM certificates cert JOIN courses c ON c.id=cert.course_id JOIN users u ON u.id=cert.user_id LEFT JOIN departments d ON d.id=u.department_id WHERE cert.user_id=? ORDER BY cert.issued_at DESC",userId(a)));}
 @GetMapping("/certificates/verify/{code}") public ResponseEntity<?> verify(@PathVariable String code){var rows=db.queryForList(CERT_SQL,code);return rows.isEmpty()?ResponseEntity.status(404).body(Map.of("success",false,"message","Certificate verification failed: Invalid certificate code.")):ResponseEntity.ok(ok("certificate",rows.getFirst()));}
 private static final String CERT_SQL="SELECT cert.id,cert.certificate_code,cert.issued_at,c.title course_title,c.category course_category,c.estimated_duration_hours,u.full_name employee_name,u.employee_id,u.designation,d.name department_name FROM certificates cert JOIN courses c ON c.id=cert.course_id JOIN users u ON u.id=cert.user_id LEFT JOIN departments d ON d.id=u.department_id WHERE cert.certificate_code=?";
 @GetMapping("/certificates/{code}/pdf") public ResponseEntity<?> pdf(@PathVariable String code){
  var rows=db.queryForList(CERT_SQL,code);
  if(rows.isEmpty())return ResponseEntity.status(404).body(Map.of("success",false,"message","Certificate verification failed: Invalid certificate code."));
  try{
   ITextRenderer renderer=new ITextRenderer();
   renderer.setDocumentFromString(certificateHtml(rows.getFirst()));
   renderer.layout();
   ByteArrayOutputStream out=new ByteArrayOutputStream();
   renderer.createPDF(out);
   renderer.finishPDF();
   String filename="Certificate-"+code.replaceAll("[^A-Za-z0-9_-]","")+".pdf";
   return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
    .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+filename+"\"")
    .header(HttpHeaders.CACHE_CONTROL,"no-store")
    .body(out.toByteArray());
  }catch(Exception e){
   return ResponseEntity.internalServerError().body(Map.of("success",false,"message","Failed to generate certificate PDF."));
  }
 }
 private String certificateHtml(Map<String,Object> cert){
  String logo="";
  try(InputStream in=new ClassPathResource("static/images/qt-logo.jpg").getInputStream()){logo="data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(in.readAllBytes());}catch(Exception ignored){}
  Object issued=cert.get("issued_at");
  String date=issued instanceof java.sql.Timestamp t?t.toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")):"—";
  StringBuilder h=new StringBuilder();
  h.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><style type=\"text/css\">"
   +"@page{size:A4 landscape;margin:0}"
   +"body{margin:0;font-family:Helvetica,sans-serif;color:#1d2433;font-size:11pt}"
   +".frame{border:5mm solid #137aa7;padding:1.6mm}"
   +".frame-in{border:0.7mm solid #f2922c;padding:8mm 14mm 6mm;text-align:center}"
   +".logo{height:22mm}"
   +".eyebrow{color:#f2922c;font-size:9pt;letter-spacing:3pt;font-weight:bold;margin-top:3mm}"
   +"h1{font-family:Times,serif;color:#137aa7;font-size:28pt;letter-spacing:2pt;margin:3mm 0 0;font-weight:bold}"
   +".rule{height:0.6mm;background:#f2922c;width:55%;margin:4mm auto}"
   +".lead{color:#5d6678;font-size:11pt;margin:0}"
   +".name{font-family:Times,serif;font-style:italic;font-weight:bold;color:#137aa7;font-size:24pt;border-bottom:0.5mm solid #f2922c;padding:0 8mm 1.5mm;margin:4mm 0 0}"
   +".emp{color:#5d6678;font-size:9.5pt;margin-top:3mm}"
   +".body-text{color:#1d2433;font-size:10.5pt;margin:4mm auto 0;width:82%;line-height:1.5}"
   +".course{font-size:15pt;font-weight:bold;color:#137aa7;margin:3mm 0 0}"
   +"table.sign{width:100%;margin-top:14mm;border-collapse:collapse}"
   +"td.sig{width:36%;font-size:9pt;color:#5d6678;vertical-align:bottom}"
   +"td.mid{width:28%;vertical-align:bottom;text-align:center}"
   +".hand{font-family:Times,serif;font-style:italic;font-size:17pt;color:#1b2a6b}"
   +".line{border-top:0.4mm solid #137aa7;padding-top:1.5mm;text-align:left}"
   +".line b{display:block;color:#137aa7;font-size:10pt}"
   +".seal{border:1.2mm double #f2922c;padding:3mm 2mm;font-family:Times,serif;font-weight:bold;color:#e07f12;font-size:8pt;letter-spacing:0.5pt;line-height:1.4}"
   +".seal i{display:block;font-size:14pt;font-style:normal}"
   +".ids{margin-top:5mm;font-size:9pt;color:#5d6678}"
   +".ids b{color:#137aa7}"
   +".ids .mono{font-family:Courier,monospace}"
   +".motto{margin-top:3mm;font-size:8.5pt;letter-spacing:2pt;color:#137aa7;font-weight:bold}"
   +".foot{margin-top:2mm;font-size:7.5pt;color:#5d6678;line-height:1.5}"
   +"</style></head><body><div class=\"frame\"><div class=\"frame-in\">");
  if(!logo.isEmpty())h.append("<img class=\"logo\" alt=\"QT Consultancy\" src=\"").append(logo).append("\"/>");
  h.append("<div class=\"eyebrow\">CORPORATE LEARNING &amp; DEVELOPMENT</div>");
  h.append("<h1>Certificate of Completion</h1><div class=\"rule\"></div>");
  h.append("<p class=\"lead\">This official certificate is proudly presented to</p>");
  h.append("<div class=\"name\">").append(esc(cert.get("employee_name"))).append("</div>");
  h.append("<div class=\"emp\">Employee ID: <b>").append(escOr(cert.get("employee_id"),"—")).append("</b> &#8226; <b>").append(escOr(cert.get("department_name"),"General Department")).append("</b> &#8226; <b>").append(escOr(cert.get("designation"),"Staff")).append("</b></div>");
  h.append("<p class=\"body-text\">For successfully completing all instructional modules, satisfying corporate compliance standards, and passing the required assessment exam for:</p>");
  h.append("<div class=\"course\">").append(esc(cert.get("course_title"))).append("</div>");
  h.append("<table class=\"sign\"><tr>"
   +"<td class=\"sig\"><div class=\"hand\">Aakash Giri</div><div class=\"line\"><b>Aakash Giri</b>Director, QT Consultancy</div></td>"
   +"<td class=\"mid\"><div class=\"seal\">VERIFIED<i>&#10003;</i>STANDARD</div></td>"
   +"<td class=\"sig\"><div class=\"hand\">Rupam Bhardwaj</div><div class=\"line\"><b>Rupam Bhardwaj</b>Learning &amp; HR</div></td>"
   +"</tr></table>");
  h.append("<div class=\"ids\">Issued: <b>").append(date).append("</b> &#8226; Verification ID: <b class=\"mono\">").append(esc(cert.get("certificate_code"))).append("</b></div>");
  h.append("<div class=\"motto\">RIGHT CANDIDATE &#160;|&#160; RIGHT TIME &#160;|&#160; RIGHT COMPANY</div>");
  h.append("<div class=\"foot\">QT Consultancy (OPC) Pvt Ltd &#8226; Plot no 5, New Shambhu Nagar Road, Delhi Road, Near Transport Nagar, Mohokampur Phase 1, Meerut, UP &#8211; 250002<br/>hr@qtconsultancy.in &#8226; +91 7830899085 &#8226; CIN: U78100UP2025OPC218928</div>");
  h.append("</div></div></body></html>");
  return h.toString();
 }
 private static String esc(Object v){return escOr(v,"");}
 private static String escOr(Object v,String fallback){if(v==null||v.toString().isBlank())return fallback;String s=v.toString();return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
 String certificate(Object course,long user){var existing=db.queryForList("SELECT certificate_code FROM certificates WHERE course_id=? AND user_id=?",course,user);if(!existing.isEmpty())return (String)existing.getFirst().get("certificate_code");String code="CERT-"+Long.toHexString(random.nextLong()).replace('-','X').toUpperCase()+"-"+(System.currentTimeMillis()%10000);db.update("INSERT INTO certificates(certificate_code,user_id,course_id) VALUES(?,?,?)",code,user,course);return code;}
}
