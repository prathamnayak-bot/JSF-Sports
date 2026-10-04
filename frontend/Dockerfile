# --- build the React app ---
FROM node:22-alpine AS build
# extra root certificates from docker/certs (antivirus HTTPS scanning / company proxies), for npm
COPY --from=certs . /tmp/certs/
RUN cat /tmp/certs/*.crt > /tmp/extra-ca.pem 2>/dev/null; true
WORKDIR /src
COPY package.json package-lock.json ./
RUN if [ -s /tmp/extra-ca.pem ]; then export NODE_EXTRA_CA_CERTS=/tmp/extra-ca.pem; fi; \
    npm ci --no-audit --no-fund
COPY . .
RUN npm run build

# --- serve it with nginx, which also forwards /api to the backend ---
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
