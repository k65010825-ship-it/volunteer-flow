import MockAdapter from "axios-mock-adapter";
import { afterEach, describe, expect, it } from "vitest";
import { apiClient, clearAccessToken, setAccessToken } from "./http";
import { refreshSession } from "./auth";

describe("apiClient", () => {
  const mock = new MockAdapter(apiClient);
  afterEach(() => {
    mock.reset();
    clearAccessToken();
  });

  it("shares the route restoration refresh with a concurrent 401 retry", async () => {
    const finishes: ((value: [number, unknown]) => void)[] = [];
    mock.onPost("/api/v1/auth/refresh").reply(
      () =>
        new Promise((resolve) => {
          finishes.push(resolve);
        }),
    );
    mock.onGet("/protected").replyOnce(401);
    mock.onGet("/protected").reply(200, {});
    const restore = refreshSession();
    const protectedRequest = apiClient.get("/protected");
    // Allow the asynchronous Axios interceptor to reach the shared refresh operation.
    await new Promise((resolve) => setTimeout(resolve, 20));
    const refreshCount = mock.history.post.length;
    finishes.forEach((finish) =>
      finish([200, { data: { accessToken: "fresh" } }]),
    );
    await Promise.all([restore, protectedRequest]);
    expect(refreshCount).toBe(1);
  });

  it("refreshes once after 401 and retries with the new bearer token", async () => {
    setAccessToken("expired");
    mock.onGet("/protected").replyOnce(401);
    mock
      .onPost("/api/v1/auth/refresh")
      .reply(200, { data: { accessToken: "fresh" } });
    mock
      .onGet("/protected")
      .reply((config) => [
        200,
        { authorization: config.headers?.Authorization },
      ]);

    const response = await apiClient.get("/protected");

    expect(response.data.authorization).toBe("Bearer fresh");
    expect(mock.history.post).toHaveLength(1);
  });

  it("does not recursively refresh when refresh itself fails", async () => {
    setAccessToken("expired");
    mock.onGet("/protected").reply(401);
    mock.onPost("/api/v1/auth/refresh").reply(401);

    await expect(apiClient.get("/protected")).rejects.toBeTruthy();
    expect(mock.history.post).toHaveLength(1);
  });
});
