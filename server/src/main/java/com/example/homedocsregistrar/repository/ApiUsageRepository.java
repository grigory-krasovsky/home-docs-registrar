package com.example.homedocsregistrar.repository;

import com.example.homedocsregistrar.domain.ApiUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApiUsageRepository extends JpaRepository<ApiUsage, Long> {

    /**
     * Atomically add token counts to the singleton counter row. Returns the number of rows updated
     * (0 when the row doesn't exist yet, so the caller can seed it). JPQL, so it runs on both Postgres
     * and the H2 used in tests.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ApiUsage u set u.inputTokens = u.inputTokens + :in, "
            + "u.outputTokens = u.outputTokens + :out where u.id = :id")
    int addUsage(@Param("id") long id, @Param("in") long in, @Param("out") long out);
}
