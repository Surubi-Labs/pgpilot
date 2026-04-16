package dev.pgpilot.orchestrator.core

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring configuration for shared infrastructure beans that other modules
 * depend on. Kept intentionally small: only wiring that has a single
 * implementation goes here.
 */
@Configuration
class CoreConfig {
    @Bean
    fun pgPilotClock(): PgPilotClock = PgPilotClock.System
}
