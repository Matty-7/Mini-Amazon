from django.contrib.auth.models import User
from django.db import models
from django.utils.timezone import now

class WareHouse(models.Model):
    x = models.IntegerField(default=1)
    y = models.IntegerField(default=1)

    def __str__(self):
        return "<" + str(self.x) + ", " + str(self.y) + ">"

class Category(models.Model):
    category = models.CharField(max_length=50, blank=False, null=False)

    def __str__(self):
        return self.category

class Item(models.Model):
    description = models.CharField(max_length=100, blank=False, null=False)
    price = models.FloatField(default=0.99, blank=False, null=False)
    img = models.CharField(max_length=50)
    category = models.ForeignKey(Category, on_delete=models.SET_NULL, null=True)
    seller = models.ForeignKey(User, on_delete=models.SET_NULL, null=True)
    on_sell = models.BooleanField(default=True)

    def __str__(self):
        return self.description

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
    item_price = models.FloatField(default=0.99)
    package = models.ForeignKey(Package, on_delete=models.CASCADE, related_name="orders", null=True, blank=True)
    creation_time = models.DateTimeField(default=now)

    # return the total price for current order
    def total(self):
        return self.item_cnt * self.item.price

    # return the total price for current order
    def total_fixed(self):
        return self.item_cnt * self.item_price

    def __str__(self):
        return "<" + str(self.package_id) + ", <" + str(self.item_id) + ', ' + str(self.item_cnt) + ">>"

