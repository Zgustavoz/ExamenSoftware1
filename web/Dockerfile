# Compila la SPA y la sirve con el Nginx que ya hace de proxy del backend.
FROM node:22-alpine AS build
WORKDIR /app

# Las dependencias se copian aparte para que la caché sobreviva a un cambio de código.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Configuración pública de Firebase para el push web (la pasa docker-compose desde el .env de la raíz).
# Vite las incorpora al paquete durante el build; no son secretos.
ARG VITE_FIREBASE_API_KEY=
ARG VITE_FIREBASE_AUTH_DOMAIN=
ARG VITE_FIREBASE_PROJECT_ID=
ARG VITE_FIREBASE_STORAGE_BUCKET=
ARG VITE_FIREBASE_MESSAGING_SENDER_ID=
ARG VITE_FIREBASE_APP_ID=
ARG VITE_FIREBASE_VAPID_KEY=
ENV VITE_FIREBASE_API_KEY=$VITE_FIREBASE_API_KEY     VITE_FIREBASE_AUTH_DOMAIN=$VITE_FIREBASE_AUTH_DOMAIN     VITE_FIREBASE_PROJECT_ID=$VITE_FIREBASE_PROJECT_ID     VITE_FIREBASE_STORAGE_BUCKET=$VITE_FIREBASE_STORAGE_BUCKET     VITE_FIREBASE_MESSAGING_SENDER_ID=$VITE_FIREBASE_MESSAGING_SENDER_ID     VITE_FIREBASE_APP_ID=$VITE_FIREBASE_APP_ID     VITE_FIREBASE_VAPID_KEY=$VITE_FIREBASE_VAPID_KEY

RUN npm run build

FROM nginx:1.27-alpine AS runtime
# La configuración va dentro de la imagen: la imagen que se prueba es la misma que se despliega. `app.conf` tiene
# lo común (cabeceras, SPA, proxy del backend); `default.conf` es el servidor HTTP que la incluye.
COPY nginx/app.conf /etc/nginx/snippets/app.conf
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
# 127.0.0.1 y no localhost: dentro del contenedor localhost resuelve primero a ::1 y Nginx solo escucha en IPv4.
HEALTHCHECK --interval=15s --timeout=5s --retries=5     CMD wget -qO- http://127.0.0.1/healthz || exit 1
