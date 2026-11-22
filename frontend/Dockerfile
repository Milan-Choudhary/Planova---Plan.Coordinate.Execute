# NOT YET BUILT as an image: Docker was unavailable in the authoring sandbox.
# (The same `npm ci && npm run build` steps were run outside Docker and passed.)

# ---- Stage 1: build the static site ----
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# No VITE_API_BASE_URL at build time => the app calls relative /api/... URLs, which nginx forwards.
RUN npm run build

# ---- Stage 2: serve with nginx ----
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
