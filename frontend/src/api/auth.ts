import { apiClient, setAccessToken, refreshAccessToken } from "./http";
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
  return refreshAccessToken();
}
export async function me(): Promise<CurrentUser> {
  return (await apiClient.get("/api/v1/auth/me")).data.data;
}
export async function updateProfile(payload: {
  realName: string;
  contact: string;
}): Promise<CurrentUser> {
  return (await apiClient.put("/api/v1/auth/me", payload)).data.data;
}
export async function changePassword(payload: {
  currentPassword: string;
  newPassword: string;
}): Promise<void> {
  await apiClient.post("/api/v1/auth/password", payload);
}
export async function logout() {
  await apiClient.post("/api/v1/auth/logout");
}
