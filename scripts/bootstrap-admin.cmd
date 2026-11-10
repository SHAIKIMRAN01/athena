@echo off
rem Creates Athena's first administrator, only while there is none, with the
rem database settings in .env. Asks for the email, name and password; nothing
rem is saved. Details in bootstrap-admin.ps1.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bootstrap-admin.ps1" %*
