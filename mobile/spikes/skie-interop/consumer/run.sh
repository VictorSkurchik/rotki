#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
framework_dir="$project_dir/build/bin/macosArm64/debugFramework"
output_dir="$project_dir/build/swift-consumer"

mkdir -p "$output_dir/module-cache"

xcrun swiftc \
  -parse-as-library \
  -module-cache-path "$output_dir/module-cache" \
  -F "$framework_dir" \
  -framework P03Interop \
  "$project_dir/consumer/InteropConsumer.swift" \
  -Xlinker -rpath \
  -Xlinker "$framework_dir" \
  -o "$output_dir/InteropConsumer"

perl -e 'alarm shift; exec @ARGV' 30 "$output_dir/InteropConsumer"
