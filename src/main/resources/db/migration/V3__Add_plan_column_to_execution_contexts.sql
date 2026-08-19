-- Add missing plan column to execution_contexts table
ALTER TABLE execution_contexts ADD COLUMN plan TEXT;
