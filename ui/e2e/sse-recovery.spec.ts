import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { Socket } from "node:net";
import { expect, test } from "@playwright/test";

const API_PORT = 18080;
const session = { id: "session-1", agentId: "agent-1", title: "SSE recovery" };
const userMessage = {
  id: "user-message-1",
  sessionId: session.id,
  role: "user",
  content: "连接断开后还能恢复吗？",
};
const finalAnswer = {
  id: "assistant-message-1",
  sessionId: session.id,
  role: "assistant",
  content: "可以，已从持久化消息中恢复最终答案。",
};

interface FixtureState {
  submitted: boolean;
  messagePolls: number;
  sseConnections: number;
  sseResponse?: ServerResponse;
  sockets: Set<Socket>;
  unexpectedRequests: string[];
}

function apiResponse(data: unknown) {
  return { code: 200, message: "ok", data };
}

function writeJson(response: ServerResponse, data: unknown, statusCode = 200) {
  response.writeHead(statusCode, {
    "access-control-allow-origin": "*",
    "content-type": "application/json",
  });
  response.end(JSON.stringify(data));
}

function enableCors(response: ServerResponse) {
  response.setHeader("access-control-allow-origin", "*");
  response.setHeader("access-control-allow-methods", "GET,POST,OPTIONS");
  response.setHeader("access-control-allow-headers", "content-type");
}

async function readRequestBody(request: IncomingMessage): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) {
    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  }
  return Buffer.concat(chunks).toString("utf8");
}

function startFixtureServer(state: FixtureState): Promise<Server> {
  const server = createServer((request, response) => {
    void (async () => {
      enableCors(response);
      if (request.method === "OPTIONS") {
        response.writeHead(204);
        response.end();
        return;
      }

      const url = new URL(
        request.url ?? "/",
        `http://127.0.0.1:${API_PORT}`,
      );

      if (request.method === "GET" && url.pathname === "/sse/connect/session-1") {
        state.sseConnections += 1;
        response.writeHead(200, {
          "access-control-allow-origin": "*",
          "cache-control": "no-cache",
          connection: "keep-alive",
          "content-type": "text/event-stream",
        });
        // Keep the initial connection open after init so the test can submit
        // a message before the simulated run emits its non-terminal event.
        response.write("event: init\ndata: {}\n\n");
        state.sseResponse = response;
        request.on("close", () => {
          if (state.sseResponse === response) {
            state.sseResponse = undefined;
          }
        });
        return;
      }

      if (request.method === "GET" && url.pathname === "/api/agents") {
        writeJson(response, apiResponse({
          agents: [{ id: session.agentId, name: "Fixture agent", model: "deepseek-chat" }],
        }));
        return;
      }
      if (request.method === "GET" && url.pathname === "/api/tools") {
        writeJson(response, apiResponse([]));
        return;
      }
      if (request.method === "GET" && url.pathname === "/api/knowledge-bases") {
        writeJson(response, apiResponse({ knowledgeBases: [] }));
        return;
      }
      if (request.method === "GET" && url.pathname === "/api/chat-sessions") {
        writeJson(response, apiResponse({ chatSessions: [session] }));
        return;
      }
      if (request.method === "GET" && url.pathname === "/api/chat-sessions/session-1") {
        writeJson(response, apiResponse({ chatSession: session }));
        return;
      }
      if (request.method === "GET" && url.pathname === "/api/chat-messages/session/session-1") {
        const messages = !state.submitted
          ? []
          : state.messagePolls++ === 0
            ? [userMessage]
            : [userMessage, finalAnswer];
        writeJson(response, apiResponse({ chatMessages: messages }));
        return;
      }
      if (request.method === "POST" && url.pathname === "/api/chat-messages") {
        await readRequestBody(request);
        state.submitted = true;
        // Simulate a run that reaches the thinking state and then loses the
        // terminal SSE event. The UI must reconcile the persisted answer.
        if (state.sseResponse) {
          state.sseResponse.write(
            `data: ${JSON.stringify({
              type: "AI_THINKING",
              payload: { statusText: "思考中：正在分析断线恢复" },
            })}\n\n`,
          );
          state.sseResponse.end();
          state.sseResponse = undefined;
        }
        writeJson(response, apiResponse({ chatMessageId: userMessage.id }));
        return;
      }

      state.unexpectedRequests.push(`${request.method ?? "UNKNOWN"} ${url.pathname}`);
      writeJson(response, apiResponse(null), 404);
    })().catch((error: unknown) => {
      if (!response.headersSent) {
        writeJson(response, apiResponse(null), 500);
      } else {
        response.destroy(error instanceof Error ? error : undefined);
      }
    });
  });

  server.on("connection", (socket) => {
    state.sockets.add(socket);
    socket.once("close", () => state.sockets.delete(socket));
  });

  return new Promise<Server>((resolve, reject) => {
    const handleError = (error: Error) => {
      server.off("listening", handleListening);
      reject(error);
    };
    const handleListening = () => {
      server.off("error", handleError);
      resolve(server);
    };
    server.once("error", handleError);
    server.once("listening", handleListening);
    server.listen(API_PORT, "127.0.0.1");
  });
}

function stopFixtureServer(server: Server, state: FixtureState): Promise<void> {
  state.sseResponse?.end();
  for (const socket of state.sockets) {
    socket.destroy();
  }
  return new Promise((resolve, reject) => {
    server.close((error) => (error ? reject(error) : resolve()));
    server.closeAllConnections();
  });
}

test("recovers a persisted final answer after the terminal SSE event is lost", async ({ page }) => {
  const state: FixtureState = {
    submitted: false,
    messagePolls: 0,
    sseConnections: 0,
    sockets: new Set(),
    unexpectedRequests: [],
  };
  const server = await startFixtureServer(state);
  try {
    await page.goto(`/chat/${session.id}`);
    const input = page.getByPlaceholder("输入消息...");
    await expect(input).toBeEnabled();
    await input.fill(userMessage.content);
    await input.press("Enter");

    const chatUrl = page.url();
    const thinkingStatus = page.getByText("✨ 思考中", { exact: true });
    await expect(page.getByText("思考中：正在分析断线恢复", { exact: true })).toBeVisible();
    await expect(page.getByText(finalAnswer.content)).toBeVisible({ timeout: 10_000 });
    expect(page.url()).toBe(chatUrl);
    await expect(thinkingStatus).toHaveCount(0);
    expect(state.messagePolls).toBeGreaterThanOrEqual(2);
    expect(state.sseConnections).toBeGreaterThanOrEqual(1);
    expect(state.unexpectedRequests).toEqual([]);
  } finally {
    await page.close();
    await stopFixtureServer(server, state);
  }
});
