package dev.pgpilot.orchestrator.health

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Lightweight liveness probe for the orchestrator.
 *
 * Kept intentionally separate from Spring Actuator's /actuator/health so
 * that health checks from load balancers and uptime monitors don't spin
 * up actuator's component discovery or expose internal details.
 */
@RestController
class HealthController(
    @Value("\${spring.application.name}") private val serviceName: String,
    @Value("\${info.app.version:0.0.0-SNAPSHOT}") private val version: String,
) {
    @GetMapping("/healthz")
    fun healthz(): Map<String, String> =
        mapOf(
            "status" to "ok",
            "service" to serviceName,
            "version" to version,
        )
}
