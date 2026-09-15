import MockAdapter from "axios-mock-adapter";
import { afterEach, describe, expect, it } from "vitest";
import { apiClient, clearAccessToken, setAccessToken } from "./http";

describe("apiClient", () => {
  const mock = new MockAdapter(apiClient);
  afterEach(() => {
    mock.reset();
    clearAccessToken();
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
