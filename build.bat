call env.bat
cd gigaspaces-cassandra
call mvn -Dmaven.test.failure.ignore=true clean install > build-log.txt