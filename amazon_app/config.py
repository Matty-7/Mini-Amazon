import os

WORLD_HOST = os.getenv("WORLD_HOST", "ece650-vm.colab.duke.edu")
WORLD_PORT = os.getenv("WORLD_PORT", 23456)

INITIAL_WAREHOUSES_DATA = [
    {'id': 1, 'x': 10, 'y': 20},
    {'id': 2, 'x': 50, 'y': 60},
]

SIM_SPEED = int(os.getenv("SIM_SPEED", 1))

