#!/usr/bin/env bash
set -euo pipefail

output_file="output.txt"

> "$output_file"

while IFS= read -r -d '' path; do
  if [ -d "$path" ]; then
    echo "${path}/" >> "$output_file"
  else
    if [[ "$path" == *.py || "$path" == *.proto ]]; then
      echo -e "\n--- File: $path ---" >> "$output_file"
      cat "$path" >> "$output_file"
    fi
  fi
done < <(find . -print0)

echo "Saved to $output_file"
