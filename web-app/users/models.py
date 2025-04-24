from django.db import models
from django.contrib.auth.models import User


class Profile(models.Model):
    user = models.OneToOneField(User, primary_key=True, on_delete=models.CASCADE)
    is_seller = models.BooleanField(default=False)
    ups_name = models.CharField(max_length=50, default="", blank=True)
    default_x = models.IntegerField(default=-1, blank=True)
    default_y = models.IntegerField(default=-1, blank=True)

    def __str__(self):
        return f'{self.user.username} Profile'
