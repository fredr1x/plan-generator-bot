package dev.ydt.plangen.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * Telegram retries webhooks on timeouts (e.g. a cold start). The update id is recorded in its own
 * transaction before handling, so a retry, or a poison message, is never processed twice.
 */
@ApplicationScoped
public class Dedup {

    @Inject
    EntityManager em;

    @Transactional
    public boolean firstTime(long updateId) {
        return em.createNativeQuery(
                        "INSERT INTO plangen.processed_updates (update_id) VALUES (:id) ON CONFLICT DO NOTHING")
                .setParameter("id", updateId)
                .executeUpdate() == 1;
    }
}
