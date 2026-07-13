# ---- build stage (ng build на GitHub-раннере) ----
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# ---- runtime stage (nginx отдаёт статику + проксирует /api) ----
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/nir-frontend/browser /usr/share/nginx/html
EXPOSE 80
