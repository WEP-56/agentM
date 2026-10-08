package dev.agentm.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Separately opt in: refreshes Ubuntu's signed package indexes and requires Internet access. */
@RunWith(AndroidJUnit4::class)
class LinuxNetworkTest {
    @Test fun ubuntuCanDownloadAndVerifyPackageIndexes() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AgentMApplication
        assertTrue(app.linux.ready)
        val result = app.linux.runtime.run(script =
            "apt-get update -o Acquire::Retries=0 -o Acquire::http::Timeout=20 -o APT::Update::Error-Mode=any", timeoutSeconds = 180)
        println(result.output)
        assertEquals(result.output, 0, result.code)
    }
}
