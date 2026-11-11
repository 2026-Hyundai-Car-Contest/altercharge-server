#!/bin/sh
set -eu

# The image's POSTGRES_USER is the bootstrap administrator, never the app user.
if [ "$POSTGRES_APP_USER" = "$POSTGRES_USER" ]; then
    echo "The application role must differ from the PostgreSQL administrator." >&2
    exit 1
fi

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set=ON_ERROR_STOP=1 \
    --set=app_user="$POSTGRES_APP_USER" \
    --set=app_password="$POSTGRES_APP_PASSWORD" \
    --set=app_db="$POSTGRES_DB" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION',
              :'app_user', :'app_password') \gexec
REVOKE ALL ON DATABASE :"app_db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"app_db" TO :"app_user";
REVOKE ALL ON SCHEMA public FROM PUBLIC;
-- The app owns only its schema objects; startup schema migrations need CREATE.
GRANT USAGE, CREATE ON SCHEMA public TO :"app_user";
SQL
