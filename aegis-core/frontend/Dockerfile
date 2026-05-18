FROM nginx:1.27-alpine

COPY aegis-core/frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY aegis-core/frontend/dist /usr/share/nginx/html

EXPOSE 80
