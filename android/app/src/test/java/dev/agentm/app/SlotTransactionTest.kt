package dev.agentm.app

import dev.agentm.app.packages.SlotTransaction
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class SlotTransactionTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun failedVerificationLeavesSelectedSlotAndUserDataUntouched() {
        val old = temp.newFile("old").apply { writeText("working version") }
        val home = temp.newFile("home").apply { writeText("user data") }
        val stage = temp.newFile("stage")
        assertThrows(IOException::class.java) {
            SlotTransaction { stage.delete() }.use { throw IOException("digest or probe failure") }
        }
        assertTrue(old.isFile); assertEquals("user data", home.readText()); assertFalse(stage.exists())
    }
    @Test fun journalFailureDoesNotRetireOldSlot() {
        val old = temp.newFile("old")
        val stage = temp.newFile("stage")
        assertThrows(IOException::class.java) {
            SlotTransaction { stage.delete() }.use { transaction ->
                transaction.publish({ throw IOException("atomic manifest write failed") }, { old.delete() })
            }
        }
        assertTrue(old.exists()); assertFalse(stage.exists())
    }
    @Test fun cleanupFailureAfterCommitKeepsNewSlotPublished() {
        val stage = temp.newFile("stage")
        var selected = "old"
        SlotTransaction { stage.delete() }.use { transaction ->
            transaction.publish({ selected = "new" }, { assertEquals("new", selected); throw IOException("old slot busy") })
        }
        assertEquals("new", selected); assertTrue(stage.exists())
    }
}
