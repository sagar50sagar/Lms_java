-- Department assignments must reach employees who join the department later, so the department
-- a grant was made to has to survive the expansion into per-user rows.
ALTER TABLE course_assignments
    ADD COLUMN IF NOT EXISTS assigned_department_id INT REFERENCES departments(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_assignments_assigned_department
    ON course_assignments(assigned_department_id)
    WHERE assigned_department_id IS NOT NULL;
