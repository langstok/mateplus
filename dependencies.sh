cd .\lib
wget https://storage.googleapis.com/google-code-archive-downloads/v2/code.google.com/mate-tools/anna-3.3.jar
mvn install:install-file -Dfile=anna-3.3.jar -DgroupId=com.googlecode.mate-tools -DartifactId=anna -Dversion=3.3 -Dpackaging=jar

