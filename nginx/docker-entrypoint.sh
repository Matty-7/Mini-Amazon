#!/bin/sh
set -e

envsubst '${SERVER_NAME}' < /etc/nginx/conf.d/web-app.conf.template > /etc/nginx/conf.d/default.conf

exec "$@" 
