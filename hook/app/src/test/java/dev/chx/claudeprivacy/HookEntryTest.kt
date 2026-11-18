package dev.chx.claudeprivacy

import org.junit.Test

class HookEntryTest {
    @Test fun moduleCanBeConstructedBeforeTheMainLooperExists() {
        // Any Android framework call here fails in the JVM Android stubs.
        HookEntry()
    }
}
