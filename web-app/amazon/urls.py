from django.urls import path

from . import views

urlpatterns = [
    path('', views.home, name="home"),
    path('category/<category>', views.home_category, name="home_category"),
    path('seller/<int:seller_id>', views.home_seller, name="home_seller"),
    path('item/<int:item_id>', views.item_detail, name="item_detail"),
    path('checkout/<int:package_id>', views.checkout, name="checkout"),
    path('shopcart', views.shop_cart, name="shop_cart"),
    path('add_update_item/<item_id>', views.add_update_item, name="add_update_item"),
    path('item_management', views.item_management, name="item_management"),
    path('change_cnt', views.change_cnt, name="change_cnt"),
    path('check_item', views.check_item, name="check_item"),
    path('delete_item', views.delete_item, name="delete_item"),
    path('listpackage/', views.list_package, name='list-package'),
    path('listpackage/<int:package_id>/', views.list_package_detail, name='list-package-detail'),
    path('deletepackage/<int:package_id>', views.delete_package, name='delete-package'),
]
