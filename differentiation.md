# Mini Amazon Extra Features Differentiation

This document outlines the extra features implemented in the Mini Amazon project that differentiate it from a basic implementation.

## Extra Features

### 1. Full-Featured Shopping Cart (jQuery + Ajax)

*   **Dynamic Item Management:**
    *   Users can check and uncheck specific orders within the cart.
    *   The quantity of items in each order can be changed dynamically.
    *   Orders can be deleted from the cart.
*   **Real-time Price Updates:** The total price dynamically reflects changes made to the cart (e.g., quantity changes, deletions).
*   **Order Sorting:** Functionality to sort cart items based on different criteria (e.g., price, name).

### 2. Full-Featured Product Management System & Seller Role

*   **Seller Registration:** Authenticated users can register as sellers and unregister.
*   **Seller-Specific Pages:** Each seller has a dedicated page displaying their listed products.
*   **Product Management:** Sellers have access to a system to:
    *   Publish new products.
    *   Edit existing product details (name, price, category).
    *   Delete or mark products as "on sell".

### 3. Enhanced Search Functionality

*   **Homepage Search Bar:**
    *   Search across all product categories.
    *   Filter searches by a specific category.
    *   Search for products listed by a specific seller.

### 4. Full-Featured Order History Page

*   **Order Search:** Search bar to locate specific past orders by item name.
*   **Order Deletion:** Ability to delete historical orders.
*   **Order Details:** View detailed information for any past order.

### 5. Build-in Data Initialization

*   **Initial Data:** Uses signals (or equivalent mechanism) to populate the database with initial data upon deployment (e.g., default user, initial products, warehouses).

### 6. Dynamic Warehouse Allocation

*   **Predefined Warehouses:** Includes 10 build-in warehouses as part of the initial dataset.
*   **Nearest Warehouse Logic:** Automatically allocates the geographically nearest warehouse to each package for efficient delivery processing.

### 7. Email Notification

*   **Purchase Confirmation:** Sends a confirmation email to the user upon successful completion of a purchase.

### 8. Product Categorization

*   **Multiple Categories:** Products are organized into several categories.
*   **Homepage Filtering:** Users can easily switch between and view products by category on the home page.

### 9. User Profile Editing

*   **Dedicated Profile Page:** A separate page allows users to edit their personal information (name, email, password).

### 10. UPS Account Association

*   **Automatic Linking:** Automatically associates each order with the user's linked UPS account (details of linking mechanism assumed).

### 11. Address Book

*   **Address Storage:** Allows users to store frequently used shipping addresses.
*   **Automatic Checkout Fill:** Automatically fills the address field during checkout using stored addresses.

### 12. User-Friendly UI and Interaction

*   **Error Handling:** Edit forms include error handling and display informative messages upon failure.
*   **Smooth Interactions (jQuery + Ajax):** Utilizes jQuery and Ajax for smoother user experiences, such as partial page refreshes (e.g., in the shopping cart) to avoid full page reloads. 
