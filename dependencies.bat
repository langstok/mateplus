@echo off
REM extract lib dir relative to the source folder
cd .\lib
call mvn install:install-file -Dfile=anna-3.3.jar -DgroupId=com.googlecode.mate-tools -DartifactId=anna -Dversion=3.3 -Dpackaging=jar
pause


