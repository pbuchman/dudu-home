FROM node:22.22.0-bookworm-slim@sha256:dd9d21971ec4395903fa6143c2b9267d048ae01ca6d3ea96f16cb30df6187d94 AS build
WORKDIR /build
COPY package.json package-lock.json tsconfig.json ./
COPY backend/ backend/
COPY ui/ ui/
RUN npm ci && npm run build:backend && npm run build:ui

FROM node:22.22.0-bookworm-slim@sha256:dd9d21971ec4395903fa6143c2b9267d048ae01ca6d3ea96f16cb30df6187d94 AS backend
ENV NODE_ENV=production
WORKDIR /app
COPY --from=build /build/package.json /build/package-lock.json ./
COPY --from=build /build/backend/package.json backend/package.json
COPY --from=build /build/ui/package.json ui/package.json
RUN npm ci --omit=dev --workspace backend
COPY --from=build /build/backend/dist backend/dist
COPY --from=build /build/backend/sql backend/sql
CMD ["node", "backend/dist/main.js"]

FROM nginx:1.28-alpine@sha256:a8b39bd9cf0f83869a2162827a0caf6137ddf759d50a171451b335cecc87d236 AS web
COPY --from=build /build/ui/dist /usr/share/nginx/html
COPY deploy/templates/nginx.conf /etc/nginx/nginx.conf
ENTRYPOINT ["nginx"]
CMD ["-g", "daemon off;"]
