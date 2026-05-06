package net.balsoftware.claude;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionSnapshot(
        List<SerializableTurn> turns,
        List<String> loadedContextClassNames,
        String systemPrompt,
        String timestamp,
        int totalInputTokens,
        int totalOutputTokens,
        int totalCacheCreationTokens,
        int totalCacheReadTokens
) {
    // Record components provide getters automatically
    public List<SerializableTurn> getTurns() { return turns; }
    public List<String> getLoadedContextClassNames() { return loadedContextClassNames; }
    public int getTotalInputTokens() { return totalInputTokens; }
    public String getTimestamp() { return timestamp; }
}
