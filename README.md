# mini amazon

A full-stack web application modeling Amazon system paired with warehouse and delivery system. It simulated the whole process from buying products to getting the package delivered. Frontend is developed with **Django** and backend is developed in **Java**. **Google Protocol Buffer** is used to communicate with world-simulator and mini UPS system partner.  

author: Kewei Xia, Yue Yang

## Feature checklist(requirement)

- [x] Buy products(communicate with both the world and UPS).
- [x] Different catalog of products.
- [x] Check the status of an order.
- [x] Specify the deliver address(i.e. (x,y)).
- [x] Specify a UPS account name to associate the order with.
- [x] Provide the *tracking number* for the shipment.

## Extra features we have

- [x] A **full-featured shopping cart**(implemented by jQuery + Ajax).
    - check(& uncheck) any orders you want
    - change the item count of each order **dynamically**
    - delete any order you don't need anymore
    - the price will change according to your action(e.g. delete order, change count)
    - **sort** the orders via different constraints
- [x] A **full-featured product management system** + seller.
    - any authenticated user can register as a seller(or unregister)
    - seller will has his/her own selling page, displaying all products they are selling
    - sellers will have a product management system which he/she can 
            - publish new products
            - edit their selling products(e.g. name, price, category)
            - delete(or on sell) their selling products
- [x] Search bar in home page.
    - search products from all categories
    - search products in one specific category
    - search products for one specific seller
- [x] A **full-featured order page**.
    - search bar --- locate any order by item name
    - delete any history orders
    - view detail of any orders
- [x] Build-in data
    - use **signals** to make sure we have some build-in data(e.g. initial items, defualt user), easy to deploy
- [x] Warehouse **dynamic alloccation**.
    - we have 10 build-in warehouses(as part of the initial data)
    - we will allocate the nearest warehouse to each package(to facilate the delivery process)
- [x] Email notification.
    - we will send a confirmation email to user once purchase successful
- [x] Product category.
    - several category of products, can switch between them in the home page
- [x] Edit user profile
    - a separate page to allow user edit his/her personal infromation(e.g. name, email, password)
- [x] Associate your amazon account with your UPS account.
    - automatically associate each order with your UPS account
- [x] Address book.
    - store your frequently use address, fill out the address autimatically when checkout next time
- [x] User-friendly UI and interaction.
    - all edit info page will have some error handling, will show the error message if failed
    - use jQuery + aJax to make interaction more smooth(e.g. use partial refresh in shopping cart)
