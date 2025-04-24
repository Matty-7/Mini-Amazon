# ERSS-project-jh730-xs90

# Get started

The last assignment -- our project -- is now posted! This will be your chance to put the things you've learned about designing robust server software to use all in one project.

- First, take a look through the assignment spec (on Canvas).
- For the project you have one near-term portion to complete. Some groups will be implementing the "Amazon" part and some will be implementing the "UPS" part. And your groups are part of a larger Interoperability Group (IG). Within the IG, each Amazon and each UPS should be fully compatible (i.e. be able to work with each other). To accomplish this, you'll need to define the protocol that your IG will use. A write-up of that will be due by **Thursday, April 10**. You can email that to your instructor and your IG's assigned TA as a PDF, and each IG only needs to send one copy (the writeup must be agreed upon by each of the 4 pairs in the IG). You'll also notice a TA listed with each IG. That is the TA who will help you review your protocol spec, provide helpful feedback, and coordinate a demo of your projects running together at the end of the semester.
- After IG formation, you will see your project (UPS or Amazon) and IG [at this link](https://docs.google.com/spreadsheets/d/1Y3v8YGJDXmZd60n2D3f_6Y6c46NFHi15wWa4VMkT1iM/edit?usp=sharing)
- The .proto files that are mentioned are posted along with the assignment spec (on Canvas).

I hope the project is enjoyable -- you've learned and practiced all the things at this point that you'll need for the project. I hope it also gives you an outlet (via the inter-operability groups) to have more chances to talk with your classmates in this unusual time. I encourage you to get together in person or on Zoom to chat and discuss often.

## World Sim docker deployment

The world simulator for final project is available under the following github repo:

https://github.com/yunjingliu96/world_simulator_exec

Please read the README.md about how to work with it.

**Important Note**: For more reliable worldsim container operation, make the following edit to docker-compose.yml: 
* Change "image: postgres" to "image: postgres:12-alpine3.15"

Still, there are several things you need to look out:

1. A note on "flakiness" in README.md -- You can think of "flakiness" as the "stupid degree" of the world. The higher the flakiness is, the more stupid the world is. The world express its stupidness by deliberately dropping (ignoring) requests it receives. And that's also why we need you to enforce the seqnum-ack mechanism in each request. Without the flakiness, ack seems useless, but with flakiness, ack is a guarantee.
2. You can set the flakiness to be 0 first and then set it with another value after you implement ack.
3. Time in the world simulator goes like in real life, not in speed, but in the fact that time won't stop even if connection is closed. So if you send command to deliver a package, close connection and make a query on same package, you should expect the package's status changes if you connect again 2 hours later.

# Tips and FAQs

## 1 Project Advice (high level thoughts)

Implementing everything on your side (amazon or ups) and then linking up to test with other groups is NOT the best way to go about it.  It can make testing at the end pretty painful.

The best way to go about it will be to get a feature working, then test with others in your IG (and you can start small with this).  Then add the next feature, and test again.  Keep repeating this process to build up.  This strategy will help with your code development, organization, and debugging.  And you'll have confidence that existing feature are working and solid as you continue adding new functions to the code.

I hope this helps, and I'd strongly encourage this way of going about it.

## 2 Google protocol buffer

### 2.1 API

FYI, all of the API details for google protocol buffers can be found here:

https://developers.google.com/protocol-buffers/docs/reference/overview

There are sub-sections for each different programming language.

### 2.2 (Python) Sending and Receiving protobuf mesages

If you're having trouble interacting with the world using Python...

The solution should be similar in the case when you recv/send msg using sockets in hw2/hw4. But the tricky part is how to get the length of message. Only when you have the correct length can you receive/send a whole photobuf message. And only when you have the correct whole photobuf message can you parse it.

Python really lacks useful references on how to solve this. There are some workaround solutions discussed on this [page](https://groups.google.com/forum/#!topic/protobuf/4RydUI1HkSM). In short, a varint32 is encoded before writing the message itself in a photobuf message. One solution is the following.

1. Import these encoding/decoding packages:
    
    ```python
    from google.protobuf.internal.decoder import _DecodeVarint32
    from google.protobuf.internal.encoder import _EncodeVarint
    ```
    
2. Sending a message is straightforward, just use socket to send it. But right before you send it, encode the length info:
    
    ```python
    _EncodeVarint(WORLD_SOCKET.send, len(ENCODED_MESSAGE), None)
    ```
    
3. Receiving is trickier, you need to extract the length first. My dummy but workable solution is to use a while loop to get the length until I know I'm at the beginning of the real message.
    
    ```python
    var_int_buff = []
    while True:
        buf = world_socket.recv(1)
        var_int_buff += buf
        msg_len, new_pos = _DecodeVarint32(var_int_buff, 0)
        if new_pos != 0:
            break
    whole_message = world_socket.recv(msg_len)
    ```
    
4. You can wrap the recv/send part to a standalone function that returns a whole Protobuf object. Just to improve the abstraction and reduce code duplication.
5. As we know the communication between you and the world is asynchronous(it should also be async between amazon and ups), you should open a socket and then while_loop listen to this socket. TIP3 is very helpful here(Waiting for a response, parse and check what this response is).

## 3 FAQs

### **Can I combine front-end and back-end?**

- Technically, yes. You can use socket in Django which means there're multiple ways to implement it based on your design. I think you can do it in this way from a syntax perspective but probably **not easy because everything is mixed together**.
- The way I did before is to use a single Python file for the backend server and Django for the frontend. And based on whatever you need these servers will send information between each other and to the UPS and world.

### **Can I separate front-end and back-end?**

Yes, having a split front-end and back-end that communicate via another socket is a reasonable way to approach it.

### **How to handle potentially the database between the front-end and back-end?**

- How to maintain model/date consistence between front end and backend depends on your design
- On one hand, you can set up database at backend (as you're describing through another app possibly in C++).  Then whenever the frontend (Django) want anything, it sends a query (possibly as a request through a tcp socket) to the backend so that the backend can do the query and send results back. In this way, only one side needs to manage the database.
- On the other hand, you can allow access to a single database both from backend and frontend. You only have to set up tables for once. As for something similar to ORM, if you are using C++, you can check out libpqxx. In this way, both the frontend and backend has the responsibility to maintain the database.

### **Which side will create package id - Amazon or UPS? What is the difference between the package id and shipment id?**

- shipid in APack is just packageid in AQuery. They are just names for identifier for a unique package.
- Shipid is created by Amazon side. World receives the shipid that amazon wants it to create and tries to create a new package for amazon with the specified id. Since the world should also be robust, if world receives any illegal id number, for example, the shipid already exists, it will send back with A/UErr with a descriptive string that you can read to debug.
- Shipid and packageid are the same, but not necessarily the same with tracking number on UPS side, depending on your design. It's amazon's responsibility to create it and communicate it with both world and UPS.

### **Seq num and ACK implementation: When we want to acknowledge a certain message with a sequence number like 101, we send back the same number 101 as ACK?**

- Let's say amazon send an ACommands with only one APurchaseMore request, and the seqnum contained in APurchaseMore request is 13 (this seqnum is the unique identifier of requests sent from amazon side, so amazon should keep incrementing it. it may start from 0). Once world receive ACommands and handle the APurchaseMore request, it will first send an ack = 13 notifying amazon that the world has received your request No.13. Then whenever APurchaseMore actions is done, it will send amazon one APurchaseMore response corresponding to the one amazon sent, but inside this APurchaseMore response, the seqnum is the unique identifier of world responses, in this case, maybe 50 (world keeps track of its own message sent sequence number).
- World has its own mechanism of gathering responses and send them back together, so these 2 responses may be sent together or separately.

### **Is there a limit to how many packages one truck can carry?**

You can assume a truck can handle any load assigned to it. These are very flexible, magic trucks. :)

### **(Python) Database and multithreading: cursor is not thread-safe. How to avoid race conditions?**

- I think this has some good info on cursors, related to your questions:
    
    https://www.psycopg.org/docs/usage.html#thread-safety
    
- Connection objects are thread safe, so you can share one connection across multiple threads.  But, cursors themselves are not thread safe. So you would need at least a separate cursor object for each thread.

### **Do we need shopping cart feature?**

- You don't necessarily need to have a feature equivalent to a shopping cart, since that's not covered by the "bare minimum" or "actually useful" categories.
- A shopping cart would be a nice "produce differentiation" feature.  But there are almost endless other feature ideas you can probably come up with as well.  So with enough other interesting produce differentiation features, you can still have a very good project.

# Submission Details

For the final Project, the submission is fairly similar to previous homework.

But the grading for the project will primarily be driven by a demo session where each IG will meet with a TA to demo their Amazon / UPS pairs working together, show off your functionality, features, etc. Details on setting up your IG demo will be provided shortly.

### **To submit the project:**

1. Use gitlab to submit your code, and the name should be ERSS-project-<netid1>-<netid2>. Don't forget to add instructor & TAs as reporters.

2. In the top level of your repo, include your danger log.

3. In the top level of your repo, include a text or PDF file named "differentiation.txt" or "differentiation.pdf" that should list the differentiating features (as described in the project spec) of your mini UPS or Amazon.

4. Use Sakai to submit your gitlab link (just one person in each project pair needs to do so).

5. Email your final protocol spec to your section's instructor (just 1 IG member needs to send it).

### **To schedule a demo:**

- A list of times each TA is available for project demo will be posted close to project submission time. 
Your IG will need to sign up for one of these slots with your TA.
- Each IG will demo to their assigned TA. **All IG members must be present at the demo.**
- Once TAs post their available timeslots (this will be announced on the message board), then communicate with your IG to agree on a timeslot when you can all do the demo.  Then email the instructor with your chosen slot.

# Database Setup & Migrations

## Setting up PostgreSQL Database

This project uses PostgreSQL for data persistence. Follow these steps to set up the database locally:

1. Start the PostgreSQL container:
   ```bash
   docker compose up -d db
   ```

   This will start a PostgreSQL instance with the following configuration:
   - User: amazon
   - Password: amazon
   - Database name: amazon
   - Port: 5432

## Running Database Migrations

We use Alembic for database migrations. To initialize your database schema:

1. Install required packages:
   ```bash
   pip install -r requirements.txt
   ```

2. Run the migrations:
   ```bash
   alembic upgrade head
   ```

   This will create all necessary tables in the database.

## Database Models

The project has the following main models:
- **Warehouse**: Stores information about warehouses
- **Product**: Represents items in the inventory
- **Shipment**: Tracks orders and their status

To create new migrations after modifying models:
```bash
alembic revision --autogenerate -m "description of changes"
```
