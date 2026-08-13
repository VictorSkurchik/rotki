#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$project_dir"

./gradlew --no-daemon clean p03SkieFrameworkCheck
./consumer/run.sh
./consumer/typecheck-ios.sh

./gradlew --no-daemon clean \
  -Protki.skie.enabled=false \
  p03SkieFrameworkCheck

framework="$project_dir/build/bin/iosSimulatorArm64/debugFramework/P03Interop.framework"
if [ -e "$framework/Headers/P03Interop-Swift.h" ] || \
   [ -d "$framework/Modules/P03Interop.swiftmodule" ]; then
  echo "SKIE-disabled build retained generated Swift artifacts" >&2
  exit 1
fi
