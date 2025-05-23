# Generated by Django 3.1.8 on 2025-04-25 03:02

from django.db import migrations, models
import django.db.models.deletion


class Migration(migrations.Migration):

    initial = True

    dependencies = [
        ('auth', '0012_alter_user_first_name_max_length'),
    ]

    operations = [
        migrations.CreateModel(
            name='Profile',
            fields=[
                ('user', models.OneToOneField(on_delete=django.db.models.deletion.CASCADE, primary_key=True, serialize=False, to='auth.user')),
                ('is_seller', models.BooleanField(default=False)),
                ('ups_name', models.CharField(blank=True, default='', max_length=50)),
                ('default_x', models.IntegerField(blank=True, default=-1)),
                ('default_y', models.IntegerField(blank=True, default=-1)),
            ],
        ),
    ]
