#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
if [ ! -f gradlew ]; then
  echo "生成 Gradle Wrapper..."
  gradle wrapper --gradle-version 8.10.2 || {
    echo "请先安装 Gradle，或手动放入 gradlew"
    exit 1
  }
fi
./gradlew run
