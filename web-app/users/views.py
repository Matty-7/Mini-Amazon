from django.contrib import messages
from django.contrib.auth.decorators import login_required
from django.contrib.auth.password_validation import password_validators_help_text_html
from django.shortcuts import render, redirect
from django.urls import reverse

from .forms import UserRegisterForm
from .utils import *


def register(request):
    if request.method == 'POST':
        form = UserRegisterForm(request.POST)
        if form.is_valid():
            form.save()
            return redirect('login')
    else:
        form = UserRegisterForm()
    return render(request, 'users/register.html', {'form': form})


@login_required
def profile(request):
    context = {}
    user = request.user
    context["username"] = user.username
    context["email"] = user.email
    if request.method == 'POST':
        opera = request.POST["operation"]
        if opera == "update_profile":
            username = request.POST["username"]
            email = request.POST["email"]
            context["username"] = username
            context["email"] = email
            errors = check_username(username, user)
            if len(errors) == 0:
                user.username = username
                user.email = email
                user.save()
            else:
                context["name_errors"] = errors
        elif opera == "update_password":
            old_p = request.POST["old_password"]
            new_p = request.POST["new_password"]
            errors = check_password(old_p=old_p, new_p=new_p, user=user)
            if len(errors) == 0:
                user.set_password(new_p)
                user.save()
                messages.success(request, "successfully change the password")
                return redirect(reverse("login"))
            else:
                context["password_errors"] = errors
        elif opera == "update_seller":
            c = request.POST.getlist("register_seller")
            if len(c) == 0:
                user.profile.is_seller = False
            else:
                user.profile.is_seller = True
            user.profile.save()
        elif opera == "update_optional":
            ups_name = request.POST["ups_name"]
            default_x = request.POST["default_x"]
            default_y = request.POST["default_y"]
            if len(default_x) > 0:
                default_x = int(default_x)
                user.profile.default_x = default_x
            else:
                user.profile.default_x = -1
            if len(default_y) > 0:
                default_y = int(default_y)
                user.profile.default_y = default_y
            else:
                user.profile.default_y = -1
            user.profile.ups_name = ups_name
            user.save()

    context["help_text"] = password_validators_help_text_html
    return render(request, 'users/profile.html', context)



