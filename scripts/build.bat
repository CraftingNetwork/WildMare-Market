@echo off
call mvn clean test package
if errorlevel 1 exit /b 1
echo WildMare Market build completed.
