package dev.pgpilot.orchestrator.health

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(HealthController::class)
class HealthControllerTest(
    @Autowired private val mvc: MockMvc,
) {
    @Test
    fun `GET healthz returns 200 with status ok`() {
        mvc
            .perform(get("/healthz"))
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.service").value("pgpilot-orchestrator"))
            .andExpect(jsonPath("$.version").exists())
    }
}
