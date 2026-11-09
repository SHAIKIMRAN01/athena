package com.athena.repository;

import com.athena.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * A page of users with their accounts loaded in the same query. A user
     * reaches the account through the reverse side of a one-to-one link,
     * which Hibernate would otherwise load with one more query per user.
     */
    @Override
    @EntityGraph(attributePaths = "account")
    Page<User> findAll(Specification<User> spec, Pageable pageable);
}
