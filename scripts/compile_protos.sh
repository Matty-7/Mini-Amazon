#!/bin/bash

# Script to compile protobuf files to Python

# Set directories
PROTO_DIR="../amazon_app/protocols"
PYTHON_OUT_DIR="../amazon_app/protocols"

# Create the output directory if it doesn't exist
mkdir -p "$PYTHON_OUT_DIR"

# Find all .proto files and compile them
echo "Compiling protobuf files..."
for proto_file in $(find "$PROTO_DIR" -name "*.proto"); do
  echo "Compiling $proto_file..."
  python -m grpc_tools.protoc \
    -I"$PROTO_DIR" \
    --python_out="$PYTHON_OUT_DIR" \
    --grpc_python_out="$PYTHON_OUT_DIR" \
    "$proto_file"
done

echo "Compilation complete!"
