import axios, { AxiosError, type InternalAxiosRequestConfig } from "axios";

let accessToken: string | null = null;
interface RefreshedSession {
  accessToken: string;
  expiresAt?: string;
}
let refreshPromise: Promise<RefreshedSession> | null = null;

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

/** Router restoration and 401 retries must rotate the HttpOnly cookie only once. */
export function refreshAccessToken(): Promise<RefreshedSession> {
  refreshPromise ??= apiClient
    .post<{ data: RefreshedSession }>("/api/v1/auth/refresh")
    .then((response) => {
      const result = response.data.data;
      setAccessToken(result.accessToken);
      return result;
    })
    .finally(() => {
      refreshPromise = null;
    });
  return refreshPromise;
}

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
    try {
      const { accessToken: token } = await refreshAccessToken();
      original.headers.Authorization = `Bearer ${token}`;
      return apiClient(original);
    } catch (refreshError) {
      clearAccessToken();
      window.dispatchEvent(new Event("volunteerflow:session-expired"));
      throw refreshError;
    }
  },
);
