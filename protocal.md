Amazon-UPS Protocol Specification
Overview
This document defines the communication protocol between the Mini-Amazon and Mini-UPS systems using Google Protocol Buffers (Protobuf). This specification outlines all messages exchanged, their structure, and the precise rules that govern their usage. The envelope-based approach is used to ensure extensibility and safe deserialization, due to Protobuf's requirement for known message types at parse time.
All communication MUST be in Protobuf binary format and MUST conform to the message definitions and workflows specified in this document.

Envelope Design
To ensure all messages can be parsed unambiguously, we define two envelope message types:
message Ack {}  

message AmazonToUPS {
  int64 seqnum = 123; 
  optional int64 acknum = 124;
  oneof msg_type {
    RequestPickup request_pickup = 1;
    Redirect redirect = 2;
    Cancel cancel = 3;
    LoadReady load_ready = 4;
    Ack pure_ack = 5; 
    ReturnAck return_ack = 6;
  }
}

message UPSToAmazon {
  int64 seqnum = 123;
  optional int64 acknum = 124;
  oneof msg_type {
    PickupAck pickup_ack = 1;
    RedirectAck redirect_ack = 2;
    CancelAck cancel_ack = 3;
    TruckArrived truck_arrived = 4;
    DeliveryStarted delivery_started = 5;
    DeliveryFailed delivery_failed = 6;
    Returned returned_msg = 7;
    ReturnDelivered return_delivered = 8;
    Ack pure_ack = 9; 
  }
}


Common Types
message Coordinate {
  int32 x = 1;
  int32 y = 2;
}

message ItemInfo {
  string item_name = 1;
  int32 quantity = 2;
}


Message Definitions
Amazon → UPS
1. RequestPickup
Triggered when a user confirms a purchase.
message RequestPickup {
  string ups_user_id = 1;
  repeated ItemInfo items = 2;
  int32 warehouse_id = 3;
  Coordinate user_destination = 4;
}

2. Redirect
Sent when a user changes the delivery address.
message Redirect {
  int64 package_id = 1;
  Coordinate new_destination = 2;
}

MAY be sent only before DeliveryStarted.

3. Cancel
Used to cancel an order.
message Cancel {
  int64 package_id = 1;
}

MAY be sent until DeliveryStarted. UPS SHOULD reject requests received after DeliveryStarted

4. LoadReady
Sent when a package has been loaded onto a truck.
message LoadReady {
  int64 package_id = 1;
  int32 truck_id = 2;
}

MUST follow TruckArrived.

5. ReturnAck
Confirm receiving the returned package.
message ReturnAck {
  int64 package_id = 1;
}
Must be sent when receiving ReturnDelivered

UPS → Amazon
1. PickupAck
Confirms truck assignment.
message PickupAck {
  string package_id = 1;
  int32 truck_id = 2;
}

package_id MUST be a 64‑bit signed integer, strictly increasing per UPS instance and globally unique within a world.
2. RedirectAck
Confirms redirect success.
message RedirectAck {
  int64 package_id = 1;
  bool success = 2;
}


3. CancelAck
Confirms cancellation.
message CancelAck {
  int64 package_id = 1;
  bool success = 2;
}


4. TruckArrived
The truck has arrived at the warehouse.
message TruckArrived {
  int64 package_id = 1;
  int32 truck_id = 2;
  int32 warehouse_id = 3;
}


5. DeliveryStarted
Indicates that delivery is in progress.
message DeliveryStarted {
  int64 package_id = 1;
  int32 truck_id = 2;
}


6. DeliveryFailed
Indicates delivery failure.
message DeliveryFailed {
  int64 package_id = 1;
  string reason = 2;
}


7. Returned
Indicates the package is returned to the warehouse.
message Returned {
  int64 package_id = 1;
  int32 warehouse_id = 3;
}


8. ReturnDelivered
Indicates successful return delivery.
message ReturnDelivered {
  int64 package_id = 1;
  Coordinate destination = 2;
}


Reliability
Each part MUST keep a unique global counter for seqnum to peer that is increased with each request. Each part SHOULD only consider the request done until the response with the same seqnum is received and MUST acknowledge it with the acknum field in the next request.
Amazon 收到 PickupAck 后 MUST 将 package_id 绑定到订单，否则遇到重连/重放会丢失映射。
Either party MAY send a pure_ack message carrying only seqnum/acknum whenever it has no business payload but needs to acknowledge incoming messages within 500 ms.
When sending pure_ack, all payload fields OTHER THAN seqnum/acknum MUST be unset.

Notes
Protobuf messages MUST be wrapped in AmazonToUPS or UPSToAmazon to ensure deserialization.


Coordinate MUST be included as a separate reusable type.


Cancel and Redirect SHOULD be sent before DeliveryStarted.






