from django.contrib.auth.models import User
from django.contrib.auth.password_validation import validate_password
from django.core.exceptions import ValidationError


def check_username(username, user):
    if " " in username:
        return ["username contains space"]
    if username != user.username:
        try:
            User.objects.get(username=username)
            return ["this name has already been used"]
        except User.DoesNotExist:
            return []
    return []


def check_password(old_p, new_p, user):
    # check old password
    if not user.check_password(old_p):
        return ["old password not match"]
    # check whether is the same
    if old_p == new_p:
        return ["old and new password are the same"]
    # check new password
    try:
        validate_password(new_p)
    except ValidationError as e:
        errors = []
        for error in e.error_list:
            errors.extend(error.messages)
        return errors
    return []

