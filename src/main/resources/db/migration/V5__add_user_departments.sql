-- True many-to-many membership between users and departments (employees AND trainers).
-- users.department_id remains the single "primary/home" department used for login/JWT/certificate display.
-- trainer_departments remains the explicit management-rights link for trainers.

CREATE TABLE IF NOT EXISTS user_departments (
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    department_id INT NOT NULL REFERENCES departments(id) ON DELETE CASCADE,
    assigned_by INT REFERENCES users(id) ON DELETE SET NULL,
    assigned_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, department_id)
);

CREATE INDEX IF NOT EXISTS idx_user_departments_department ON user_departments(department_id);
CREATE INDEX IF NOT EXISTS idx_user_departments_user ON user_departments(user_id);

-- Backfill: each existing home department becomes a membership row.
INSERT INTO user_departments(user_id, department_id)
SELECT id, department_id FROM users WHERE department_id IS NOT NULL
ON CONFLICT (user_id, department_id) DO NOTHING;

-- Backfill: every trainer's managed department also counts as membership so it shows in the Users tab.
INSERT INTO user_departments(user_id, department_id, assigned_by)
SELECT trainer_id, department_id, assigned_by FROM trainer_departments
ON CONFLICT (user_id, department_id) DO NOTHING;
