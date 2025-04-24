# Import pytest only when running through pytest, not when run directly
try:
    import pytest
    pytestmark = pytest.mark.integration
except ImportError:
    pass  # Skip when run directly

# Adjust path for direct script execution
import sys
import os
# Add the project root directory to Python path when run directly
if __name__ == "__main__":
    sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

from amazon_app.services.world_client import WorldClient
import amazon_app.pb_generated.world_amazon_1_pb2 as wam
import socket
import argparse
import logging
import queue
# Set up logging
logging.basicConfig(level=logging.INFO, 
                   format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='Test World Client')
    parser.add_argument('--host', default="ece650-vm.colab.duke.edu", 
                        help='Server hostname (default: ece650-vm.colab.duke.edu)')
    parser.add_argument('--port', type=int, default=23456, 
                        help='Server port (default: 23456)')
    args = parser.parse_args()
    
    hostname = args.host
    port = args.port
    
    try:
        # First check if the server is reachable
        print(f"Verifying connection to {hostname}:{port}...")
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(5)
        
        try:
            result = sock.connect_ex((hostname, port))
            if result != 0:
                print(f"Error: Could not connect to {hostname}:{port}")
                print(f"Error code: {result}")
                sys.exit(1)
            sock.close()
            print(f"Successfully connected to {hostname}:{port}")
        except socket.error as e:
            print(f"Socket error: {e}")
            sys.exit(1)
        
        # Now proceed with the client
        client = WorldClient(hostname, port, queue.Queue())
        
        # connect to world
        print("Connecting to world...")
        client.connect([
            wam.AInitWarehouse(id=1, x=3, y=4)
        ])
        print("Connected successfully!")

        # simulate purchase
        print("Buying product...")
        product = wam.AProduct(id=101, description="book", count=5)
        client.buy(1, [product])

        # simulate pack
        print("Packing product...")
        client.pack(1, [product], 10001)

        # simulate load
        print("Loading product...")
        client.load(1, truckid=1, shipid=10001)

        # simulate query
        print("Querying package...")
        client.query(packageid=10001)
        
        print("Test completed successfully")

    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()
    finally:
        # Make sure to cleanly shutdown the client
        if 'client' in locals():
            print("Shutting down client...")
            client.shutdown()
