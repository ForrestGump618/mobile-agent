#!/usr/bin/env sh
# Gradle wrapper startup script for POSIX shells.
# Requires JAVA_HOME to point at a JDK 17+.

APP_HOME=$(cd "$(dirname "$0")" && pwd)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "$JAVA_HOME" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD=java
fi

exec "$JAVACMD" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew \
  -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
