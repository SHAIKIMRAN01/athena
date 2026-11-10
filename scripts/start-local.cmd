@echo off
rem Starts Athena for development, each part in its own window:
rem   the API      http://localhost:8080   (reads the password and JWT key from .env)
rem   the website  http://localhost:5173   (forwards /api to the API)
rem Run scripts\setup-local.cmd once first. Close a window to stop that part.
setlocal
set "ROOT=%~dp0.."
if not exist "%ROOT%\.env" (
  echo No .env file yet. Run scripts\setup-local.cmd first.
  exit /b 1
)
start "Athena API" cmd /k "cd /d "%ROOT%\backend\athena" && mvnw spring-boot:run"
start "Athena website" cmd /k "cd /d "%ROOT%\frontend" && npm run dev"
echo Starting. In a minute open http://localhost:5173
