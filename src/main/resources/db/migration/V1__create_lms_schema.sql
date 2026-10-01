-- ==============================================================================
-- Corporate Employee Training & Compliance LMS Database Schema
-- Roles: admin, trainer, employee
-- ==============================================================================

-- 1. Departments Table
CREATE TABLE IF NOT EXISTS departments (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) UNIQUE NOT NULL,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 2. Users Table (Strictly 3 roles: admin, trainer, employee)
CREATE TABLE IF NOT EXISTS users (
    id SERIAL PRIMARY KEY,
    employee_id VARCHAR(50) UNIQUE,
    full_name VARCHAR(150) NOT NULL,
    email VARCHAR(150) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(30) NOT NULL CHECK (role IN ('admin', 'trainer', 'employee')),
    department_id VARCHAR(10) REFERENCES departments(id) ON DELETE SET NULL,
    designation VARCHAR(100),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Trainer-Department Assignments (which departments a trainer manages)
CREATE TABLE IF NOT EXISTS trainer_departments (
    id SERIAL PRIMARY KEY,
    trainer_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    department_id VARCHAR(10) NOT NULL REFERENCES departments(id) ON DELETE CASCADE,
    assigned_by INT REFERENCES users(id) ON DELETE SET NULL,
    assigned_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(trainer_id, department_id)
);

-- 3. Training Courses Table
CREATE TABLE IF NOT EXISTS courses (
    id SERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    slug VARCHAR(200) UNIQUE NOT NULL,
    description TEXT,
    category VARCHAR(100) DEFAULT 'General', -- 'Compliance', 'Technical', 'Soft Skills', 'Onboarding'
    is_mandatory BOOLEAN DEFAULT FALSE,
    estimated_duration_hours NUMERIC(4,1) DEFAULT 1.0,
    created_by INT REFERENCES users(id) ON DELETE SET NULL,
    is_published BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 4. Course Chapters / Modules
CREATE TABLE IF NOT EXISTS chapters (
    id SERIAL PRIMARY KEY,
    course_id INT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL,
    sequence_order INT DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 5. Individual Lessons
CREATE TABLE IF NOT EXISTS lessons (
    id SERIAL PRIMARY KEY,
    chapter_id INT NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
    course_id INT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL,
    content_type VARCHAR(30) DEFAULT 'text', -- 'text', 'video'
    content TEXT,
    video_url TEXT,
    duration_mins INT DEFAULT 10,
    sequence_order INT DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 6. Course Assignments (Targeted to Employee with Deadlines)
CREATE TABLE IF NOT EXISTS course_assignments (
    id SERIAL PRIMARY KEY,
    course_id INT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    assigned_by INT REFERENCES users(id) ON DELETE SET NULL,
    due_date DATE,
    status VARCHAR(30) DEFAULT 'enrolled' CHECK (status IN ('enrolled', 'in_progress', 'completed', 'overdue')),
    enrolled_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    UNIQUE(course_id, user_id)
);

-- 7. Lesson Completion Tracker
CREATE TABLE IF NOT EXISTS lesson_progress (
    id SERIAL PRIMARY KEY,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    lesson_id INT NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,
    course_id INT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    completed BOOLEAN DEFAULT TRUE,
    completed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, lesson_id)
);

-- 8. Quizzes / Knowledge Checks
CREATE TABLE IF NOT EXISTS quizzes (
    id SERIAL PRIMARY KEY,
    course_id INT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL,
    passing_score INT DEFAULT 70, -- Minimum percentage needed to pass
    time_limit_mins INT DEFAULT 15,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 9. Quiz Questions
CREATE TABLE IF NOT EXISTS quiz_questions (
    id SERIAL PRIMARY KEY,
    quiz_id INT NOT NULL REFERENCES quizzes(id) ON DELETE CASCADE,
    question_type VARCHAR(20) DEFAULT 'mcq', -- 'mcq' (single choice), 'msq' (multiple select)
    question_text TEXT NOT NULL,
    options JSONB NOT NULL, -- e.g. [{"id": "A", "text": "Option 1"}, {"id": "B", "text": "Option 2"}]
    correct_option VARCHAR(50) NOT NULL, -- e.g. "A" or "A,C"
    explanation TEXT,
    sequence_order INT DEFAULT 1
);

-- 10. Quiz Submissions & Grades
CREATE TABLE IF NOT EXISTS quiz_submissions (
    id SERIAL PRIMARY KEY,
    quiz_id INT NOT NULL REFERENCES quizzes(id) ON DELETE CASCADE,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    score INT NOT NULL,
    total_questions INT NOT NULL,
    percentage NUMERIC(5,2) NOT NULL,
    passed BOOLEAN NOT NULL,
    answers JSONB,
    submitted_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 11. Internal Certificates of Completion
CREATE TABLE IF NOT EXISTS certificates (
    id SERIAL PRIMARY KEY,
    certificate_code VARCHAR(100) UNIQUE NOT NULL,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    course_id INT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    issued_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, course_id)
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_role ON users(role);
CREATE INDEX IF NOT EXISTS idx_users_department ON users(department_id);
CREATE INDEX IF NOT EXISTS idx_courses_category ON courses(category);
CREATE INDEX IF NOT EXISTS idx_assignments_user ON course_assignments(user_id);
CREATE INDEX IF NOT EXISTS idx_assignments_course ON course_assignments(course_id);
CREATE INDEX IF NOT EXISTS idx_progress_user ON lesson_progress(user_id, course_id);
CREATE INDEX IF NOT EXISTS idx_trainer_departments ON trainer_departments(trainer_id);
