package net.balsoftware.claude;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

public interface ClaudeClient {
    OKHttpClaudeClient.RawResponse send(String model, List<ClaudeMessage> messages) throws IOException;

    /**
     * Like {@link #send}, but invokes {@code onTextDelta} for each text chunk as it arrives.
     * The default implementation is non-streaming: it sends normally and emits the whole text
     * once, so existing clients work unchanged.
     */
    default OKHttpClaudeClient.RawResponse sendStreaming(
            String model, List<ClaudeMessage> messages, Consumer<String> onTextDelta) throws IOException {
        OKHttpClaudeClient.RawResponse response = send(model, messages);
        if (onTextDelta != null && response.text() != null && !response.text().isEmpty()) {
            onTextDelta.accept(response.text());
        }
        return response;
    }
}