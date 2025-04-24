#!/usr/bin/env bash
set -euo pipefail

output_file="output.txt"

> "$output_file"

echo "=== PROJECT FILE STRUCTURE ===" >> "$output_file"
find . -type f -not -path "*/\.*" -not -path "*/venv/*" | sort | sed -e 's/[^-][^\/]*\//  |/g' -e 's/|\([^ ]\)/|-\1/' >> "$output_file"
echo -e "\n" >> "$output_file"

while IFS= read -r -d '' path; do
  if [ -d "$path" ]; then
    echo "${path}/" >> "$output_file"
  else
    if [[ "$path" == *.py || "$path" == *.proto ]]; then
      echo -e "\n--- File: $path ---" >> "$output_file"
      cat "$path" >> "$output_file"
    fi
  fi
done < <(find . -not -path "*/\.*" -not -path "*/venv/*" -print0)

echo "Saved to $output_file"
