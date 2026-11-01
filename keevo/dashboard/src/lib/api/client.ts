import axios, { type InternalAxiosRequestConfig } from "axios";
import { clearSession } from "@/lib/auth/auth-context";
import { freshToken, renewSession } from "@/lib/auth/session";

const client = axios.create({
  baseURL: "/api/backend",
  headers: { "Content-Type": "application/json" },
});

/** Story 16.11 — the access token (30 min) is renewed before expiry and on TOKEN_EXPIRED. */
client.interceptors.request.use(async (config) => {
  const token = await freshToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

type RetriableConfig = InternalAxiosRequestConfig & { _retried?: boolean };

function endSession() {
  clearSession(); // otherwise the next refresh would restore the same dead token
  if (typeof window !== "undefined") window.location.href = "/login";
}

client.interceptors.response.use(
  (res) => res,
  async (error) => {
    if (error.response?.status === 401 && typeof window !== "undefined") {
      const config = error.config as RetriableConfig | undefined;
      const domainCode = (error.response?.data as { domainCode?: unknown } | undefined)?.domainCode;
      if (domainCode === "TOKEN_EXPIRED" && config && !config._retried) {
        const outcome = await renewSession();
        if (outcome === "renewed") {
          config._retried = true;
          return client(config);
        }
        if (outcome === "unreachable") return Promise.reject(error);
      }
      endSession();
    }
    return Promise.reject(error);
  }
);

export default client;
