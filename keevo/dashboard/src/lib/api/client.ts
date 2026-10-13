import axios from "axios";
import { clearSession, getToken } from "@/lib/auth/auth-context";

const client = axios.create({
  baseURL: "/api/backend",
  headers: { "Content-Type": "application/json" },
});

client.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

client.interceptors.response.use(
  (res) => res,
  (error) => {
    if (error.response?.status === 401 && typeof window !== "undefined") {
      clearSession(); // otherwise the next refresh would restore the same dead token
      window.location.href = "/login";
    }
    return Promise.reject(error);
  }
);

export default client;
