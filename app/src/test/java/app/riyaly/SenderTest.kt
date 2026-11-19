package app.riyaly

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderTest {
    @Test fun adSendersAreRecognized() {
        assertTrue(isAdSender("SAB-Ad"))
        assertTrue(isAdSender("ALRAJHI-AD"))
        assertTrue(isAdSender("stc-ad"))
        assertFalse(isAdSender("SAB"))
        assertFalse(isAdSender("AlRajhiBank"))
        assertFalse(isAdSender("SNB-AlAhli"))
    }
}
