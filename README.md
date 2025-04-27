# 🛒 Mini-Amazon System  
**By Matty (jh730) & Alex (xs90)**

## Overview

This project simulates a simplified version of Amazon's logistics and marketplace system, integrating a Django-based web front-end with a backend daemon service, a PostgreSQL database, and real-time communication with a simulated world and UPS system using Protocol Buffers.  

It supports user registration, seller functionality, order placement, warehouse selection, UPS tracking, and synchronized backend processing.

---

## 🧱 Project Structure

```bash
.
├── daemon/                 # Java-based backend daemon for order processing
│   ├── src/main/java/...   # Core Java source code
│   └── pom.xml             # Maven dependencies
├── web-app/               # Django-based Amazon web platform
│   ├── amazon/             # Main marketplace app (models, views, templates)
│   ├── users/              # User management app
│   ├── manage.py           # Django management script
│   └── requirements.txt    # Python dependencies
├── nginx/                 # Nginx config for load balancing (e.g., nginx.conf)
├── worldSim/              # World simulator interface (provided JAR/scripts)
├── protobuf/              # Protocol Buffer definitions (.proto files)
│   ├── world_amazon.proto
│   ├── ups_amazon.proto
│   └── world_ups.proto
├── docker-compose.yml     # Multi-container setup for deployment
└── README.md              # This file
```

---

## ⚙️ Features

### ✅ Web Front-End (`web-app`)
- User registration & login with role-based access (distinguishes buyers and sellers).
- Product listing with details (description, price, image uploads).
- Shopping cart functionality (add, remove, view items).
- Secure checkout process including address and (simulated) payment info.
- Order history tracking for users.
- Seller dashboard for inventory management (add/update items).
- Dynamic category browsing and search functionality.
- Automatic warehouse selection during checkout based on destination address coordinates.

### 🧠 Backend Daemon (`daemon`)
- Establishes and maintains persistent connections to World Simulator and UPS system.
- Processes incoming orders from the web application database.
- Sends `APurchaseMore`, `APack`, `APutOnTruck` commands to the World Simulator.
- Sends `AUReqTruck`, `AUDeliverReq` messages to the UPS system.
- Receives and handles responses (`AResponses`, `AUFinished`), acknowledgements (`acks`), and errors.
- Implements reliable communication with sequence numbers and retry logic for message delivery.
- Updates order status in the shared database (e.g., `packing`, `packed`, `loading`, `loaded`, `delivering`, `delivered`).

### 📦 Logistics Simulation
- World Simulator manages warehouse inventory and truck movements.
- UPS Simulator handles truck dispatch and delivery notifications.
- Warehouse assignment logic implemented in the web app, selecting the nearest available warehouse.
- Package status updates flow from World -> Daemon -> Database -> Web App.

---

## 🛠️ Setup & Run

### Prerequisites
- Docker & Docker Compose (Latest stable versions recommended)
- Java JDK 11 or higher
- Python 3.8 or higher
- PostgreSQL (Will be run in a Docker container)
- Apache Maven (for building the daemon if not using Docker build)

### Build & Launch

```bash
# 1. Clone the repository
git clone git@gitlab.oit.duke.edu:jh730/erss-project-jh730-xs90.git

# 2. Build and start all services (Web, Daemon, DB, Nginx)
# This command builds the Docker images and starts the containers.
# The Java daemon is compiled within its Docker build process.
docker-compose up --build -d # Use -d to run in detached mode

# 3. Set up the Django database
# Enter the running web container
docker exec -it web-app bash # 'web-app' should match the service name in docker-compose.yml

# Inside the container, apply database migrations
python manage.py migrate

# Exit the container
exit

# 4. Access the web application
# Open your browser and navigate to http://localhost:80 (or the port mapped by Nginx)
```

### Stopping the System
```bash
docker-compose down
```

---

## 📡 Protocol Overview

Communication between the Daemon, World Simulator, and UPS system uses `Protocol Buffers`. The `.proto` files define the message structures.

- `world_amazon.proto`: Defines messages exchanged between the Amazon Daemon and the World Simulator.
    - **Daemon -> World:** `APurchaseMore` (buy inventory), `APack` (request packing), `APutOnTruck` (request loading).
    - **World -> Daemon:** `AResponses` (purchase results, packed notifications, loaded notifications), `AErr` (errors), `AFinished` (warehouse empty).
- `ups_amazon.proto`: Defines messages exchanged between the Amazon Daemon and the UPS Simulator.
    - **Daemon -> UPS:** `AUReqTruck` (request a truck for pickup), `AUDeliverReq` (notify UPS package is ready).
    - **UPS -> Daemon:** `UADelivered` (delivery confirmation), `UATruckArrived` (truck arrival notification).
- `world_ups.proto`: Defines messages possibly used internally by UPS or between World and UPS (if applicable). Includes truck status and delivery information.

*Note: Reliable delivery is implemented using sequence numbers (`seqnum`) and acknowledgements (`acks`) embedded within the communication wrappers.*

---

## 📂 File Highlights

- `daemon/src/main/java/edu/duke/ece568/proj/AmazonDaemon.java`: Main application class for the backend daemon, handling connections and message processing loops.
- `daemon/src/main/java/edu/duke/ece568/proj/protocol/`: Contains Java classes generated from `.proto` files.
- `web-app/amazon/models.py`: Defines the core Django database models (e.g., `Product`, `Order`, `OrderItem`, `Warehouse`).
- `web-app/amazon/views.py`: Contains the Django view functions handling web requests for the marketplace.
- `web-app/users/models.py`: Defines the `User` profile and related models.
- `protobuf/`: Directory containing the `.proto` definitions used to generate communication code.
- `docker-compose.yml`: Defines the services (web, daemon, db, nginx), networks, and volumes for the entire application stack.
- `nginx/nginx.conf`: Configuration file for the Nginx reverse proxy / load balancer.

---

## 🚨 Known Issues
See `dangerlog.md` for unresolved bugs, potential race conditions, and edge case handling failures.

---

## 📄 Documentation
- Protocol Buffer Definitions: See files in the `protobuf/` directory.
- Project Design Notes: `differentiation.md` (Discusses design choices and system architecture).

---

## 🙌 Authors

**Matty & Alex**  
ECE 568 Final Project, Duke University  
April 2025
