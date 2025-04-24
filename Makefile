PROTO_DIR = protos
PY_OUT    = amazon_app/pb_generated

protos:
	python3 -m grpc_tools.protoc -I$(PROTO_DIR) --python_out=$(PY_OUT) $(PROTO_DIR)/amazon_ups.proto
	python3 -m grpc_tools.protoc -I$(PROTO_DIR) --python_out=$(PY_OUT) $(PROTO_DIR)/world_amazon-1.proto
	# The protoc output filename already matches our expected name, no need to rename
	touch $(PY_OUT)/__init__.py

.PHONY: clean protos
clean:
	rm -rf $(PY_OUT)/*.py 
