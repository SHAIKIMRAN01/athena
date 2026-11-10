@echo off
rem One-time setup for this computer: saves your database password and a new
rem JWT key in .env (git ignores it), creates the databases and installs the
rem website packages. Details in setup-local.ps1. Safe to run again: it never
rem overwrites an existing .env.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-local.ps1" %*
