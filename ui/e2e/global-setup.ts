import { build, preview, type PreviewServer } from "vite";

const API_BASE_URL = "http://127.0.0.1:18080/api";
const PREVIEW_HOST = "127.0.0.1";
const PREVIEW_PORT = 4173;

export default async function globalSetup(): Promise<() => Promise<void>> {
  process.env.VITE_API_BASE_URL = API_BASE_URL;
  await build({ root: process.cwd(), mode: "test" });

  const server: PreviewServer = await preview({
    root: process.cwd(),
    preview: {
      host: PREVIEW_HOST,
      port: PREVIEW_PORT,
      strictPort: true,
    },
  });

  return async () => {
    const httpServer = server.httpServer;
    if (!httpServer) {
      return;
    }
    httpServer.closeAllConnections();
    await new Promise<void>((resolve, reject) => {
      httpServer.close((error) => (error ? reject(error) : resolve()));
    });
  };
}
