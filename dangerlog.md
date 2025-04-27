Danger log 
4.17: 
When a new purchase request created at the front-end, it should notify back-end. 

So we created a socket for communications between front-end (`web-app/amazon/utils.py`) and back-end (`daemon/src/.../DaemonThread.java`). Front-end (`purchase` function) opens a TCP connection to the daemon on port 8888, sends the package ID, gets an ACK, and closes the connection. The daemon's `DaemonThread` listens on 8888, accepts the connection, reads the ID, sends ACK, closes the socket, and triggers the purchase process (`onPurchase` -> `AmazonDaemon.toPurchase`).

4.18: 
Make the communication process asynchronous.

We created three separate threads in `AmazonDaemon.java` for different communication purposes: one thread (`DaemonThread`) for handling purchase requests from Django front-end via socket, one thread (`UPS-Receiver`) to listen for messages from UPS via a persistent connection, and one (`World-recv-thread`) for receiving asynchronous messages (`AResponses`) from the World simulator.
For asynchronous operations like `toPurchase()`, `toPack()`, `toRequestPickup()`, `toLoad()`, etc., in `AmazonDaemon.java`, a `ThreadPoolExecutor` is used (`threadPool.execute(...)`) to submit tasks (like sending Protobuf messages), allowing functions to return immediately.

4.19:
Users that are not login shouldn't get access to purchasing, modifying profile and other web pages.

So we added the `@login_required` decorator to relevant view functions in `web-app/amazon/views.py` (e.g., cart, checkout, order history) and `web-app/users/views.py` (e.g., profile). Also fixed other unspecified model-related bugs.

4.20:
Basic process realized with mocked-UPS, mocked-UPS only support two trucks for now, so can only buy two packages at the same time.

4.21:
Fixed multi-thread problem related to sequence number, use synchronized methods to make the process of getting seqnum thread-safe. 

In `AmazonDaemon.java`, the global sequence number (`seqNum`) generation is made thread-safe using a `synchronized` method `nextSeq()`. 

Fixed asynchronous problem, we use a map to store the mapping between sequence number and request, and has a separate thread only for receiving information from the world, once receive it will loop through all field, update the corresponding package status, ack all requests and send back corresponding ack.

In `AmazonDaemon.java`, a `ConcurrentHashMap<Long, Timer>` (`requestMap`) stores the mapping between the sequence number of an outgoing request (to World or UPS) and a `Timer` task for potential resends. Separate receiver threads (`World-recv-thread`, `UPS-Receiver`) listen for responses containing acknowledgements (`acks`). Upon receiving an `ack`, the corresponding `Timer` in `requestMap` is cancelled. The `World-recv-thread` also processes incoming `AResponses` (like `Arrived`, `Ready`, `Loaded`) using `handleWorldResponse` to update package status (in `packageMap` and the database).

4.22:
More front-end web pages and functionalities are added, user experience improved.

4.23:
Fixed synchronize-related problems. 

4.24:
Fixed bugs in shopping-cart web page, make it more user friendly.

4.25:
The database has empty tables when first created(in docker or in new environment), no items will be listed on the webpage.

So we use `signals` provided by django (`web-app/users/signals.py`) to automatically create a default `Profile` whenever a new `User` object is saved. (Note: Initial creation of default categories, items, or warehouses was not found in signals or migrations).

4.26:
Fixed bugs in showing the total price of an order and in modifying a selling product.
