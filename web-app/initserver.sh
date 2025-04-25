#!/bin/bash
chmod 777 ./amazon/static/img
python3 manage.py makemigrations
python3 manage.py makemigrations users amazon
python3 manage.py migrate auth
python3 manage.py migrate
res="$?"
while [ "$res" != "0" ]
do
    sleep 3;
    python3 manage.py migrate
    res="$?"
done

