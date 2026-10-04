-- V6: user role is derived from department management, not set manually.
-- A non-admin with at least one trainer_departments row is a trainer; otherwise an employee.
UPDATE users u SET role='trainer'
WHERE u.role<>'admin'
  AND EXISTS(SELECT 1 FROM trainer_departments td WHERE td.trainer_id=u.id);

UPDATE users u SET role='employee'
WHERE u.role<>'admin'
  AND NOT EXISTS(SELECT 1 FROM trainer_departments td WHERE td.trainer_id=u.id);
