import axios, { AxiosError, type InternalAxiosRequestConfig } from "axios";

let accessToken: string | null = null;
let refreshPromise: Promise<string> | null = null;

// Access tokens stay in memory; the browser sends the HttpOnly refresh cookie automatically.
export const setAccessToken = (token: string) => {
  accessToken = token;
};
export const clearAccessToken = () => {
  accessToken = null;
};
export const getAccessToken = () => accessToken;

export const apiClient = axios.create({
  baseURL: "",
  withCredentials: true,
  timeout: 10_000,
});

apiClient.interceptors.request.use((config) => {
  if (accessToken && config.url !== "/api/v1/auth/refresh")
    config.headers.Authorization = `Bearer ${accessToken}`;
  return config;
});

apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const original = error.config as
      | (InternalAxiosRequestConfig & { _retried?: boolean })
      | undefined;
    if (
      !original ||
      error.response?.status !== 401 ||
      original._retried ||
      original.url === "/api/v1/auth/refresh"
    )
      throw error;
    original._retried = true;
    // Reuse one refresh request when several API calls expire at the same time.
    refreshPromise ??= apiClient
      .post("/api/v1/auth/refresh")
      .then((response) => {
        const token = (response.data as { data: { accessToken: string } }).data
          .accessToken;
        setAccessToken(token);
        return token;
      })
      .finally(() => {
        refreshPromise = null;
      });
    try {
      const token = await refreshPromise;
      original.headers.Authorization = `Bearer ${token}`;
      return apiClient(original);
    } catch (refreshError) {
      clearAccessToken();
      window.dispatchEvent(new Event("volunteerflow:session-expired"));
      throw refreshError;
    }
  },
);
