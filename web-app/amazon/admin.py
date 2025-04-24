from django.contrib import admin
from .models import Package, Order, Item

# Register your models here.
admin.site.register(Item)
admin.site.register(Package)
admin.site.register(Order)