/// <reference types="node" />

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const repositoryRoot = resolve(process.cwd(), "..");

function readRepositoryFile(path: string): string {
  return readFileSync(resolve(repositoryRoot, path), "utf8");
}

describe("frontend container contract", () => {
  it("builds the Vue application with Node and serves it from Nginx", () => {
    const dockerfile = readRepositoryFile("frontend/Dockerfile");

    expect(dockerfile).toContain("FROM node:22-alpine AS build");
    expect(dockerfile).toContain("COPY frontend/package*.json ./");
    expect(dockerfile).toContain("RUN npm ci");
    expect(dockerfile).toContain("COPY frontend/ ./");
    expect(dockerfile).toContain("RUN npm run build");
    expect(dockerfile).toContain("FROM nginx:1.27-alpine");
    expect(dockerfile).toContain(
      "COPY deploy/nginx/nginx.conf /etc/nginx/conf.d/default.conf",
    );
    expect(dockerfile).toContain(
      "COPY --from=build /workspace/dist /usr/share/nginx/html",
    );
    expect(dockerfile).toContain("EXPOSE 80");
  });

  it("keeps SPA routing and proxies API paths without stripping their prefixes", () => {
    const nginx = readRepositoryFile("deploy/nginx/nginx.conf");

    expect(nginx).toContain("try_files $uri $uri/ /index.html;");
    expect(nginx).toMatch(
      /location \/api\/\s*\{[\s\S]*?proxy_pass http:\/\/backend:8080;/,
    );
    expect(nginx).toMatch(
      /location \/actuator\/\s*\{[\s\S]*?proxy_pass http:\/\/backend:8080;/,
    );
    expect(nginx).not.toContain("proxy_pass http://backend:8080/");
  });

  it("keeps exact API and actuator roots out of the SPA fallback", () => {
    const nginx = readRepositoryFile("deploy/nginx/nginx.conf");

    expect(nginx).toMatch(
      /location = \/api\s*\{[\s\S]*?proxy_pass http:\/\/backend:8080;/,
    );
    expect(nginx).toMatch(
      /location = \/actuator\s*\{[\s\S]*?proxy_pass http:\/\/backend:8080;/,
    );
  });

  it("forwards the original request metadata without exposing the upstream name", () => {
    const nginx = readRepositoryFile("deploy/nginx/nginx.conf");

    expect(nginx).toContain("proxy_set_header Host $host;");
    expect(nginx).toContain("proxy_set_header X-Real-IP $remote_addr;");
    expect(nginx).toContain(
      "proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;",
    );
    expect(nginx).toContain("proxy_set_header X-Forwarded-Proto $scheme;");
    expect(nginx).toContain("server_tokens off;");
    expect(nginx).toContain("proxy_hide_header X-Powered-By;");
    expect(nginx).not.toMatch(/add_header\s+[^;]*backend/i);
  });

  it("keeps required build inputs while excluding local and generated files", () => {
    const dockerignore = readRepositoryFile(".dockerignore");

    expect(dockerignore).toContain(".git");
    expect(dockerignore).toContain(".idea/");
    expect(dockerignore).toContain(".vscode/");
    expect(dockerignore).toContain(".superpowers/");
    expect(dockerignore).toContain("*.log");
    expect(dockerignore).toContain("output/");
    expect(dockerignore).toContain(".env");
    expect(dockerignore).toContain(".env.*");
    expect(dockerignore).toContain("backend/target/");
    expect(dockerignore).toContain("frontend/node_modules/");
    expect(dockerignore).toContain("frontend/dist/");
    expect(dockerignore).toContain("backend/config/application-local.yml");
    expect(dockerignore).toContain("!frontend/package.json");
    expect(dockerignore).toContain("!frontend/package-lock.json");
    expect(dockerignore).toContain("!frontend/src/**");
    expect(dockerignore).toContain("!deploy/nginx/nginx.conf");
  });
});
