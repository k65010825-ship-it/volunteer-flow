import { apiClient, setAccessToken } from "./http";
import type { CurrentUser } from "./types";
export async function login(username: string, password: string) {
  const r = await apiClient.post("/api/v1/auth/login", { username, password });
  setAccessToken(r.data.data.accessToken);
  return r.data.data;
}
export async function register(payload: {
  username: string;
  password: string;
  realName: string;
  studentNumber: string;
  contact: string;
}) {
  const r = await apiClient.post("/api/v1/auth/register", payload);
  setAccessToken(r.data.data.accessToken);
  return r.data.data;
}
export async function refreshSession() {
  const r = await apiClient.post("/api/v1/auth/refresh");
  setAccessToken(r.data.data.accessToken);
  return r.data.data;
}
export async function me(): Promise<CurrentUser> {
  return (await apiClient.get("/api/v1/auth/me")).data.data;
}
export async function logout() {
  await apiClient.post("/api/v1/auth/logout");
}
