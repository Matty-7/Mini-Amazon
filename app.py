# app.py

import os
import sys
import logging
import threading
import queue
import time
from flask import Flask, request, jsonify

# --- Configuration and Imports ---
try:
    import config
    from amazon_app.services.world_client import WorldClient
    # Adjust import based on your actual protobuf file location/name
    # Example: from amazon_app.protocols import world_amazon_1_pb2 as wam
    import amazon_app.pb_generated.world_amazon_1_pb2 as wam
except ImportError as e:
    print(f"Error importing modules: {e}. Ensure config.py, world_client.py, "
          "and the protobuf definitions exist and are importable.")
    sys.exit(1)

# --- Basic Logging Setup ---
logging.basicConfig(level=logging.INFO,
                    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

# --- Flask App Initialization ---
app = Flask(__name__)
app.config.from_object(config)

# --- Shared State and Queue ---
# Queue for responses from WorldClient's _recv_loop
response_queue = queue.Queue(maxsize=100) # Set a maxsize

# Simple in-memory state (Replace with DB for persistence)
# WARNING: Direct dict access is NOT thread-safe if using multiple Flask workers.
# Use thread-safe structures or DB for production.
package_statuses = {}
shipment_readiness = {} # shipid -> bool
shipment_loaded_status = {} # shipid -> bool
warehouse_inventory = {} # whnum -> {product_id: count} - Simplified
world_errors = []
state_lock = threading.Lock() # To protect access to shared dictionaries

# --- WorldClient Instance ---
# Create after app is defined, before running
logger.info("Initializing WorldClient...")
world_client = WorldClient(
    host=app.config['WORLD_HOST'],
    port=app.config['WORLD_PORT'],
    response_queue=response_queue
)

# --- Response Processing Worker ---
def response_processor_worker():
    """Runs in a background thread, processing messages from WorldClient."""
    logger.info("Starting response processor worker thread...")
    while True:
        try:
            # Blocks until an item is available in the queue
            item = response_queue.get()
            logger.info(f"Processing item from queue: {item.get('type', 'UNKNOWN')}")

            item_type = item.get("type")

            with state_lock: # Lock when modifying shared state
                if item_type == "arrived":
                    # Example: Update inventory (very basic)
                    wh = item.get("whnum")
                    if wh not in warehouse_inventory:
                        warehouse_inventory[wh] = {}
                    for prod in item.get("things", []):
                        prod_id = prod.get("id")
                        count = prod.get("count", 0)
                        warehouse_inventory[wh][prod_id] = warehouse_inventory[wh].get(prod_id, 0) + count
                    logger.info(f"Inventory updated for WH {wh}: {warehouse_inventory[wh]}")

                elif item_type == "ready":
                    ship_id = item.get("shipid")
                    if ship_id is not None:
                        shipment_readiness[ship_id] = True
                        logger.info(f"Shipment {ship_id} marked as ready.")

                elif item_type == "loaded":
                    ship_id = item.get("shipid")
                    if ship_id is not None:
                        shipment_loaded_status[ship_id] = True
                        # Maybe remove from readiness?
                        # shipment_readiness.pop(ship_id, None)
                        logger.info(f"Shipment {ship_id} marked as loaded.")

                elif item_type == "packagestatus":
                    pkg_id = item.get("packageid")
                    status = item.get("status")
                    if pkg_id is not None:
                        package_statuses[pkg_id] = status
                        logger.info(f"Status updated for package {pkg_id}: {status}")

                elif item_type == "error":
                    world_errors.append(item)
                    logger.warning(f"World error received: {item}")

                elif item_type == "finished":
                    logger.warning("World simulator finished. Application might stop functioning correctly.")
                    # Potentially trigger app shutdown or alert
                    break # Stop processing if world says it's done

                elif item_type == "disconnected":
                     logger.error("World disconnected unexpectedly.")
                     # Potentially trigger app shutdown or alert
                     break # Stop processing

                elif item_type == "recv_loop_error":
                    logger.error(f"Receive loop error reported: {item.get('error')}")
                    break # Stop processing

                else:
                    logger.warning(f"Received unknown item type from queue: {item_type}")

            # Important: Signal that the task from the queue is done
            response_queue.task_done()

        except Exception as e:
            logger.exception(f"Error in response_processor_worker: {e}")
            # Avoid dying; maybe sleep and retry or log and continue?
            time.sleep(1)

    logger.info("Response processor worker thread stopped.")


# --- API Endpoints ---

@app.route('/')
def index():
    """Basic health check."""
    return jsonify({"status": "ok", "world_client_running": world_client._running})

@app.route('/buy', methods=['POST'])
def handle_buy():
    """Endpoint to trigger a purchase."""
    if not world_client._running:
         return jsonify({"error": "World client not connected"}), 503

    data = request.get_json()
    if not data or 'warehouse_id' not in data or 'products' not in data:
        return jsonify({"error": "Missing warehouse_id or products"}), 400

    wh_id = data['warehouse_id']
    products_data = data['products'] # Expecting list of {'id': N, 'description': 'S', 'count': N}

    try:
        products = []
        for p_data in products_data:
            if not all(k in p_data for k in ('id', 'description', 'count')):
                return jsonify({"error": f"Invalid product data: {p_data}"}), 400
            prod = wam.AProduct(id=p_data['id'], description=p_data['description'], count=p_data['count'])
            products.append(prod)

        world_client.buy(wh=wh_id, prods=products)
        # Note: We don't get immediate confirmation here, just that the command was sent.
        # The confirmation ('arrived' message) comes via the queue later.
        return jsonify({"message": "Buy command sent to world"}), 202 # 202 Accepted
    except Exception as e:
        logger.exception(f"Error processing /buy request: {e}")
        return jsonify({"error": "Internal server error processing request"}), 500


@app.route('/pack', methods=['POST'])
def handle_pack():
    """Endpoint to trigger packing a shipment."""
    if not world_client._running:
         return jsonify({"error": "World client not connected"}), 503

    data = request.get_json()
    if not data or not all(k in data for k in ('warehouse_id', 'products', 'shipment_id')):
        return jsonify({"error": "Missing warehouse_id, products, or shipment_id"}), 400

    wh_id = data['warehouse_id']
    ship_id = data['shipment_id']
    products_data = data['products']

    try:
        products = []
        for p_data in products_data:
             if not all(k in p_data for k in ('id', 'description', 'count')):
                return jsonify({"error": f"Invalid product data: {p_data}"}), 400
             prod = wam.AProduct(id=p_data['id'], description=p_data['description'], count=p_data['count'])
             products.append(prod)

        world_client.pack(wh=wh_id, prods=products, shipid=ship_id)
        return jsonify({"message": "Pack command sent to world"}), 202
    except Exception as e:
        logger.exception(f"Error processing /pack request: {e}")
        return jsonify({"error": "Internal server error processing request"}), 500

@app.route('/load', methods=['POST'])
def handle_load():
    """Endpoint to trigger loading a shipment onto a truck."""
    if not world_client._running:
         return jsonify({"error": "World client not connected"}), 503

    data = request.get_json()
    if not data or not all(k in data for k in ('warehouse_id', 'truck_id', 'shipment_id')):
        return jsonify({"error": "Missing warehouse_id, truck_id, or shipment_id"}), 400

    wh_id = data['warehouse_id']
    truck_id = data['truck_id']
    ship_id = data['shipment_id']

    try:
        # Optional: Check if shipment is ready first using our state
        with state_lock:
            if not shipment_readiness.get(ship_id):
                 logger.warning(f"Attempted to load shipment {ship_id} which is not marked as ready.")
                 # Decide: reject request or send command anyway? Let's send it for now.
                 # return jsonify({"error": f"Shipment {ship_id} is not ready for loading"}), 409 # 409 Conflict

        world_client.load(wh=wh_id, truckid=truck_id, shipid=ship_id)
        return jsonify({"message": "Load command sent to world"}), 202
    except Exception as e:
        logger.exception(f"Error processing /load request: {e}")
        return jsonify({"error": "Internal server error processing request"}), 500

@app.route('/query', methods=['POST'])
def handle_query():
    """Endpoint to query package status."""
    if not world_client._running:
         return jsonify({"error": "World client not connected"}), 503

    data = request.get_json()
    if not data or 'package_id' not in data:
        return jsonify({"error": "Missing package_id"}), 400

    package_id = data['package_id']

    try:
        world_client.query(packageid=package_id)
        return jsonify({"message": "Query command sent to world"}), 202
    except Exception as e:
        logger.exception(f"Error processing /query request: {e}")
        return jsonify({"error": "Internal server error processing request"}), 500

@app.route('/package/<int:package_id>/status', methods=['GET'])
def get_package_status(package_id):
    """Endpoint to get the last known status of a package from our state."""
    with state_lock: # Lock for reading shared state
        status = package_statuses.get(package_id)

    if status is not None:
        return jsonify({"package_id": package_id, "status": status})
    else:
        # Check if a query was ever sent, maybe? For now, just 404
        return jsonify({"error": "Package status not found or not yet received"}), 404

@app.route('/status/summary', methods=['GET'])
def get_status_summary():
    """Provides a summary of the current known state."""
    with state_lock:
        summary = {
            "package_statuses": dict(package_statuses), # Create copies
            "shipment_readiness": dict(shipment_readiness),
            "shipment_loaded_status": dict(shipment_loaded_status),
            "warehouse_inventory_summary": {wh: list(inv.keys()) for wh, inv in warehouse_inventory.items()}, # Just keys for brevity
            "world_errors": list(world_errors),
            "world_client_running": world_client._running,
            "response_queue_size": response_queue.qsize(),
        }
    return jsonify(summary)


# --- Application Startup ---
if __name__ == '__main__':
    logger.info("Starting Flask application...")

    # Prepare initial warehouse data for WorldClient connect
    initial_warehouses = []
    for wh_data in app.config.get('INITIAL_WAREHOUSES_DATA', []):
        try:
            wh = wam.AInitWarehouse(id=wh_data['id'], x=wh_data['x'], y=wh_data['y'])
            initial_warehouses.append(wh)
        except KeyError as e:
            logger.error(f"Invalid warehouse data in config: Missing key {e} in {wh_data}")
            sys.exit(1)

    # Connect to the World Simulator
    logger.info(f"Connecting to World Simulator at {app.config['WORLD_HOST']}:{app.config['WORLD_PORT']}...")
    try:
        world_id = world_client.connect(warehouses=initial_warehouses)
        if world_id is None:
             logger.error("Failed to connect to World Simulator. Exiting.")
             sys.exit(1) # Exit if connection failed
        logger.info(f"Successfully connected to World Simulator with worldid: {world_id}")
    except Exception as e:
        logger.exception(f"Unhandled exception during WorldClient connection: {e}")
        sys.exit(1)


    # Start the background thread for processing responses
    response_thread = threading.Thread(target=response_processor_worker, daemon=True)
    response_thread.start()
    logger.info("Response processor thread started.")

    # Start the Flask development server
    # IMPORTANT: use_reloader=False is often necessary when managing background threads
    # or singleton objects like WorldClient, as the reloader can cause multiple
    # initializations or issues with thread management.
    logger.info("Starting Flask development server...")
    app.run(host='0.0.0.0', port=5001, debug=True, use_reloader=False)

    # --- Cleanup (won't usually run in debug mode, but good practice) ---
    logger.info("Flask app stopping...")
    world_client.shutdown() # Gracefully disconnect from world sim
    # Optionally wait for the response thread?
    # response_queue.join() # Wait for queue to be empty (might block indefinitely if worker died)
    logger.info("Application shutdown complete.")
