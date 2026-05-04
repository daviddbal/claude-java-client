package net.balsoftware.claude;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Test-only dummy collector. Always returns an empty list; never looks for source files.
 */
public class DummySourceFileCollector extends SourceFileCollector {
    public DummySourceFileCollector() {
        super(new SourceRootConfig(Collections.emptyList()));
    }

    @Override
    public List<SourceFile> collect(List<Class<?>> classes) throws IOException {
        return Collections.emptyList();
    }
}