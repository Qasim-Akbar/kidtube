-- Creates the application role and database. Idempotent. Run on the VPS as:
--     sudo -u postgres psql -v ON_ERROR_STOP=1 -f deploy/postgres/setup.sql
-- Then set the password INTERACTIVELY (it never appears in a file, in `ps` or in shell history):
--     sudo -u postgres psql -c '\password kidtube'
-- Use a strong password without special characters:  openssl rand -hex 24
-- (psql hashes it with SCRAM on the client side before sending it.)

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'kidtube') THEN
        CREATE ROLE kidtube LOGIN
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
END
$$;

SELECT 'CREATE DATABASE kidtube OWNER kidtube ENCODING ''UTF8'' TEMPLATE template0'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'kidtube')
\gexec

-- Nobody but the owner (and superusers) may even connect.
REVOKE ALL ON DATABASE kidtube FROM PUBLIC;
GRANT CONNECT ON DATABASE kidtube TO kidtube;

\connect kidtube
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO kidtube;
