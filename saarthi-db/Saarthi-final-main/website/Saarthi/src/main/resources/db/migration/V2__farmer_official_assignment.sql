-- V2: current monitoring assignment for farmers.
--
-- registered_by_official_id (V1) records WHO ORIGINALLY REGISTERED the
-- farmer (audit/history) and is left untouched. assigned_official_id (this
-- migration) records WHO IS CURRENTLY RESPONSIBLE for monitoring the
-- farmer. Reassignment only updates this column; it never creates a new
-- farmer row and there is no assignment-history table in this phase.
--
-- The column is nullable so pre-existing farmer rows survive the migration
-- with assigned_official_id = NULL until the assignment workflow assigns
-- them. ON DELETE SET NULL lets an official be removed without deleting
-- (or orphaning the responsibility link of) the farmer. SQL is kept to
-- standard constructs shared by PostgreSQL and H2.

ALTER TABLE farmer ADD COLUMN assigned_official_id BIGINT;

ALTER TABLE farmer ADD CONSTRAINT fk_farmer_assigned_official
    FOREIGN KEY (assigned_official_id)
    REFERENCES panchayat_official (id) ON DELETE SET NULL;

CREATE INDEX idx_farmer_assigned_official ON farmer (assigned_official_id);
