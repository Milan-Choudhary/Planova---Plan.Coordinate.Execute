import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // Read VITE_* variables from the ROOT .env (one file for the whole project).
  // Vite only exposes variables that start with VITE_ to browser code, so
  // POSTGRES_PASSWORD / JWT_SECRET in the same file are never bundled into the frontend.
  envDir: '..',
  server: {
    port: 5173,
    // Convenience: if VITE_API_BASE_URL is empty, "/api/..." calls are forwarded to the backend.
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
