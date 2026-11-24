package com.kenshix.deck

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressTest {
    @Test fun acceptsUsbOnlyAndPrivateAddresses() {
        listOf("", "10.0.0.4", "172.16.0.4", "172.31.0.4", "192.168.0.10")
            .forEach { assertTrue(it, validLan(it)) }
    }

    @Test fun rejectsPublicAndAmbiguousAddresses() {
        listOf("127.0.0.1", "8.8.8.8", "172.32.1.1", "100.64.0.1", "192.168.01.1",
            "192.168.0.1:43851", "192.168.0.1/path", "192.168.0.256", "::1")
            .forEach { assertFalse(it, validLan(it)) }
    }
}
