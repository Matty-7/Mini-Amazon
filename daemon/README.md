# Daemon

This is the amazonDaemon who keep running in the background, and has no user interface. It's mainly responsible for communicate with the world simulator(and UPS), and it will share the same database with front-end amazon.

You should run this amazonDaemon with the front-end amazon together(aka on the same machine).

## If you want to purchase some items

1. store the new package into database
2. connect to `localhost`, port `8888`
3. send the id of new package(daemon will extract package info from DB)
4. receive an ack and then close the connection

NOTE: the daemon will update the status of package regularly, so if you want to query the status, go for DB.

## Important NOTE

* Because we mock a simple UPS(no truck allocating strategy), so we only support two trucks for now, which
means you can only buy two packages at the same time(only two truck). Otherwise, some corner case like when one package try to load,
but the truck has left the warehouse to deliver another package.