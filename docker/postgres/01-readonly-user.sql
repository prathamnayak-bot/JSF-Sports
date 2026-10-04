-- Runs once, when the database volume is first created.
-- A login that can only read the cricket tables: the AI chat's generated SQL runs as this user,
-- so the database itself refuses writes even if a bad query got past SqlGuard.
CREATE ROLE cricket_readonly LOGIN PASSWORD 'cricket_readonly';
ALTER ROLE cricket_readonly SET statement_timeout = '10s';
ALTER ROLE cricket_readonly SET default_transaction_read_only = on;

GRANT CONNECT ON DATABASE cricket TO cricket_readonly;
GRANT USAGE ON SCHEMA public TO cricket_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO cricket_readonly;
-- tables are created later by the backend (schema.sql, as user "cricket"), so grant on future tables too
ALTER DEFAULT PRIVILEGES FOR ROLE cricket IN SCHEMA public GRANT SELECT ON TABLES TO cricket_readonly;
