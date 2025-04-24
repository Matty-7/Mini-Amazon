#!/bin/bash
set -e

echo "== 1. BUY TEST =="
curl -X POST http://127.0.0.1:5001/buy \
     -H 'Content-Type: application/json' \
     -d '{
           "warehouse_id": 1,
           "products": [
             { "id": 101, "description": "book", "count": 5 }
           ]
         }'
echo -e "\n"

sleep 3  # 等待World回应

echo "== 2. PACK TEST =="
curl -X POST http://127.0.0.1:5001/pack \
     -H 'Content-Type: application/json' \
     -d '{
           "warehouse_id": 1,
           "shipment_id": 9001,
           "products": [
             { "id": 101, "description": "book", "count": 5 }
           ]
         }'
echo -e "\n"

echo "Waiting for shipment to be ready..."

tries=0
max_tries=60          # 把10提升到60（≈60秒）
sleep_step=1

while [ $tries -lt $max_tries ]; do
  ready=$(curl -s http://127.0.0.1:5001/status/summary | \
          jq -r '.shipment_readiness["9001"] // false')
  if [ "$ready" = "true" ]; then
    echo "Shipment is ready!"
    break
  fi
  tries=$((tries+1))
  echo "Waiting... $tries"
  sleep $sleep_step
  # 可选：越等越久，指数退避
  [ $((tries%10)) -eq 0 ] && sleep_step=$((sleep_step*2))
done

if [ "$ready" != "true" ]; then
  echo "Error: Waiting for shipment ready timeout"
  exit 1
fi

echo "== 3. LOAD TEST =="
curl -X POST http://127.0.0.1:5001/load \
     -H 'Content-Type: application/json' \
     -d '{
           "warehouse_id": 1,
           "truck_id": 3,
           "shipment_id": 9001
         }'
echo -e "\n"

sleep 3  # 等待World回应

echo "== 4. QUERY TEST =="
curl -X POST http://127.0.0.1:5001/query \
     -H 'Content-Type: application/json' \
     -d '{ "package_id": 9001 }'
echo -e "\n"

sleep 3  # 等待World回应

echo "== 5. STATUS CHECK =="
curl http://127.0.0.1:5001/package/9001/status
echo -e "\n"

echo "== 6. SUMMARY =="
curl http://127.0.0.1:5001/status/summary
echo -e "\n"

echo "Smoke test completed!" 
