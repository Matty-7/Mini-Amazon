from django.contrib.auth.models import User
from django.db import models
from django.utils.timezone import now

# Create your models here.

"""
README
use real amazon as reference
Item stands for the actual item we are selling.
Order stands for an order, contains an item and count.
Package stands for one pack, contains delivery-related information and list of orders.
1. Every time use click "add to cart" or "place order", we will create a new order
(NOTE: this action should happen in the item detail page)
    1.1 if user click "add to cart", we will leave the package field of order NULL
    1.2 if user click "place order", we will create a new package, and add this order into package
2. To fetch the shop car of one user ---> user.orders.filter(package__isnull=True)
3. To fetch all historical packages of one user ---> user.packages
"""


# warehouse class, used to initial a list of ware house
class WareHouse(models.Model):
    x = models.IntegerField(default=1)
    y = models.IntegerField(default=1)

    def __str__(self):
        return "<" + str(self.x) + ", " + str(self.y) + ">"


# The category of different items.
class Category(models.Model):
    category = models.CharField(max_length=50, blank=False, null=False)

    def __str__(self):
        return self.category


# This is the class which represent one specific item.
class Item(models.Model):
    description = models.CharField(max_length=100, blank=False, null=False)
    # below are some values we might want for advance feature(we can add more)
    price = models.FloatField(default=0.99, blank=False, null=False)
    img = models.CharField(max_length=50, default="/static/img/sample.jpg")
    # even we delete the seller or category info, we should still keep the item info
    # since there will be some history order referring to it
    category = models.ForeignKey(Category, on_delete=models.SET_NULL, null=True)
    seller = models.ForeignKey(User, on_delete=models.SET_NULL, null=True)
    on_sell = models.BooleanField(default=True)

    def __str__(self):
        return self.description


# This stands for a package(stands for one purchase).
# each package can contains several orders(e.g. package.orders.all())
class Package(models.Model):
    # user info
    owner = models.ForeignKey(User, on_delete=models.CASCADE, related_name="packages")
    # the warehouse id where this package stores
    warehouse = models.IntegerField(default=1)
    # the status of current package, possible value:
    # processing --- purchase but not receive the successful message
    # processed  --- purchase successful
    # packing    --- package arrived warehouse and is packing
    # packed     --- package is packed
    # loading    --- the truck arrived at warehouse and is loading
    # loaded     --- finish loading
    # delivering --- delivering to destination
    # delivered  --- delivered(final state of this package)
    # error      --- any error state(should follow by the actual error message, e.g. error: illegal item)
    status = models.CharField(max_length=100, default="processing")
    dest_x = models.IntegerField(default=10)
    dest_y = models.IntegerField(default=10)
    creation_time = models.DateTimeField(default=now)
    # associate ups account name for this package(optional)
    ups_name = models.CharField(max_length=50, default="", blank=True)

    def total(self):
        total = 0
        for order in self.orders.all():
            total += order.total()
        return total

    # NOTE: this value will not change according the item price(aka it's fixed)
    def total_fixed(self):
        total = 0
        for order in self.orders.all():
            total += order.total_fixed()
        return total

    def info_str(self):
        info = "Your order has successfully been placed.\nDetail info:\n"
        for order in self.orders.all():
            info += "* %d %s(total $ %.2f)\n" % (order.item_cnt, order.item.description, order.total_fixed())
        info += "total: $%.2f" % (self.total_fixed())
        return info

    def __str__(self):
        return "<" + str(self.warehouse) + ", " + self.status + ">"


# order = item id + item counts (+ item price)
class Order(models.Model):
    # user info
    owner = models.ForeignKey(User, on_delete=models.CASCADE, related_name="orders")
    item = models.ForeignKey(Item, on_delete=models.SET_NULL, null=True)
    item_cnt = models.IntegerField(default=1)
    # since seller can change the price, but the price of any finished order can't be change
    # so we need to store the price info of any finished order
    item_price = models.FloatField(default=0.99)
    # package id
    package = models.ForeignKey(Package, on_delete=models.CASCADE, related_name="orders", null=True, blank=True)
    creation_time = models.DateTimeField(default=now)

    # return the total price for current order
    def total(self):
        return self.item_cnt * self.item.price

    # return the total price for current order
    # NOTE: this value will not change according the item price(aka it's fixed)
    def total_fixed(self):
        return self.item_cnt * self.item_price

    def __str__(self):
        return "<" + str(self.package_id) + ", <" + str(self.item_id) + ', ' + str(self.item_cnt) + ">>"

