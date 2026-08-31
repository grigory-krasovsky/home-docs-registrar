package com.example.homedocsregistrar.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

/** Shared construction of the Anthropic client from config, so every caller wires it the same way. */
public final class AnthropicClients {

    private AnthropicClients() {
    }

    /**
     * Build a client, or {@code null} when no api key is configured (callers then degrade gracefully).
     * Identity-linked keys aren't scoped to a workspace and need the {@code anthropic-workspace-id}
     * header on every request; workspace-scoped keys don't, so it's only sent when configured.
     */
    public static AnthropicClient build(String apiKey, String workspaceId) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        var builder = AnthropicOkHttpClient.builder().apiKey(apiKey);
        if (workspaceId != null && !workspaceId.isBlank()) {
            builder.putHeader("anthropic-workspace-id", workspaceId);
        }
        return builder.build();
    }
}
