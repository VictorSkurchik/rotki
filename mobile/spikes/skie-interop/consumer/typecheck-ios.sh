#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
simulator_framework="$project_dir/build/bin/iosSimulatorArm64/debugFramework"
device_framework="$project_dir/build/bin/iosArm64/debugFramework"
module_cache="$project_dir/build/swift-consumer/ios-module-cache"

mkdir -p "$module_cache"

xcrun --sdk iphonesimulator swiftc \
  -swift-version 5 \
  -target arm64-apple-ios17.0-simulator \
  -typecheck \
  -parse-as-library \
  -module-cache-path "$module_cache" \
  -F "$simulator_framework" \
  -framework P03Interop \
  "$project_dir/consumer/InteropConsumer.swift"

xcrun --sdk iphoneos swiftc \
  -swift-version 5 \
  -target arm64-apple-ios17.0 \
  -typecheck \
  -parse-as-library \
  -module-cache-path "$module_cache" \
  -F "$device_framework" \
  -framework P03Interop \
  "$project_dir/consumer/InteropConsumer.swift"
