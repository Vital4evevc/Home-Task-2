package com.example.jobs.common.repository;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.example.jobs.common.dao.JobCron;

public interface IJobCronRepository extends JpaRepository<JobCron, String> {

    Page<JobCron> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** ACTIVE → CANCELLED; 0 rows if it was already cancelled. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update JobCron s set s.status = com.example.jobs.common.enums.JobCronStatus.CANCELLED,
                                     s.cancelledAt = :now, s.updatedAt = :now
            where s.id = :id and s.status = com.example.jobs.common.enums.JobCronStatus.ACTIVE
            """)
    int markCancelled(@Param("id") String id, @Param("now") Instant now);
}
