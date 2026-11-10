#!/usr/bin/env sh
# Creates Athena's first administrator, only while there is none.
#
#   scripts/bootstrap-admin.sh           the app from this checkout (./mvnw), settings from .env
#   scripts/bootstrap-admin.sh docker    the Docker Compose app and database (docker compose up first)
#   scripts/bootstrap-admin.sh jar       the built jar (/app/app.jar in the image), settings from the environment
#
# Asks for the email, name and password (the password is typed hidden, twice)
# unless BOOTSTRAP_ADMIN_EMAIL, BOOTSTRAP_ADMIN_PASSWORD and BOOTSTRAP_ADMIN_NAME
# are set. They reach the app only through the environment of this one run:
# nothing is written to a file. The app prepares the database, creates the
# administrator and exits. If an administrator exists already it changes
# nothing, so this can never take over or reset one.
#
# On Render's free plan, which has no shell: add BOOTSTRAP_ADMIN_EMAIL and
# BOOTSTRAP_ADMIN_PASSWORD to the service's environment, deploy once, then
# delete them (important/DEPLOYMENT.md).
set -eu

mode="${1:-local}"
root="$(cd "$(dirname "$0")/.." && pwd)"

if [ -z "${BOOTSTRAP_ADMIN_EMAIL:-}" ]; then
    printf 'Administrator email: '
    read -r BOOTSTRAP_ADMIN_EMAIL
fi
if [ -z "${BOOTSTRAP_ADMIN_NAME:-}" ]; then
    printf 'Full name [Athena Administrator]: '
    read -r BOOTSTRAP_ADMIN_NAME
fi
BOOTSTRAP_ADMIN_NAME="${BOOTSTRAP_ADMIN_NAME:-Athena Administrator}"
if [ -z "${BOOTSTRAP_ADMIN_PASSWORD:-}" ]; then
    stty -echo 2>/dev/null || true
    printf 'Password (at least 12 characters, with a letter and a digit): '
    read -r BOOTSTRAP_ADMIN_PASSWORD
    printf '\nThe same password again: '
    read -r again
    stty echo 2>/dev/null || true
    printf '\n'
    if [ "$BOOTSTRAP_ADMIN_PASSWORD" != "$again" ]; then
        echo 'The two passwords differ; nothing was created.'
        exit 1
    fi
fi
if [ -z "$BOOTSTRAP_ADMIN_EMAIL" ] || [ -z "$BOOTSTRAP_ADMIN_PASSWORD" ]; then
    echo 'An email and a password are both needed; nothing was created.'
    exit 1
fi

export BOOTSTRAP_ADMIN_EMAIL BOOTSTRAP_ADMIN_NAME BOOTSTRAP_ADMIN_PASSWORD
export BOOTSTRAP_ONLY=true
# A free port of its own, so it never clashes with an Athena already running.
export SERVER_PORT=0
export SPRING_MAIN_BANNER_MODE=off
export LOGGING_LEVEL_ROOT=WARN
export LOGGING_LEVEL_COM_ATHENA_CONFIG=INFO

echo 'Preparing the database and creating the administrator...'
case "$mode" in
    local)
        cd "$root/backend/athena"
        # Devtools' restarter is off: it would hide a failed start behind exit code 0.
        ./mvnw -q spring-boot:run -Dspring-boot.run.jvmArguments=-Dspring.devtools.restart.enabled=false
        ;;
    docker)
        cd "$root"
        # -e NAME passes the value from this shell, so it never appears in the command line.
        docker compose run --rm --no-TTY \
            -e BOOTSTRAP_ADMIN_EMAIL -e BOOTSTRAP_ADMIN_NAME -e BOOTSTRAP_ADMIN_PASSWORD \
            -e BOOTSTRAP_ONLY -e SERVER_PORT -e SPRING_MAIN_BANNER_MODE \
            -e LOGGING_LEVEL_ROOT -e LOGGING_LEVEL_COM_ATHENA_CONFIG \
            app
        ;;
    jar)
        java -jar "${ATHENA_JAR:-/app/app.jar}"
        ;;
    *)
        echo "Unknown mode '$mode': use local, docker or jar."
        exit 2
        ;;
esac
echo 'Finished. The AdminBootstrap line above says whether the administrator was created'
echo 'or one existed already (then nothing was changed).'
