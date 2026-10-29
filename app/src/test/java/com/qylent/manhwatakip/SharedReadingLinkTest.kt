package com.qylent.manhwatakip

import org.junit.Assert.*
import org.junit.Test

class SharedReadingLinkTest {
    @Test fun externalShareExtractsOneValidatedReadingLink() {
        assertEquals("https://okutoon.com/test/bolum-1", SharedReadingLink.extract("Burayı okuyorum: https://okutoon.com/test/bolum-1"))
        assertThrows(IllegalArgumentException::class.java) { SharedReadingLink.extract("https://a.example/ https://b.example/") }
        assertThrows(IllegalArgumentException::class.java) { SharedReadingLink.extract("file:///secret") }
        assertThrows(IllegalArgumentException::class.java) { SharedReadingLink.extract("https://user:password@example.com/secret") }
    }
}
