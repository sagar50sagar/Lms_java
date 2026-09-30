package com.company.lms.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Development-only sample data. Production deployments should disable it with app.demo-data=false. */
@Configuration
@ConditionalOnProperty(name = "app.demo-data", havingValue = "true", matchIfMissing = true)
class DemoDataInitializer {
 @Bean CommandLineRunner demoData(JdbcTemplate db, PasswordEncoder encoder) { return args -> {
   Integer count=db.queryForObject("SELECT COUNT(*) FROM users",Integer.class); if(count != null && count > 0) return;
   db.update("INSERT INTO departments(name,description) VALUES (?,?),(?,?),(?,?)","Information Security & Compliance","Security and regulatory compliance","Engineering & Product","Software development and quality","Human Resources & Operations","People operations and policies");
   long hr=db.queryForObject("SELECT id FROM departments WHERE name=?",Long.class,"Human Resources & Operations"), engineering=db.queryForObject("SELECT id FROM departments WHERE name=?",Long.class,"Engineering & Product");
   db.update("INSERT INTO users(employee_id,full_name,email,password_hash,role,department_id,designation) VALUES (?,?,?,?,?,?,?),(?,?,?,?,?,?,?),(?,?,?,?,?,?,?)","EMP-001","Sarah Jenkins","admin@company.local",encoder.encode("admin123"),"admin",hr,"Head of Learning & HR","EMP-002","David Miller","trainer@company.local",encoder.encode("trainer123"),"trainer",engineering,"Principal Technical Trainer","EMP-003","Alex Rivera","employee@company.local",encoder.encode("employee123"),"employee",engineering,"Software Engineer");
   long admin=db.queryForObject("SELECT id FROM users WHERE email=?",Long.class,"admin@company.local"), trainer=db.queryForObject("SELECT id FROM users WHERE email=?",Long.class,"trainer@company.local"), employee=db.queryForObject("SELECT id FROM users WHERE email=?",Long.class,"employee@company.local");
   db.update("INSERT INTO trainer_departments(trainer_id,department_id,assigned_by) VALUES(?,?,?)",trainer,engineering,admin);
   long course=db.queryForObject("INSERT INTO courses(title,slug,description,category,is_mandatory,estimated_duration_hours,created_by) VALUES(?,?,?,?,?,?,?) RETURNING id",Long.class,"Cybersecurity Awareness & Data Protection","cybersecurity-awareness-data-protection","Mandatory annual corporate compliance training covering phishing defenses and secure data handling.","Compliance",true,1.5,admin);
   long chapter=db.queryForObject("INSERT INTO chapters(course_id,title,sequence_order) VALUES(?,?,?) RETURNING id",Long.class,course,"Module 1: Identifying Modern Phishing Attacks",1);
   db.update("INSERT INTO lessons(chapter_id,course_id,title,content_type,content,duration_mins,sequence_order) VALUES(?,?,?,?,?,?,?),(?,?,?,?,?,?,?)",chapter,course,"Anatomy of a Spear Phishing Email","text","<h3>Recognizing Phishing Attempts</h3><p>Inspect sender domains and report suspicious messages.</p>",10,1,chapter,course,"Password Hygiene & MFA","text","<p>Use long, unique passwords and phishing-resistant MFA.</p>",12,2);
   long quiz=db.queryForObject("INSERT INTO quizzes(course_id,title,passing_score,time_limit_mins) VALUES(?,?,?,?) RETURNING id",Long.class,course,"Cybersecurity Knowledge Check",75,15);
   db.update("INSERT INTO quiz_questions(quiz_id,question_text,options,correct_option,explanation,sequence_order) VALUES(?,?,?::jsonb,?,?,?)",quiz,"What should you do after entering credentials on a phishing site?","[{\"id\":\"A\",\"text\":\"Wait\"},{\"id\":\"B\",\"text\":\"Notify Security immediately\"}]","B","Fast reporting limits impact.",1);
   db.update("INSERT INTO course_assignments(course_id,user_id,assigned_by,due_date,status) VALUES(?,?,?,CURRENT_DATE+14,'enrolled')",course,employee,admin);
 }; }
}
