# 🛒 Mini-Amazon System  
**By Matty & Alex**

## Overview

This project simulates a simplified version of Amazon's logistics and marketplace system, integrating a Django-based web front-end with a backend daemon service, a PostgreSQL database, and real-time communication with a simulated world and UPS system using Protocol Buffers (proto2).  

It supports user registration, seller functionality, order placement, warehouse selection, UPS tracking, and synchronized backend processing.

---

## 🧱 Project Structure

```bash
.
├── daemon/                 # Java-based backend daemon for order processing
├── web-app/               # Django-based Amazon web platform
├── nginx/                 # Nginx config for load balancing (if needed)
├── worldSim/              # World simulator interface (via ProtoBuf)
├── docker-compose.yml     # Multi-container setup for deployment
```

---

## ⚙️ Features

### ✅ Web Front-End (`web-app`)
- User registration & login with role-based access (buyer/seller)
- Item listing, shopping cart, checkout, order history
- Seller dashboard for managing inventory
- Dynamic category and image handling
- Checkout includes address and UPS info with auto-warehouse selection

### 🧠 Backend Daemon (`daemon`)
- Communicates with World Simulator and UPS using:
  - `world_amazon.proto`
  - `ups_amazon.proto`
  - `world_ups.proto`
- Handles packing, loading, and truck coordination
- Sends and acknowledges messages with retry logic
- Order status updates fed back to web front-end

### 📦 Logistics Simulation
- Warehouse assignment based on customer coordinates
- UPS truck interaction using a reliable channel
- Auto-generated package status updates (e.g., packed → loaded → delivered)

---

## 🛠️ Setup & Run

### Prerequisites
- Docker + Docker Compose
- Java 11+
- Python 3.8+
- PostgreSQL

### Build & Launch

```bash
# Clone repo
git clone <repo-url>
cd <project-root>

# Start the entire system
docker-compose up --build
```

### Django Management Commands
```bash
# Enter web container
docker exec -it mini-amazon-web bash

# Apply migrations and create default data
python manage.py migrate
```

---

## 📡 Protocol Overview

We use `Protocol Buffers (proto2)` for all inter-system communication.

- `world_amazon.proto` — commands to world: pack, load, query
- `ups_amazon.proto` — Amazon <-> UPS coordination: pickup, redirect, cancel
- `world_ups.proto` — UPS internal truck management

---

## 📂 File Highlights

- `/daemon/.../AmazonDaemon.java` – Core handler for communication with World/UPS
- `/web-app/amazon/...` – Django models, views, templates for the marketplace
- `/web-app/users/...` – Django app for user management (profiles, roles)
- `/docker-compose.yml` – Defines services for web, db, daemon, and nginx

---

## 🚨 Known Issues
See `dangerlog.md` for unresolved bugs and edge case failures.

---

## 📄 Documentation
- Protocol docs: `world_amazon.proto`, `ups_amazon.proto`
- Design notes: `differentiation.pdf`
- Sample outputs: `output.txt`

---

## 🙌 Authors

**Matty & Alex**  
ECE 568 Final Project, Duke University  
April 2025
