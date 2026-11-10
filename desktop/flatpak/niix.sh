#!/bin/sh
# NiiX launcher inside the Flatpak: the bundled Java runtime, the app's jars, and native access for
# Java-GI, the JDBC driver and libsignal.
exec /app/jre/bin/java \
  --enable-native-access=ALL-UNNAMED \
  -XX:+UseSerialGC \
  -XX:MaxRAMPercentage=25 \
  -Xss2m \
  -Dfile.encoding=UTF-8 \
  -Djava.awt.headless=true \
  -Dniix.launcher=niix \
  -cp "/app/share/niix/lib/*" \
  app.niix.desktop.MainKt "$@"
